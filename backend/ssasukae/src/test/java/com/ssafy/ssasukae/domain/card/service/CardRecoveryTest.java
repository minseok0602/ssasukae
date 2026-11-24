package com.ssafy.ssasukae.domain.card.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.scheduling.TaskScheduler;

import com.ssafy.ssasukae.domain.card.redis.*;
import com.ssafy.ssasukae.domain.card.repository.CardRepository;
import com.ssafy.ssasukae.domain.card.type.CardTier;
import com.ssafy.ssasukae.domain.card.websocket.*;
import com.ssafy.ssasukae.domain.card.websocket.type.*;
import com.ssafy.ssasukae.domain.performance.redis.performance.*;
import com.ssafy.ssasukae.domain.room.entity.*;
import com.ssafy.ssasukae.domain.room.repository.*;
import com.ssafy.ssasukae.domain.room.type.*;

class CardRecoveryTest {
  private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
  private final Long roomId = 10L, performanceId = 100L, sourceId = 15L;
  private Clock clock;
  private Instant instant;
  private Room room;
  private RoomParticipant owner;
  private CardStateStore store;
  private CardWebSocketEventPublisher events;
  private PerformanceStore performances;
  private CardService service;
  private RoomCardSnapshot current;
  private CardAssignmentSnapshot assignment;
  private final List<Job> jobs = new ArrayList<>();

  private record Job(Runnable action, Instant deadline) {}

  @BeforeEach
  void setup() {
    instant = NOW;
    clock = mock(Clock.class);
    when(clock.instant()).thenAnswer(i -> instant);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    RoomRepository rooms = mock(RoomRepository.class);
    room = mock(Room.class);
    when(rooms.findByIdForUpdate(roomId)).thenReturn(Optional.of(room));
    when(room.getId()).thenReturn(roomId);
    when(room.getMode()).thenReturn(RoomMode.BATTLE);
    when(room.getStatus()).thenReturn(RoomStatus.PLAYING);
    RoomParticipantRepository participants = mock(RoomParticipantRepository.class);
    owner = mock(RoomParticipant.class);
    when(participants.findById(sourceId)).thenReturn(Optional.of(owner));
    when(owner.getId()).thenReturn(sourceId);
    when(owner.getRoom()).thenReturn(room);
    when(owner.isOnline()).thenReturn(true);
    performances = mock(PerformanceStore.class);
    OffsetDateTime prepared = time(-61);
    when(performances.findActiveByRoomId(roomId))
        .thenReturn(
            Optional.of(
                PerformanceSnapShot.prepare(performanceId, roomId, 11L, 3L, 99L, 210_000L, prepared)
                    .startPlayback(time(-60))));
    store = mock(CardStateStore.class);
    events = mock(CardWebSocketEventPublisher.class);
    assignment =
        new CardAssignmentSnapshot(
            roomId,
            performanceId,
            sourceId,
            7L,
            3L,
            "TEST",
            "test",
            "fixture",
            CardEffectType.MR_KEY_CHANGE,
            CardEffectTargetType.PERFORMER,
            -3,
            15,
            CardTier.G,
            CardAssignmentStatus.ASSIGNED,
            time(-10),
            null);
    when(store.findRoomCard(roomId)).thenAnswer(i -> Optional.ofNullable(current));
    when(store.findAssignment(roomId, performanceId, sourceId))
        .thenAnswer(i -> Optional.of(assignment));
    when(store.removeRoomCard(any())).thenAnswer(i -> remove(i.getArgument(0)));
    when(store.activateRoomCard(any(), any(), any(), any()))
        .thenAnswer(
            i -> {
              RoomCardSnapshot expected = i.getArgument(0);
              if (current == null
                  || current.status() != RoomCardStatus.PENDING
                  || !current.sameExecution(expected)) return false;
              current = i.getArgument(1);
              assignment = i.getArgument(3);
              return true;
            });
    TaskScheduler scheduler = mock(TaskScheduler.class);
    when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
        .thenAnswer(
            i -> {
              jobs.add(new Job(i.getArgument(0), i.getArgument(1)));
              return null;
            });
    service =
        new CardService(
            rooms,
            participants,
            mock(CardRepository.class),
            performances,
            store,
            events,
            scheduler,
            clock);
  }

