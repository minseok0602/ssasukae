package experiment;

import java.time.Instant;
import java.util.Map;
import java.security.Principal;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import com.ssafy.ssasukae.domain.room.service.RoomService;
import com.ssafy.ssasukae.domain.song.entity.Song;
import com.ssafy.ssasukae.domain.song.repository.SongRepository;
import com.ssafy.ssasukae.global.security.jwt.*;

@RestController
public class LbApi {
  private final RoomService rooms;
  private final SongRepository songs;
  private final JwtTokenProvider tokens;
  private final ActiveSessionService sessions;
  private final TokenBlacklistService blacklist;
  private final SimpMessagingTemplate messaging;
  public LbApi(RoomService rooms,SongRepository songs,JwtTokenProvider tokens,ActiveSessionService sessions,
      TokenBlacklistService blacklist,SimpMessagingTemplate messaging) {
    this.rooms=rooms;this.songs=songs;this.tokens=tokens;this.sessions=sessions;
    this.blacklist=blacklist;this.messaging=messaging;
  }

  @GetMapping("/experiment/lb/node") public Map<String,Object> node() {
    return Map.of("instance",System.getenv("INSTANCE"),"at",Instant.now().toString());
  }

  @PostMapping("/experiment/lb/seed") @Transactional public void seed() {
    if (!songs.existsById(1L)) {
      Song song=songs.save(Song.create("LB fixture song","fixture artist",600,3,
          "fixture-cover","fixture-mr","fixture-midi","fixture-lyrics"));
      if (!song.getId().equals(1L)) throw new IllegalStateException("Fresh isolated DB must reserve song 1");
    }
  }

  @GetMapping("/experiment/lb/snapshot/{roomId}")
  public Map<String,Object> snapshot(@PathVariable Long roomId,@RequestHeader("Authorization") String authorization) {
    if (!authorization.startsWith("Bearer ")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    String token=authorization.substring(7);
    if (!tokens.validateToken(token) || blacklist.isBlacklisted(tokens.getJti(token))
        || !sessions.isActiveSession(tokens.getUserId(token),tokens.getSid(token)))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return Map.of("instance",System.getenv("INSTANCE"),"snapshot",rooms.getRoomSnapshot(tokens.getUserId(token),roomId));
  }

  @MessageMapping("/experiment/identity")
  @SendToUser(value="/queue/lb-probe",broadcast=false)
  public Map<String,Object> identity(@Header("simpSessionId") String sessionId,Principal principal) {
    return Map.of("kind","identity","instance",System.getenv("INSTANCE"),"sessionId",sessionId,
        "userId",principal.getName(),"at",Instant.now().toString());
  }

  @MessageMapping("/rooms/{roomId}/lb-probe")
  public void roomProbe(@DestinationVariable Long roomId,@Header("simpSessionId") String sessionId,Principal principal) {
    messaging.convertAndSend("/topic/rooms/"+roomId,(Object)Map.of("eventType","EXPERIMENT_ROOM_PROBE",
        "roomId",roomId,"payload",Map.of("instance",System.getenv("INSTANCE"),"sessionId",sessionId)));
  }
}
