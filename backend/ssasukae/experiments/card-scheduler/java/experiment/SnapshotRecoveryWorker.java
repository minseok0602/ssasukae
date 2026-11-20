package experiment;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.ssafy.ssasukae.domain.card.redis.*;
import com.ssafy.ssasukae.domain.card.websocket.*;
import com.ssafy.ssasukae.domain.card.websocket.payload.*;
import com.ssafy.ssasukae.domain.card.websocket.type.CardEffectEndReason;
import com.ssafy.ssasukae.domain.performance.redis.performance.*;
import com.ssafy.ssasukae.domain.performance.type.PerformanceStatus;
import com.ssafy.ssasukae.domain.room.repository.*;
import com.ssafy.ssasukae.domain.room.type.*;

/**
 * Experiment-only recovery candidate. The existing snapshot is the durable deadline record.
 * SCAN also finds snapshots without a separate scheduling index, avoiding partial index writes.
 * Every transition uses the same MySQL room row lock as the production CardService.
 * This is a measured prototype, not an automatically selected production policy.
 */
@EnableScheduling
@ConditionalOnProperty(name="experiment.recovery.enabled", havingValue="true")
public class SnapshotRecoveryWorker {
  private final StringRedisTemplate redis;
  private final CardStateStore cards;
  private final PerformanceStore performances;
  private final RoomRepository rooms;
  private final RoomParticipantRepository participants;
  private final CardWebSocketEventPublisher events;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final String policy;
  private final long maxLateMs;
  private final Set<String> seenBeforeDeadline = ConcurrentHashMap.newKeySet();

  public SnapshotRecoveryWorker(StringRedisTemplate redis, CardStateStore cards,
      PerformanceStore performances, RoomRepository rooms, RoomParticipantRepository participants,
      CardWebSocketEventPublisher events, PlatformTransactionManager tm, Clock clock,
      @Value("${experiment.recovery.policy}") String policy,
      @Value("${experiment.recovery.max-late-ms:30000}") long maxLateMs) {
    if (!Set.of("cancel", "bounded-start").contains(policy))
      throw new IllegalArgumentException("Explicit experimental policy required: cancel or bounded-start");
    this.redis=redis; this.cards=cards; this.performances=performances; this.rooms=rooms;
    this.participants=participants; this.events=events; this.tx=new TransactionTemplate(tm);
    this.clock=clock; this.policy=policy; this.maxLateMs=maxLateMs;
  }

  @Scheduled(fixedDelayString="${experiment.recovery.scan-delay-ms:100}")
  public void scan() {
    Set<String> live = new HashSet<>();
    try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match("room:*:active-card").count(100).build())) {
      while (cursor.hasNext()) {
        String key=cursor.next();
        Long roomId=Long.parseLong(key.split(":")[1]);
        try {
          cards.findRoomCard(roomId).ifPresent(card -> {
            String identity=identity(card); live.add(identity);
            OffsetDateTime due=card.status()==RoomCardStatus.PENDING ? card.activateAt() : card.endsAt();
            if (!now().isBefore(due))
              System.err.println("RECOVERY_LOCK_ATTEMPT instance="+System.getenv("INSTANCE")
                  +" room="+roomId+" at="+now());
            if (card.status()==RoomCardStatus.PENDING && now().isBefore(card.activateAt()))
              seenBeforeDeadline.add(identity);
            tx.executeWithoutResult(ignored -> rooms.findByIdForUpdate(roomId).ifPresent(room -> {
              RoomCardSnapshot current=cards.findRoomCard(roomId).orElse(null);
              if (current==null || !identity(current).equals(identity)) return;
              PerformanceSnapShot performance=performances.findActiveByRoomId(roomId).orElse(null);
              boolean playing=room.getMode()==RoomMode.BATTLE && room.getStatus()==RoomStatus.PLAYING
                  && performance!=null && performance.status()==PerformanceStatus.PLAYING
                  && performance.performanceId().equals(current.performanceId());
              if (!playing) { close(current, CardEffectEndReason.SYSTEM_CANCELLED); return; }
              OffsetDateTime now=now();
              if (current.status()==RoomCardStatus.ACTIVE) {
                if (!now.isBefore(current.endsAt())) close(current, CardEffectEndReason.DURATION_EXPIRED);
                return;
              }
              if (now.isBefore(current.activateAt())) return;
              boolean permitted=seenBeforeDeadline.contains(identity)
                  || (policy.equals("bounded-start")
                      && Duration.between(current.activateAt(), now).toMillis() <= maxLateMs);
              CardAssignmentSnapshot assignment=cards.findAssignment(roomId,current.performanceId(),
                  current.sourceParticipantId()).orElse(null);
              boolean online=participants.findById(current.sourceParticipantId())
                  .filter(p -> p.getRoom().getId().equals(roomId) && p.isOnline()).isPresent();
              if (!permitted || !online || assignment==null
                  || assignment.status()!=CardAssignmentStatus.ASSIGNED
                  || performance.remainingPlaybackMs(now) <= 0) {
                close(current, CardEffectEndReason.SYSTEM_CANCELLED); return;
              }
              RoomCardSnapshot active=current.active(now,now.plusSeconds(current.durationSeconds()),null);
              cards.saveAssignment(assignment.used(now));
              cards.saveRoomCard(active);
              events.publishToRoom(roomId,CardWebSocketEventType.CARD_EFFECT_STARTED,
                  new CardEffectStartedPayload(active.performanceId(),active.sourceParticipantId(),
                      active.targetParticipantId(),active.targetType(),active.cardId(),active.cardCode(),
                      active.cardName(),active.description(),active.effectType(),active.effectValue(),
                      active.durationSeconds(),active.startedAt(),active.endsAt()));
            }));
          });
        } catch (RuntimeException error) {
          // A failed room must not prevent other rooms or subsequent scans from recovering.
          System.err.println("RECOVERY_RETRY room="+roomId+" type="+error.getClass().getSimpleName());
        }
      }
      seenBeforeDeadline.retainAll(live);
    } catch (RuntimeException error) {
      System.err.println("RECOVERY_SCAN_RETRY type="+error.getClass().getSimpleName());
    }
  }

  private OffsetDateTime now() { return OffsetDateTime.now(clock); }
  private String identity(RoomCardSnapshot c) {
    return c.roomId()+":"+c.performanceId()+":"+c.sourceParticipantId()+":"+c.approvedAt().toInstant();
  }
  private void close(RoomCardSnapshot c, CardEffectEndReason reason) {
    cards.deleteRoomCard(c.roomId());
    if (c.status()==RoomCardStatus.PENDING) {
      events.publishToRoom(c.roomId(),CardWebSocketEventType.CARD_ACTIVATION_CANCELLED,
          new CardActivationCancelledPayload(c.performanceId(),c.sourceParticipantId(),reason,now()));
    } else {
      events.publishToRoom(c.roomId(),CardWebSocketEventType.CARD_EFFECT_ENDED,
          new CardEffectEndedPayload(c.performanceId(),c.sourceParticipantId(),c.targetParticipantId(),
              c.targetType(),c.cardId(),c.cardCode(),c.cardName(),c.description(),c.effectType(),
              c.effectValue(),c.previousValue(),c.durationSeconds(),c.startedAt(),now(),reason));
    }
  }
}