  private boolean remove(RoomCardSnapshot expected) {
    if (current == null
        || current.status() != expected.status()
        || !current.sameExecution(expected)) return false;
    current = null;
    return true;
  }

  private OffsetDateTime time(long offset) {
    return OffsetDateTime.ofInstant(NOW.plusSeconds(offset), ZoneOffset.UTC);
  }

  private RoomCardSnapshot pending(long approved, long activation) {
    return new RoomCardSnapshot(
        roomId,
        performanceId,
        RoomCardStatus.PENDING,
        sourceId,
        11L,
        3L,
        "TEST",
        "test",
        "fixture",
        CardEffectType.MR_KEY_CHANGE,
        CardEffectTargetType.PERFORMER,
        -3,
        15,
        null,
        time(approved),
        time(activation),
        null,
        null);
  }

  private void event(CardWebSocketEventType type, int count) {
    verify(events, times(count)).publishToRoom(eq(roomId), eq(type), any());
  }

  @Test
  void overduePendingIsCancelledWithoutConsumingAssignment() {
    current = pending(-20, -17);
    service.recoverRoomCard(roomId);
    service.recoverRoomCard(roomId);
    assertThat(current).isNull();
    assertThat(assignment.status()).isEqualTo(CardAssignmentStatus.ASSIGNED);
    assertThat(jobs).isEmpty();
    event(CardWebSocketEventType.CARD_ACTIVATION_CANCELLED, 1);
    event(CardWebSocketEventType.CARD_EFFECT_STARTED, 0);
  }

  @Test
  void futurePendingRecoversExactlyOneStartAndOriginalEnd() {
    current = pending(0, 3);
    service.recoverRoomCard(roomId);
    service.recoverRoomCard(roomId);
    assertThat(jobs).hasSize(1);
    assertThat(jobs.get(0).deadline()).isEqualTo(NOW.plusSeconds(3));
    instant = NOW.plusSeconds(3);
    jobs.get(0).action().run();
    assertThat(current.status()).isEqualTo(RoomCardStatus.ACTIVE);
    assertThat(assignment.status()).isEqualTo(CardAssignmentStatus.USED);
    assertThat(current.endsAt().toInstant()).isEqualTo(NOW.plusSeconds(18));
    instant = NOW.plusSeconds(18);
    jobs.get(1).action().run();
    service.recoverRoomCard(roomId);
    assertThat(current).isNull();
    event(CardWebSocketEventType.CARD_EFFECT_STARTED, 1);
    event(CardWebSocketEventType.CARD_EFFECT_ENDED, 1);
  }

  @Test
  void activeRecoveryKeepsPersistedDeadline() {
    current = pending(-10, -7).active(time(-7), time(8), null);
    service.recoverRoomCard(roomId);
    service.recoverRoomCard(roomId);
    assertThat(jobs).hasSize(1);
    assertThat(jobs.get(0).deadline()).isEqualTo(NOW.plusSeconds(8));
    instant = NOW.plusSeconds(8);
    jobs.get(0).action().run();
    assertThat(current).isNull();
    event(CardWebSocketEventType.CARD_EFFECT_ENDED, 1);
  }

  @Test
  void expiredActiveConvergesImmediatelyAndRepeatedRecoveryIsIdempotent() {
    current = pending(-20, -17).active(time(-17), time(-2), null);
    service.recoverRoomCard(roomId);
    service.recoverRoomCard(roomId);
    assertThat(current).isNull();
    assertThat(jobs).isEmpty();
    event(CardWebSocketEventType.CARD_EFFECT_ENDED, 1);
  }

  @ParameterizedTest
  @EnumSource(RoomCardStatus.class)
  void finishedOrChangedPerformanceNeverRunsOldCards(RoomCardStatus status) {
    current = pending(0, 3);
    if (status == RoomCardStatus.ACTIVE) current = current.active(time(-1), time(14), null);
    when(performances.findActiveByRoomId(roomId)).thenReturn(Optional.empty());
    service.recoverRoomCard(roomId);
    assertThat(current).isNull();
    assertThat(jobs).isEmpty();
    verify(store).deleteCardState(roomId, performanceId);
    event(
        status == RoomCardStatus.PENDING
            ? CardWebSocketEventType.CARD_ACTIVATION_CANCELLED
            : CardWebSocketEventType.CARD_EFFECT_ENDED,
        1);
  }

