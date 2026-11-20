package experiment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import com.ssafy.ssasukae.config.*;
import com.ssafy.ssasukae.domain.card.redis.*;
import com.ssafy.ssasukae.domain.card.service.CardService;
import com.ssafy.ssasukae.domain.card.type.CardTier;
import com.ssafy.ssasukae.domain.card.websocket.*;
import com.ssafy.ssasukae.domain.card.websocket.type.*;
import com.ssafy.ssasukae.domain.performance.redis.performance.*;
import com.ssafy.ssasukae.domain.room.entity.*;
import com.ssafy.ssasukae.domain.room.repository.*;
import com.ssafy.ssasukae.domain.room.type.*;
import com.ssafy.ssasukae.domain.user.entity.User;
import com.ssafy.ssasukae.domain.user.repository.UserRepository;
import com.ssafy.ssasukae.domain.user.type.*;
import com.ssafy.ssasukae.global.security.jwt.*;
import com.ssafy.ssasukae.global.security.websocket.*;
import com.ssafy.ssasukae.global.websocket.message.WebSocketEvent;
import com.ssafy.ssasukae.global.websocket.publisher.WebSocketEventPublisher;
import tools.jackson.databind.ObjectMapper;

/** Isolated experiment entry point. Never component-scanned by the production application. */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableJpaAuditing
@EntityScan("com.ssafy.ssasukae.domain")
@EnableJpaRepositories("com.ssafy.ssasukae.domain")
@Import({CardService.class, RedisCardStateStore.class, RedisPerformanceStore.class,
    CardSchedulerConfig.class, CardWebSocketController.class, CardWebSocketEventPublisher.class,
    WebSocketMessageBrokerConfig.class, WebSocketHeartbeatConfig.class, CorsProperties.class,
    WebSocketAuthenticationInterceptor.class, WebSocketAuthorizationInterceptor.class,
    WebSocketStompErrorHandler.class, JwtProperties.class, JwtTokenProvider.class,
    TokenBlacklistService.class, ActiveSessionService.class, CardExperimentApplication.FixtureApi.class,
    SnapshotRecoveryWorker.class})
