package experiment;

import java.nio.file.*;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import com.ssafy.ssasukae.config.*;
import com.ssafy.ssasukae.domain.card.redis.RedisCardStateStore;
import com.ssafy.ssasukae.domain.card.service.CardService;
import com.ssafy.ssasukae.domain.card.websocket.*;
import com.ssafy.ssasukae.domain.performance.redis.performance.RedisPerformanceStore;
import com.ssafy.ssasukae.global.security.jwt.*;
import com.ssafy.ssasukae.global.security.websocket.*;
import com.ssafy.ssasukae.global.websocket.message.WebSocketEvent;
import com.ssafy.ssasukae.global.websocket.publisher.WebSocketEventPublisher;
import com.ssafy.ssasukae.global.websocket.relay.RedisWebSocketEventRelay;
import tools.jackson.databind.ObjectMapper;

/** Production implementations only; the earlier SnapshotRecoveryWorker prototype is not imported. */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableJpaAuditing
@EntityScan("com.ssafy.ssasukae.domain")
@EnableJpaRepositories("com.ssafy.ssasukae.domain")
@Import({CardService.class,RedisCardStateStore.class,RedisPerformanceStore.class,CardSchedulerConfig.class,
    CardWebSocketController.class,CardWebSocketEventPublisher.class,WebSocketRelayConfig.class,
    WebSocketMessageBrokerConfig.class,WebSocketHeartbeatConfig.class,CorsProperties.class,
    WebSocketAuthenticationInterceptor.class,WebSocketAuthorizationInterceptor.class,
    WebSocketStompErrorHandler.class,JwtProperties.class,JwtTokenProvider.class,
    TokenBlacklistService.class,ActiveSessionService.class,CardExperimentApplication.FixtureApi.class,
    LbWiring.class,LbApi.class,ProductionProbeApi.class})
public class ProductionVerificationApplication {
  public static void main(String[] args) {
    SpringApplication.run(ProductionVerificationApplication.class,args);
    System.out.println("LB_APPLICATION_READY instance="+System.getenv("INSTANCE"));
  }
  @Bean Clock clock() {return Clock.systemUTC();}
  @Bean SecurityFilterChain experimentHttpSecurity(HttpSecurity http) throws Exception {
    return http.csrf(csrf->csrf.disable()).authorizeHttpRequests(auth->auth.anyRequest().permitAll()).build();
  }
  @Bean WebSocketEventPublisher journaledPublisher(SimpMessagingTemplate local,RedisWebSocketEventRelay relay,ObjectMapper mapper) {
    return new WebSocketEventPublisher(local,relay) {
      @Override public synchronized void publishToRoom(Long roomId,WebSocketEvent<?> event) {
        try {
          Files.writeString(Path.of("results/server-events.jsonl"),mapper.writeValueAsString(
              Map.of("observedAt",Instant.now().toString(),"instance",System.getenv("INSTANCE"),"event",event))+"\n",
              StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        } catch(Exception error) {throw new IllegalStateException(error);}
        super.publishToRoom(roomId,event);
      }
    };
  }
}
