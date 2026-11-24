package experiment;

import java.nio.file.*;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.messaging.*;
import com.ssafy.ssasukae.domain.card.service.CardService;
import com.ssafy.ssasukae.domain.performance.redis.leaderboard.*;
import com.ssafy.ssasukae.domain.performance.redis.performance.PerformanceStore;
import com.ssafy.ssasukae.domain.room.repository.*;
import com.ssafy.ssasukae.domain.room.service.RoomService;
import com.ssafy.ssasukae.domain.song.repository.SongRepository;
import com.ssafy.ssasukae.domain.user.repository.UserRepository;
import com.ssafy.ssasukae.global.websocket.publisher.WebSocketEventPublisher;
import com.ssafy.ssasukae.integration.aws.S3StorageService;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods=false)
@Import(RedisRoomLeaderboardStore.class)
public class LbWiring {
  @Bean RoomService snapshotRoomService(RoomRepository rooms, RoomParticipantRepository participants,
      UserRepository users, WebSocketEventPublisher events, CardService cards, PerformanceStore performances,
      RoomLeaderboardStore leaderboard, Clock clock, SongRepository songs) {
    S3StorageService fakeS3 = new S3StorageService(null,null,null) {
      @Override public String presignedUrl(String key) { return "https://fixture.invalid/"+key; }
    };
    // Only getRoomSnapshot is exposed. The unused media/mutation dependencies are intentionally absent.
    return new RoomService(rooms,participants,users,null,null,events,cards,performances,leaderboard,clock,songs,fakeS3);
  }

  @Bean SessionJournal sessionJournal(ObjectMapper mapper) { return new SessionJournal(mapper); }

  static class SessionJournal {
    private final ObjectMapper mapper;
    SessionJournal(ObjectMapper mapper) { this.mapper=mapper; }
    @EventListener public void connected(SessionConnectedEvent e) { record("CONNECTED",e); }
    @EventListener public void subscribed(SessionSubscribeEvent e) { record("SUBSCRIBED",e); }
    @EventListener public void disconnected(SessionDisconnectEvent e) { record("DISCONNECTED",e); }
    private synchronized void record(String kind, AbstractSubProtocolEvent e) {
      var h=StompHeaderAccessor.wrap(e.getMessage());
      try {
        Files.writeString(Path.of("results/sessions-"+System.getenv("INSTANCE")+".jsonl"),
            mapper.writeValueAsString(Map.of("at",Instant.now().toString(),"kind",kind,
                "instance",System.getenv("INSTANCE"),"sessionId",h.getSessionId(),
                "destination",String.valueOf(h.getDestination()),"user",e.getUser()==null?"":e.getUser().getName()))+"\n",
            StandardOpenOption.CREATE,StandardOpenOption.APPEND);
      } catch (Exception error) { throw new IllegalStateException(error); }
    }
  }
}