public class CardExperimentApplication {
  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(CardExperimentApplication.class);
    app.setDefaultProperties(Map.ofEntries(
        Map.entry("spring.config.name", "card-experiment-no-secret-files"),
        Map.entry("spring.datasource.url", "jdbc:mysql://mysql:3306/card_experiment"),
        Map.entry("spring.datasource.username", "experiment"),
        Map.entry("spring.datasource.password", "experiment-local-only"),
        Map.entry("spring.jpa.hibernate.ddl-auto", "update"),
        Map.entry("spring.jpa.open-in-view", "false"),
        Map.entry("spring.data.redis.host", "redis"),
        Map.entry("spring.data.redis.repositories.enabled", "false"),
        Map.entry("spring.main.banner-mode", "off"),
        Map.entry("logging.level.root", "WARN"),
        Map.entry("jwt.secret", "card-experiment-local-only-secret-at-least-32-bytes"),
        Map.entry("jwt.access-token-expiration", "3600000"),
        Map.entry("jwt.refresh-token-expiration", "3600000")));
    app.run(args);
  }

  @Bean Clock clock() { return Clock.systemUTC(); }

  // Only experiment HTTP helpers are public. STOMP retains the production JWT/room interceptors.
  @Bean SecurityFilterChain experimentHttpSecurity(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
  }

  @Bean WebSocketEventPublisher journaledPublisher(SimpMessagingTemplate template, ObjectMapper mapper) {
    return new WebSocketEventPublisher(template) {
      @Override public synchronized void publishToRoom(Long roomId, WebSocketEvent<?> event) {
        try {
          Files.writeString(Path.of("/experiment/results/server-events.jsonl"),
              mapper.writeValueAsString(Map.of("observedAt", OffsetDateTime.now().toString(),
                  "instance", System.getenv().getOrDefault("INSTANCE", "app"), "event", event)) + "\n",
              StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) { throw new IllegalStateException("Event journal failed", e); }
        super.publishToRoom(roomId, event);
      }
    };
  }

  @RestController
  @RequestMapping("/experiment")
  public static class FixtureApi {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private SnapshotRecoveryWorker recovery;
    private final UserRepository users;
    private final RoomRepository rooms;
    private final RoomParticipantRepository participants;
    private final PerformanceStore performances;
    private final CardStateStore cards;
    private final ActiveSessionService sessions;
    private final JwtTokenProvider tokens;
    private final TransactionTemplate tx;
    private final StringRedisTemplate redis;

    public FixtureApi(UserRepository users, RoomRepository rooms, RoomParticipantRepository participants,
        PerformanceStore performances, CardStateStore cards, ActiveSessionService sessions,
        JwtTokenProvider tokens, PlatformTransactionManager tm, StringRedisTemplate redis) {
      this.users = users; this.rooms = rooms; this.participants = participants;
      this.performances = performances; this.cards = cards; this.sessions = sessions;
      this.tokens = tokens; this.tx = new TransactionTemplate(tm); this.redis = redis;
    }

    @GetMapping("/ready") public Map<String, Object> ready() {
      return Map.of("ready", true, "at", OffsetDateTime.now().toString());
    }

    @PostMapping("/recover") public void recover() {
      if (recovery==null) throw new IllegalStateException("Recovery candidate is disabled");
      recovery.scan();
    }

    @PostMapping("/fixture") public Map<String, Object> fixture(@RequestParam(defaultValue="15") int duration) {
      if (duration < 1 || duration > 120) throw new IllegalArgumentException("duration 1..120");
      return tx.execute(ignored -> {
        String suffix = UUID.randomUUID().toString();
        User performer = user(suffix + "p");
        User viewer = user(suffix + "v");
        Room room = rooms.save(Room.create(suffix.substring(0, 6), "card experiment", RoomMode.BATTLE,
            performer, "stub-openvidu-" + suffix, LocalDateTime.now()));
        RoomParticipant singer = RoomParticipant.join(room, performer, LocalDateTime.now());
        singer.connect("stub-singer-" + suffix); singer.promoteToPerformer(); participants.save(singer);
        RoomParticipant owner = RoomParticipant.join(room, viewer, LocalDateTime.now());
        owner.connect("stub-owner-" + suffix); participants.save(owner);
        room.startPerformance();
        OffsetDateTime now = OffsetDateTime.now();
        PerformanceSnapShot performance = PerformanceSnapShot.prepare(performances.nextPerformanceId(),
            room.getId(), singer.getId(), performer.getId(), 1L, 600_000L, now.minusSeconds(1))
            .startPlayback(now);
        if (!performances.create(performance)) throw new IllegalStateException("Fixture conflict");
        cards.saveAssignment(new CardAssignmentSnapshot(room.getId(), performance.performanceId(),
            owner.getId(), viewer.getId(), 1L, "EXPERIMENT_KEY", "experiment card", "fixture",
            CardEffectType.MR_KEY_CHANGE, CardEffectTargetType.PERFORMER, -3, duration,
            CardTier.G, CardAssignmentStatus.ASSIGNED, now, null));
        String sid = suffix;
        sessions.setActiveSession(viewer.getId(), sid, 3600000L);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roomId", room.getId()); result.put("performanceId", performance.performanceId());
        result.put("participantId", owner.getId()); result.put("userId", viewer.getId());
        result.put("cardId", 1); result.put("durationSeconds", duration);
        result.put("token", tokens.createAccessToken(viewer.getId(), viewer.getEmail(), "USER", sid));
        return result;
      });
    }

    @PostMapping("/end-performance/{roomId}") public void end(@PathVariable Long roomId) {
      // Simulates a performance changed while the card JVM was down, without card cleanup.
      performances.findActiveByRoomId(roomId).ifPresent(performances::delete);
      tx.executeWithoutResult(ignored -> rooms.findByIdForUpdate(roomId).ifPresent(Room::recoverPerformance));
    }

    private User user(String suffix) {
      return users.save(User.builder().email(suffix + "@experiment.invalid").nickname("experiment")
          .provider(OAuthProvider.GOOGLE).providerId(suffix).role(Role.USER).build());
    }
  }
}
