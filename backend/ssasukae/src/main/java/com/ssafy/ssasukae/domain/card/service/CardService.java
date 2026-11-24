package com.ssafy.ssasukae.domain.card.service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.ssasukae.domain.card.entity.Card;
import com.ssafy.ssasukae.domain.card.recovery.CardRecoveryProperties;
import com.ssafy.ssasukae.domain.card.redis.CardAssignmentSnapshot;
import com.ssafy.ssasukae.domain.card.redis.CardAssignmentStatus;
import com.ssafy.ssasukae.domain.card.redis.CardStateStore;
import com.ssafy.ssasukae.domain.card.redis.RoomCardSnapshot;
import com.ssafy.ssasukae.domain.card.redis.RoomCardStatus;
import com.ssafy.ssasukae.domain.card.repository.CardRepository;
import com.ssafy.ssasukae.domain.card.websocket.CardWebSocketEventPublisher;
import com.ssafy.ssasukae.domain.card.websocket.CardWebSocketEventType;
import com.ssafy.ssasukae.domain.card.websocket.payload.CardActivationCancelledPayload;
import com.ssafy.ssasukae.domain.card.websocket.payload.CardActivationScheduledPayload;
import com.ssafy.ssasukae.domain.card.websocket.payload.CardAssignedPayload;
import com.ssafy.ssasukae.domain.card.websocket.payload.CardEffectEndedPayload;
import com.ssafy.ssasukae.domain.card.websocket.payload.CardEffectStartedPayload;
import com.ssafy.ssasukae.domain.card.websocket.type.CardEffectEndReason;
import com.ssafy.ssasukae.domain.performance.redis.performance.PerformanceSnapShot;
import com.ssafy.ssasukae.domain.performance.redis.performance.PerformanceStore;
import com.ssafy.ssasukae.domain.performance.type.PerformanceStatus;
import com.ssafy.ssasukae.domain.room.entity.Room;
import com.ssafy.ssasukae.domain.room.entity.RoomParticipant;
import com.ssafy.ssasukae.domain.room.repository.RoomParticipantRepository;
import com.ssafy.ssasukae.domain.room.repository.RoomRepository;
import com.ssafy.ssasukae.domain.room.type.ConnectionStatus;
import com.ssafy.ssasukae.domain.room.type.RoomMode;
import com.ssafy.ssasukae.domain.room.type.RoomStatus;
import com.ssafy.ssasukae.global.exception.websocket.WebSocketBusinessException;
import com.ssafy.ssasukae.global.exception.websocket.WebSocketErrorCode;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class CardService {

  private static final ZoneId SEOUL_ZONE_ID = ZoneId.of("Asia/Seoul");
  private static final int ACTIVATION_COUNTDOWN_SECONDS = 3;
  private static final long MINIMUM_REMAINING_PLAYBACK_MS = 3_000L;
  private final RoomRepository roomRepository;
  private final RoomParticipantRepository participantRepository;
  private final CardRepository cardRepository;
  private final PerformanceStore performanceStore;
  private final CardStateStore cardStateStore;
  private final CardWebSocketEventPublisher eventPublisher;
  private final TaskScheduler taskScheduler;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;
  private final CardRecoveryProperties recoveryProperties;
  private final Set<ScheduledCard> scheduledTasks = ConcurrentHashMap.newKeySet();

  private record ScheduledCard(
      Long roomId,
      Long performanceId,
      Long participantId,
      Instant approvedAt,
      Instant activateAt,
      RoomCardStatus status,
      Instant deadline) {}

  @Autowired
  public CardService(
      RoomRepository roomRepository,
      RoomParticipantRepository participantRepository,
      CardRepository cardRepository,
      PerformanceStore performanceStore,
      CardStateStore cardStateStore,
      CardWebSocketEventPublisher eventPublisher,
      @Qualifier("cardTaskScheduler") TaskScheduler taskScheduler,
      Clock clock,
      PlatformTransactionManager transactionManager,
      CardRecoveryProperties recoveryProperties) {
    this.roomRepository = roomRepository;
    this.participantRepository = participantRepository;
    this.cardRepository = cardRepository;
    this.performanceStore = performanceStore;
    this.cardStateStore = cardStateStore;
    this.eventPublisher = eventPublisher;
    this.taskScheduler = taskScheduler;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.recoveryProperties = recoveryProperties;
  }

  /** 단위 테스트 및 순수 객체 테스트에서 사용하는 호환 생성자다. */
  public CardService(
      RoomRepository roomRepository,
      RoomParticipantRepository participantRepository,
      CardRepository cardRepository,
      PerformanceStore performanceStore,
      CardStateStore cardStateStore,
      CardWebSocketEventPublisher eventPublisher,
      TaskScheduler taskScheduler,
      Clock clock) {
    this.roomRepository = roomRepository;
    this.participantRepository = participantRepository;
    this.cardRepository = cardRepository;
    this.performanceStore = performanceStore;
    this.cardStateStore = cardStateStore;
    this.eventPublisher = eventPublisher;
    this.taskScheduler = taskScheduler;
    this.clock = clock;
    this.transactionTemplate = null;
    this.recoveryProperties = new CardRecoveryProperties();
  }

  // 공연 시작할 때, 공연자 제외 인원들 카드 배정
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void assignForPlayback(PerformanceSnapShot performance) {
    try {
      Room room = roomRepository.findByIdForUpdate(performance.roomId()).orElse(null);
      if (room == null
          || room.getMode() != RoomMode.BATTLE
          || room.getStatus() != RoomStatus.PLAYING
          || performance.status() != PerformanceStatus.PLAYING) {
        return;
      }

      List<RoomParticipant> recipients =
          participantRepository
              .findAllByRoomIdAndConnectionStatusIn(
                  room.getId(), List.of(ConnectionStatus.CONNECTED))
              .stream()
              .filter(participant -> !performance.isPerformedBy(participant.getId()))
              .toList();
      if (recipients.isEmpty()) {
        return;
      }

      List<Card> drawableCards =
          cardRepository.findAll().stream().filter(Card::isDrawable).toList();
      if (drawableCards.isEmpty()) {
        log.warn(
            "뽑을 카드 없음 (roomId={}, performanceId={})", room.getId(), performance.performanceId());
        return;
      }

      if (drawableCards.size() < recipients.size()) {
        log.warn(
            "뽑을 카드 없음 (roomId={}, performanceId={}, cardCount={}, recipientCount={})",
            room.getId(),
            performance.performanceId(),
            drawableCards.size(),
            recipients.size());
        return;
      }

      OffsetDateTime assignedAt = now();
      List<CardAssignmentSnapshot> assignments = new ArrayList<>();
      for (int i = 0; i < recipients.size(); i++) {
        RoomParticipant recipient = recipients.get(i);
        Card card = drawableCards.get(i);
        CardAssignmentSnapshot cardAssignmentSnapshot =
            toAssignment(room.getId(), performance, recipient, card, assignedAt);
        cardStateStore.saveAssignment(cardAssignmentSnapshot);
        assignments.add(cardAssignmentSnapshot);
      }
      assignments.forEach(this::publishAssigned);
    } catch (RuntimeException exception) {
      log.error(
          "카드 배정 실패 (roomId={}, performanceId={})",
          performance.roomId(),
          performance.performanceId(),
          exception);
    }
  }

  // 카드 활성화
  @Transactional
  public void activate(Long userId, Long roomId, Long performanceId) {
    validatePositive(userId, "userId");
    validatePositive(roomId, "roomId");
    validatePositive(performanceId, "performanceId");

    // 존재하는 방인가
    Room room =
        roomRepository
            .findByIdForUpdate(roomId)
            .orElseThrow(() -> business(WebSocketErrorCode.RESOURCE_NOT_FOUND));
    // 배틀방인가
    if (room.getMode() != RoomMode.BATTLE) {
      throw business(WebSocketErrorCode.INVALID_ROOM_MODE);
    }
    // 공연중인가
    if (room.getStatus() != RoomStatus.PLAYING) {
      throw business(WebSocketErrorCode.INVALID_ROOM_STATE);
    }

    // 올바른 참가자인가
    RoomParticipant participant =
        participantRepository
            .findByRoomIdAndUserId(roomId, userId)
            .orElseThrow(() -> business(WebSocketErrorCode.ROOM_ACCESS_DENIED));
    if (!participant.isOnline()) {
      throw business(WebSocketErrorCode.PARTICIPANT_OFFLINE);
    }
    // 공연 정보가 스냅샷에 있나
    PerformanceSnapShot performance =
        performanceStore
            .findActiveByRoomId(roomId)
            .orElseThrow(() -> business(WebSocketErrorCode.NO_ACTIVE_PERFORMANCE));
    if (!performance.performanceId().equals(performanceId)) {
      throw business(WebSocketErrorCode.PERFORMANCE_MISMATCH);
    }
    if (performance.status() != PerformanceStatus.PLAYING || performance.startedAt() == null) {
      throw business(WebSocketErrorCode.PLAYBACK_NOT_RUNNING);
    }
    // 공연자인가
    if (performance.isPerformedBy(participant.getId())) {
      throw business(WebSocketErrorCode.PERFORMER_CANNOT_USE_CARD);
    }
    // 대기 시간이 남은 노래 시간보다 더 길면 시작 안함
    OffsetDateTime approvedAt = now();
    if (performance.remainingPlaybackMs(approvedAt) <= MINIMUM_REMAINING_PLAYBACK_MS) {
      throw business(WebSocketErrorCode.INSUFFICIENT_PLAYBACK_TIME);
    }

    // 그 외의 경우에 대해서는 정상 요청으로 간주 -> 카드 발행
    CardAssignmentSnapshot assignment =
        cardStateStore
            .findAssignment(roomId, performanceId, participant.getId())
            .orElseThrow(() -> business(WebSocketErrorCode.CARD_ASSIGNMENT_NOT_FOUND));
    validateAssignment(assignment);

    // 마이크 난입이면, 효과를 카드 사용자 본인한테 적용시켜야함
    Long targetParticipantId =
        assignment.targetType()
                == com.ssafy.ssasukae.domain.card.websocket.type.CardEffectTargetType.CARD_OWNER
            ? participant.getId()
            : performance.performerParticipantId();
    if (targetParticipantId == null) {
      throw business(WebSocketErrorCode.INVALID_CARD_TARGET);
    }

    validateNoRoomCard(roomId);

    // 프론트가 타이머 설정하기 위해서 3초 뒤의 시간을 넘겨줌
    OffsetDateTime activateAt = approvedAt.plusSeconds(ACTIVATION_COUNTDOWN_SECONDS);
    RoomCardSnapshot pendingRoomCard =
        new RoomCardSnapshot(
            roomId,
            performanceId,
            RoomCardStatus.PENDING,
            participant.getId(),
            targetParticipantId,
            assignment.cardId(),
            assignment.cardCode(),
            assignment.cardName(),
            assignment.description(),
            assignment.effectType(),
            assignment.targetType(),
            assignment.effectValue(),
            assignment.durationSeconds(),
            null,
            approvedAt,
            activateAt,
            null,
            null);

    cardStateStore.saveRoomCard(pendingRoomCard);

    // 카드 사용 예정 이벤트 발행 및 activatedAt 시각에 startEffect 메서드 실행
    afterCommit(
        () -> {
          scheduleStart(pendingRoomCard);
          eventPublisher.publishToRoom(
              roomId,
              CardWebSocketEventType.CARD_ACTIVATION_SCHEDULED,
              scheduledPayload(pendingRoomCard, approvedAt));
        });
  }

  public PerformanceSnapShot closeForPerformance(
      PerformanceSnapShot performance, CardEffectEndReason reason) {
    return doCloseForPerformance(performance, reason);
  }

  /** 가창자 재접속을 기다리는 동안 현재 예약/활성 카드만 종료한다. 공연을 재개해야 하므로 아직 사용하지 않은 개인 카드 배정은 유지한다. */
  public PerformanceSnapShot suspendForPerformance(
      PerformanceSnapShot performance, CardEffectEndReason reason) {
    Optional<RoomCardSnapshot> roomCardOptional = cardStateStore.findRoomCard(performance.roomId());
    if (roomCardOptional.isEmpty()
        || !performance.performanceId().equals(roomCardOptional.get().performanceId())) {
      return performance;
    }

    RoomCardSnapshot roomCard = roomCardOptional.get();
    if (roomCard.status() == RoomCardStatus.PENDING) {
      if (cardStateStore.removeRoomCard(roomCard)) publishCancelled(roomCard, reason);
      clearTasks(roomCard);
      return performance;
    }

    if (cardStateStore.removeRoomCard(roomCard)) publishEnded(roomCard, reason, now());
    clearTasks(roomCard);
    return performance;
  }

  private PerformanceSnapShot doCloseForPerformance(
      PerformanceSnapShot performance, CardEffectEndReason reason) {
    Optional<RoomCardSnapshot> roomCardOptional = cardStateStore.findRoomCard(performance.roomId());
    if (roomCardOptional.isPresent()
        && performance.performanceId().equals(roomCardOptional.get().performanceId())) {
      RoomCardSnapshot roomCard = roomCardOptional.get();
      if (roomCard.status() == RoomCardStatus.PENDING) {
        if (cardStateStore.removeRoomCard(roomCard)) publishCancelled(roomCard, reason);
        clearTasks(roomCard);
      } else if (roomCard.status() == RoomCardStatus.ACTIVE) {
        if (cardStateStore.removeRoomCard(roomCard)) publishEnded(roomCard, reason, now());
        clearTasks(roomCard);
      }
    }
    cardStateStore.deleteCardState(performance.roomId(), performance.performanceId());
    return performance;
  }

  public void closeRoom(Long roomId) {
    performanceStore
        .findActiveByRoomId(roomId)
        .ifPresent(
            performance -> closeForPerformance(performance, CardEffectEndReason.ROOM_TERMINATED));
  }

  public Optional<CardAssignmentSnapshot> findAssignment(
      Long roomId, Long performanceId, Long participantId) {
    return cardStateStore.findAssignment(roomId, performanceId, participantId);
  }

  public List<CardAssignmentSnapshot> findAssignments(Long roomId, Long performanceId) {
    return cardStateStore.findAssignments(roomId, performanceId);
  }

  public Optional<RoomCardSnapshot> findActiveCard(Long roomId) {
    return cardStateStore.findRoomCard(roomId);
  }

  /** Recover independently of whether a client reconnects to this node. */
  public void recoverRoomCard(Long roomId) {
    executeWithRoomLock(
        roomId,
        room -> {
          RoomCardSnapshot card = cardStateStore.findRoomCard(roomId).orElse(null);
          if (card == null) return;
          PerformanceSnapShot performance =
              performanceStore.findActiveByRoomId(roomId).orElse(null);
          if (!isPlaying(room, performance, card.performanceId())) {
            if (cardStateStore.removeRoomCard(card)) {
              if (card.status() == RoomCardStatus.PENDING)
                publishCancelled(card, CardEffectEndReason.SYSTEM_CANCELLED);
              else publishEnded(card, CardEffectEndReason.SYSTEM_CANCELLED, now());
            }
            clearTasks(card);
            cardStateStore.deleteCardState(roomId, card.performanceId());
            return;
          }
          OffsetDateTime current = now();
          if (card.status() == RoomCardStatus.PENDING) {
            if (current.isBefore(card.activateAt())) {
              afterCommit(() -> scheduleStart(card));
            } else if (current.isAfter(
                card.activateAt().plus(recoveryProperties.getStartExecutionGrace()))) {
              // Unknown overdue cards are never started late. Allow only normal timer jitter first.
              cancelScheduled(card, CardEffectEndReason.SYSTEM_CANCELLED);
            }
          } else if (!current.isBefore(card.endsAt())) {
            doFinishEffect(card);
          } else {
            afterCommit(() -> scheduleFinish(card));
          }
        });
  }

  private boolean isPlaying(Room room, PerformanceSnapShot performance, Long performanceId) {
    return room != null
        && room.getMode() == RoomMode.BATTLE
        && room.getStatus() == RoomStatus.PLAYING
        && performance != null
        && performance.status() == PerformanceStatus.PLAYING
        && performance.performanceId().equals(performanceId);
  }

  private ScheduledCard taskKey(RoomCardSnapshot card) {
    return new ScheduledCard(
        card.roomId(),
        card.performanceId(),
        card.sourceParticipantId(),
        card.approvedAt().toInstant(),
        card.activateAt().toInstant(),
        card.status(),
        (card.status() == RoomCardStatus.PENDING ? card.activateAt() : card.endsAt()).toInstant());
  }

  private void scheduleStart(RoomCardSnapshot card) {
    schedule(card, () -> executeWithRoomLock(card.roomId(), room -> doStartEffect(card, room)));
  }

  private void scheduleFinish(RoomCardSnapshot card) {
    schedule(card, () -> executeWithRoomLock(card.roomId(), room -> doFinishEffect(card)));
  }

  private void schedule(RoomCardSnapshot card, Runnable action) {
    ScheduledCard key = taskKey(card);
    if (!scheduledTasks.add(key)) return;
    try {
      taskScheduler.schedule(
          () -> {
            try {
              action.run();
            } finally {
              scheduledTasks.remove(key);
            }
          },
          key.deadline());
    } catch (RuntimeException error) {
      scheduledTasks.remove(key);
      throw error;
    }
  }

  private void clearTasks(RoomCardSnapshot card) {
    scheduledTasks.removeIf(
        task ->
            task.roomId().equals(card.roomId())
                && task.approvedAt().equals(card.approvedAt().toInstant()));
  }

  private void doStartEffect(RoomCardSnapshot expected, Room room) {
    RoomCardSnapshot pending = cardStateStore.findRoomCard(expected.roomId()).orElse(null);
    if (pending == null
        || pending.status() != RoomCardStatus.PENDING
        || !pending.sameExecution(expected)) return;
    OffsetDateTime current = now();
    if (current.isBefore(pending.activateAt())) return;
    if (current.isAfter(pending.activateAt().plus(recoveryProperties.getStartExecutionGrace()))) {
      cancelScheduled(pending, CardEffectEndReason.SYSTEM_CANCELLED);
      return;
    }
    CardAssignmentSnapshot assignment =
        cardStateStore
            .findAssignment(
                pending.roomId(), pending.performanceId(), pending.sourceParticipantId())
            .orElse(null);
    PerformanceSnapShot performance =
        performanceStore.findActiveByRoomId(pending.roomId()).orElse(null);
    RoomParticipant owner =
        participantRepository.findById(pending.sourceParticipantId()).orElse(null);
    if (!isPlaying(room, performance, pending.performanceId())
        || assignment == null
        || assignment.status() != CardAssignmentStatus.ASSIGNED
        || !assignment.cardId().equals(pending.cardId())
        || owner == null
        || !owner.isOnline()
        || !owner.getRoom().getId().equals(pending.roomId())
        || performance.remainingPlaybackMs(current) <= 0) {
      cancelScheduled(pending, CardEffectEndReason.SYSTEM_CANCELLED);
      return;
    }
    Long target =
        assignment.targetType()
                == com.ssafy.ssasukae.domain.card.websocket.type.CardEffectTargetType.CARD_OWNER
            ? owner.getId()
            : performance.performerParticipantId();
    if (!java.util.Objects.equals(target, pending.targetParticipantId())) {
      cancelScheduled(pending, CardEffectEndReason.SYSTEM_CANCELLED);
      return;
    }
    OffsetDateTime startedAt = now();
    RoomCardSnapshot active =
        pending.active(startedAt, startedAt.plusSeconds(pending.durationSeconds()), null);
    if (!cardStateStore.activateRoomCard(pending, active, assignment, assignment.used(startedAt)))
      return;
    afterCommit(
        () -> {
          scheduleFinish(active);
          eventPublisher.publishToRoom(
              active.roomId(),
              CardWebSocketEventType.CARD_EFFECT_STARTED,
              new CardEffectStartedPayload(
                  active.performanceId(),
                  active.sourceParticipantId(),
                  active.targetParticipantId(),
                  active.targetType(),
                  active.cardId(),
                  active.cardCode(),
                  active.cardName(),
                  active.description(),
                  active.effectType(),
                  active.effectValue(),
                  active.durationSeconds(),
                  active.startedAt(),
                  active.endsAt()));
        });
  }

  private void doFinishEffect(RoomCardSnapshot expected) {
    RoomCardSnapshot current = cardStateStore.findRoomCard(expected.roomId()).orElse(null);
    if (current == null
        || current.status() != RoomCardStatus.ACTIVE
        || !current.sameExecution(expected)
        || !current.endsAt().toInstant().equals(expected.endsAt().toInstant())
        || now().isBefore(current.endsAt())) return;
    if (cardStateStore.removeRoomCard(current)) {
      clearTasks(current);
      publishEnded(current, CardEffectEndReason.DURATION_EXPIRED, now());
    }
  }

  private void cancelScheduled(RoomCardSnapshot card, CardEffectEndReason reason) {
    if (card != null
        && card.status() == RoomCardStatus.PENDING
        && cardStateStore.removeRoomCard(card)) {
      clearTasks(card);
      publishCancelled(card, reason);
    }
  }

  private CardAssignmentSnapshot toAssignment(
      Long roomId,
      PerformanceSnapShot performance,
      RoomParticipant participant,
      Card card,
      OffsetDateTime assignedAt) {
    return new CardAssignmentSnapshot(
        roomId,
        performance.performanceId(),
        participant.getId(),
        participant.getUser().getId(),
        card.getId(),
        card.getCode(),
        card.getName(),
        card.getDescription(),
        card.getEffectType(),
        card.getTargetType(),
        card.getEffectValue(),
        card.getDurationSeconds(),
        card.getTier(),
        CardAssignmentStatus.ASSIGNED,
        assignedAt,
        null);
  }

  private void publishAssigned(CardAssignmentSnapshot assignment) {
    afterCommit(
        () ->
            eventPublisher.publishToUser(
                assignment.userId(),
                assignment.roomId(),
                CardWebSocketEventType.CARD_ASSIGNED,
                new CardAssignedPayload(
                    assignment.performanceId(),
                    assignment.participantId(),
                    assignment.cardId(),
                    assignment.cardCode(),
                    assignment.cardName(),
                    assignment.description(),
                    assignment.effectType(),
                    assignment.targetType(),
                    assignment.effectValue(),
                    assignment.durationSeconds(),
                    assignment.tier())));
  }

  private CardActivationScheduledPayload scheduledPayload(
      RoomCardSnapshot roomCard, OffsetDateTime serverNow) {
    return new CardActivationScheduledPayload(
        roomCard.performanceId(),
        roomCard.sourceParticipantId(),
        serverNow,
        ACTIVATION_COUNTDOWN_SECONDS,
        roomCard.approvedAt(),
        roomCard.activateAt());
  }

  private void publishCancelled(RoomCardSnapshot roomCard, CardEffectEndReason reason) {
    afterCommit(
        () ->
            eventPublisher.publishToRoom(
                roomCard.roomId(),
                CardWebSocketEventType.CARD_ACTIVATION_CANCELLED,
                new CardActivationCancelledPayload(
                    roomCard.performanceId(), roomCard.sourceParticipantId(), reason, now())));
  }

  private void publishEnded(
      RoomCardSnapshot roomCard, CardEffectEndReason reason, OffsetDateTime endedAt) {
    afterCommit(
        () ->
            eventPublisher.publishToRoom(
                roomCard.roomId(),
                CardWebSocketEventType.CARD_EFFECT_ENDED,
                new CardEffectEndedPayload(
                    roomCard.performanceId(),
                    roomCard.sourceParticipantId(),
                    roomCard.targetParticipantId(),
                    roomCard.targetType(),
                    roomCard.cardId(),
                    roomCard.cardCode(),
                    roomCard.cardName(),
                    roomCard.description(),
                    roomCard.effectType(),
                    roomCard.effectValue(),
                    roomCard.previousValue(),
                    roomCard.durationSeconds(),
                    roomCard.startedAt(),
                    endedAt,
                    reason)));
  }

  private void executeWithRoomLock(Long roomId, Consumer<Room> action) {
    if (transactionTemplate == null) {
      action.accept(roomRepository.findByIdForUpdate(roomId).orElse(null));
      return;
    }
    transactionTemplate.executeWithoutResult(
        ignored -> action.accept(roomRepository.findByIdForUpdate(roomId).orElse(null)));
  }

  private void afterCommit(Runnable action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      action.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            action.run();
          }
        });
  }

  private void validateAssignment(CardAssignmentSnapshot assignment) {
    if (assignment.status() == CardAssignmentStatus.USED) {
      throw business(WebSocketErrorCode.CARD_ALREADY_USED);
    }
    if (assignment.status() != CardAssignmentStatus.ASSIGNED) {
      throw business(WebSocketErrorCode.INVALID_CARD_STATE);
    }
  }

  private void validateNoRoomCard(Long roomId) {
    cardStateStore
        .findRoomCard(roomId)
        .ifPresent(
            roomCard -> {
              if (roomCard.status() == RoomCardStatus.PENDING) {
                throw business(WebSocketErrorCode.CARD_ACTIVATION_PENDING);
              }
              throw business(WebSocketErrorCode.CARD_EFFECT_ALREADY_ACTIVE);
            });
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock).atZoneSameInstant(SEOUL_ZONE_ID).toOffsetDateTime();
  }

  private void validatePositive(Long value, String fieldName) {
    if (value == null || value <= 0) {
      throw new WebSocketBusinessException(
          WebSocketErrorCode.INVALID_REQUEST, fieldName + "은 양의 정수여야 합니다.");
    }
  }

  private WebSocketBusinessException business(WebSocketErrorCode code) {
    return new WebSocketBusinessException(code);
  }
}
