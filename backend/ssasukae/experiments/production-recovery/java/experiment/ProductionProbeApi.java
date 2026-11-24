package experiment;

import java.time.*;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.ssafy.ssasukae.domain.card.redis.*;
import com.ssafy.ssasukae.domain.card.websocket.type.CardEffectTargetType;
import com.ssafy.ssasukae.domain.performance.redis.performance.PerformanceStore;
import com.ssafy.ssasukae.domain.room.repository.RoomParticipantRepository;
import com.ssafy.ssasukae.domain.room.service.RoomService;
import com.ssafy.ssasukae.global.security.jwt.*;
import com.ssafy.ssasukae.global.websocket.message.WebSocketEvent;
import com.ssafy.ssasukae.global.websocket.publisher.WebSocketEventPublisher;

@RestController
public class ProductionProbeApi {
  private final JwtTokenProvider tokens;
  private final ActiveSessionService sessions;
  private final RoomService rooms;
  private final RoomParticipantRepository participants;
  private final CardStateStore cards;
  private final PerformanceStore performances;
  private final WebSocketEventPublisher events;
  private final Clock clock;
  public ProductionProbeApi(JwtTokenProvider tokens,ActiveSessionService sessions,RoomService rooms,
      RoomParticipantRepository participants,CardStateStore cards,PerformanceStore performances,
      WebSocketEventPublisher events,Clock clock) {
    this.tokens=tokens;this.sessions=sessions;this.rooms=rooms;this.participants=participants;
    this.cards=cards;this.performances=performances;this.events=events;this.clock=clock;
  }
  private Long authenticated(String authorization,Long roomId) {
    String token=authorization.startsWith("Bearer ")?authorization.substring(7):"";
    if(!tokens.validateToken(token)||!sessions.isActiveSession(tokens.getUserId(token),tokens.getSid(token)))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    Long userId=tokens.getUserId(token);rooms.getRoomSnapshot(userId,roomId);return userId;
  }
  @PostMapping("/experiment/relay-test/{roomId}")
  public Map<String,Object> relay(@PathVariable Long roomId,@RequestParam int sequence,
      @RequestParam(defaultValue="false") boolean personal,@RequestHeader("Authorization") String authorization) {
    Long user=authenticated(authorization,roomId);
    var event=WebSocketEvent.roomEvent(()->personal?"PRIVATE_RELAY_PROBE":"ROOM_RELAY_PROBE",roomId,
        Map.of("sequence",sequence,"sourceNode",System.getenv("INSTANCE")));
    if(personal)events.publishToUser(user.toString(),"/queue/relay-test",event);
    else events.publishToRoom(roomId,event);
    return Map.of("sourceNode",System.getenv("INSTANCE"),"sequence",sequence);
  }
  @PostMapping("/experiment/seed-pending/{roomId}")
  public Map<String,Object> pending(@PathVariable Long roomId,@RequestHeader("Authorization") String authorization) {
    Long user=authenticated(authorization,roomId);
    Long owner=participants.findByRoomIdAndUserId(roomId,user).orElseThrow().getId();
    var performance=performances.findActiveByRoomId(roomId).orElseThrow();
    var assignment=cards.findAssignment(roomId,performance.performanceId(),owner).orElseThrow();
    OffsetDateTime now=OffsetDateTime.now(clock);
    var pending=new RoomCardSnapshot(roomId,performance.performanceId(),RoomCardStatus.PENDING,owner,
        assignment.targetType()==CardEffectTargetType.CARD_OWNER?owner:performance.performerParticipantId(),
        assignment.cardId(),assignment.cardCode(),assignment.cardName(),assignment.description(),
        assignment.effectType(),assignment.targetType(),assignment.effectValue(),assignment.durationSeconds(),null,
        now,now.plusSeconds(2),null,null);
    cards.saveRoomCard(pending);
    return Map.of("sourceNode",System.getenv("INSTANCE"),"card",pending);
  }
}