  @Test
  void participantGoingOfflineCancelsScheduledStart() {
    current = pending(0, 3);
    service.recoverRoomCard(roomId);
    when(owner.isOnline()).thenReturn(false);
    instant = NOW.plusSeconds(3);
    jobs.get(0).action().run();
    assertThat(current).isNull();
    assertThat(assignment.status()).isEqualTo(CardAssignmentStatus.ASSIGNED);
    event(CardWebSocketEventType.CARD_EFFECT_STARTED, 0);
    event(CardWebSocketEventType.CARD_ACTIVATION_CANCELLED, 1);
  }

  @Test
  void oldStartCallbackCannotStartNewCardOfSameOwnerAndPerformance() {
    current = pending(0, 3);
    service.recoverRoomCard(roomId);
    RoomCardSnapshot replacement = pending(1, 4);
    current = replacement;
    instant = NOW.plusSeconds(3);
    jobs.get(0).action().run();
    assertThat(current).isSameAs(replacement);
    event(CardWebSocketEventType.CARD_EFFECT_STARTED, 0);
    service.recoverRoomCard(roomId);
    instant = NOW.plusSeconds(4);
    jobs.get(1).action().run();
    assertThat(current.approvedAt()).isEqualTo(time(1));
    event(CardWebSocketEventType.CARD_EFFECT_STARTED, 1);
  }

  @Test
  void oldEndCallbackCannotEndNewCardOfSameOwnerAndPerformance() {
    current = pending(-10, -7).active(time(-7), time(8), null);
    service.recoverRoomCard(roomId);
    RoomCardSnapshot replacement = pending(1, 4).active(time(4), time(19), null);
    current = replacement;
    instant = NOW.plusSeconds(8);
    jobs.get(0).action().run();
    assertThat(current).isSameAs(replacement);
    event(CardWebSocketEventType.CARD_EFFECT_ENDED, 0);
    service.recoverRoomCard(roomId);
    assertThat(jobs.get(1).deadline()).isEqualTo(NOW.plusSeconds(19));
  }

  @Test
  void smallTimerJitterStartsButLongMisfireCancels() {
    current = pending(0, 3);
    service.recoverRoomCard(roomId);
    instant = NOW.plusSeconds(3).plusMillis(100);
    jobs.get(0).action().run();
    assertThat(current.status()).isEqualTo(RoomCardStatus.ACTIVE);
    current = pending(5, 8);
    instant = NOW.plusSeconds(5);
    service.recoverRoomCard(roomId);
    instant = NOW.plusSeconds(8).plusMillis(501);
    jobs.get(2).action().run();
    assertThat(current).isNull();
    event(CardWebSocketEventType.CARD_ACTIVATION_CANCELLED, 1);
  }

  @Test
  void failedEndIsRetriedWithoutPublishingTwice() {
    current = pending(-20, -17).active(time(-17), time(-2), null);
    doThrow(new IllegalStateException("transient Redis failure"))
        .doAnswer(i -> remove(i.getArgument(0)))
        .when(store)
        .removeRoomCard(any());
    assertThatThrownBy(() -> service.recoverRoomCard(roomId))
        .isInstanceOf(IllegalStateException.class);
    assertThat(current).isNotNull();
    event(CardWebSocketEventType.CARD_EFFECT_ENDED, 0);
    service.recoverRoomCard(roomId);
    service.recoverRoomCard(roomId);
    assertThat(current).isNull();
    event(CardWebSocketEventType.CARD_EFFECT_ENDED, 1);
  }

  @Test
  void aNewNodeDoesNotCancelAnotherNodesTimerInsideJitterWindow() {
    current = pending(0, 3);
    instant = NOW.plusSeconds(3).plusMillis(100);
    service.recoverRoomCard(roomId);
    assertThat(current).isNotNull();
    assertThat(jobs).isEmpty();
    instant = NOW.plusSeconds(3).plusMillis(501);
    service.recoverRoomCard(roomId);
    assertThat(current).isNull();
    event(CardWebSocketEventType.CARD_ACTIVATION_CANCELLED, 1);
  }
}
