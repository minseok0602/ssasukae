package com.ssafy.ssasukae.domain.performance.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ssafy.ssasukae.domain.card.service.CardService;
import com.ssafy.ssasukae.domain.performance.recovery.*;
import com.ssafy.ssasukae.domain.performance.redis.performance.*;
import com.ssafy.ssasukae.domain.performance.type.*;
import com.ssafy.ssasukae.domain.performance.websocket.*;
import com.ssafy.ssasukae.domain.performance.websocket.request.PerformanceConnectionRecoveryRequest;
import com.ssafy.ssasukae.domain.room.entity.*;
import com.ssafy.ssasukae.domain.room.repository.*;
import com.ssafy.ssasukae.domain.room.type.*;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.*;

class PerformanceSocketRecoveryServiceTest {
  final OffsetDateTime now = OffsetDateTime.parse("2026-11-23T00:00:30Z");
  RoomRepository rooms;
  RoomParticipantRepository participants;
  PerformanceStore performances;
  PerformanceRecoveryDeadlineStore deadlines;
  PerformanceWebSocketEventPublisher events;
  PerformanceCancellationProcessor cancellations;
  PerformanceConnectionRecoveryService connections;
  PerformanceSocketRecoveryService service;
  Room room;
  RoomParticipant owner;
  PerformanceSnapShot current;

  @BeforeEach
  void setup() {
    rooms = mock(RoomRepository.class);
    participants = mock(RoomParticipantRepository.class);
    performances = mock(PerformanceStore.class);
    deadlines = mock(PerformanceRecoveryDeadlineStore.class);
    events = mock(PerformanceWebSocketEventPublisher.class);
    cancellations = mock(PerformanceCancellationProcessor.class);
    connections = mock(PerformanceConnectionRecoveryService.class);
    room = mock(Room.class);
    owner = mock(RoomParticipant.class);
    when(room.getId()).thenReturn(10L);
    when(room.getStatus()).thenReturn(RoomStatus.PLAYING);
    when(rooms.findByIdForUpdate(10L)).thenReturn(Optional.of(room));
    when(participants.findByRoomIdAndUserId(10L, 1L)).thenReturn(Optional.of(owner));
    when(owner.getId()).thenReturn(100L);
    when(owner.isActive()).thenReturn(true);
    when(owner.isPerformer()).thenReturn(true);
    current =
        PerformanceSnapShot.prepare(30L, 10L, 100L, 1L, 20L, 180000L, now.minusSeconds(30))
            .startPlayback(now.minusSeconds(28));
    when(performances.findActiveByRoomId(10L)).thenAnswer(i -> Optional.ofNullable(current));
    when(performances.replaceWithRecoveryDeadline(any(), any(), any()))
        .thenAnswer(
            i -> {
              current = i.getArgument(1);
              return true;
            });
    service =
        new PerformanceSocketRecoveryService(
            rooms,
            participants,
            performances,
            deadlines,
            new PerformanceRecoveryProperties(),
            new PerformanceTransactionSupport(performances, deadlines),
            connections,
            cancellations,
            mock(CardService.class),
            events,
            Clock.fixed(now.toInstant(), ZoneOffset.UTC));
  }

  PerformanceConnectionRecoveryRequest request(String id, int seconds, long position) {
    return new PerformanceConnectionRecoveryRequest(id, now.minusSeconds(seconds), position);
  }

  @Test
  void restartReconcilesPlayingWithActualAudioPosition() {
    var originalStart = current.startedAt();
    service.reconcile(1L, 10L, 30L, request("freeze", 3, 22000));
    assertThat(current.status()).isEqualTo(PerformanceStatus.SUSPENDED);
    assertThat(current.playbackPositionMs()).isEqualTo(22000L);
    assertThat(current.startedAt()).isEqualTo(originalStart);
    assertThat(current.playbackPositionAt(now.plusMinutes(1))).isEqualTo(22000L);
    verify(performances).replaceWithRecoveryDeadline(any(), any(), isNull());
    verify(events).publish(eq(10L), eq(PerformanceWebSocketEventType.PERFORMANCE_SUSPENDED), any());
  }

  @Test
  void retriedReconciliationDoesNotSuspendAnAlreadyResumedPerformance() {
    var req = request("freeze", 3, 22000);
    service.reconcile(1L, 10L, 30L, req);
    current = current.resumeAfterPerformerReconnect(now.plusSeconds(1));
    service.reconcile(1L, 10L, 30L, req);
    assertThat(current.status()).isEqualTo(PerformanceStatus.PLAYING);
    verify(performances, times(1)).replaceWithRecoveryDeadline(any(), any(), any());
  }

  @Test
  void reconnectAfterGraceCancelsInsteadOfStartingLate() {
    service.reconcile(1L, 10L, 30L, request("late", 16, 10000));
    verify(cancellations).cancel(room, current, PerformanceCancelReason.PERFORMER_DISCONNECTED);
    verify(performances, never()).replaceWithRecoveryDeadline(any(), any(), any());
  }

  @Test
  void spectatorCannotSubmitAnotherPerformersPosition() {
    when(owner.isPerformer()).thenReturn(false);
    assertThatThrownBy(() -> service.reconcile(1L, 10L, 30L, request("spectator", 1, 20000)))
        .isInstanceOf(RuntimeException.class);
    verify(performances, never()).replaceWithRecoveryDeadline(any(), any(), any());
    verifyNoInteractions(events);
  }

  @Test
  void oldPerformanceRequestDoesNotChangeTheCurrentPerformance() {
    service.reconcile(1L, 10L, 29L, request("old", 1, 20000));
    verify(performances, never()).replaceWithRecoveryDeadline(any(), any(), any());
    verifyNoInteractions(events);
  }

  @Test
  void socketDisconnectRegistersGraceAndSpectatorDisconnectDoesNot() {
    service.socketDisconnected(room, owner, "socket");
    verify(performances)
        .replaceWithRecoveryDeadline(any(), any(), eq(now.plusSeconds(15).toInstant()));
    assertThat(current.status()).isEqualTo(PerformanceStatus.SUSPENDED);
    clearInvocations(deadlines, performances, events);
    when(owner.isPerformer()).thenReturn(false);
    service.socketDisconnected(room, owner, "spectator");
    verifyNoInteractions(deadlines, events);
    verify(performances, never()).replaceWithRecoveryDeadline(any(), any(), any());
  }

  @Test
  void connectionWithinGraceCancelsExpirationButDoesNotResumeBeforeReady() {
    current = current.reconcileInterruption("socket:old", now.minusSeconds(10), 18000);
    when(deadlines.findDeadline(30L)).thenReturn(Optional.of(now.plusSeconds(5).toInstant()));
    service.socketConnected(room, owner);
    verify(deadlines).delete(30L);
    verifyNoInteractions(cancellations, connections);
    assertThat(current.status()).isEqualTo(PerformanceStatus.SUSPENDED);
  }

  @Test
  void connectionAfterDeadlineCancelsEvenIfExpiryWorkerHasNotRunYet() {
    current = current.reconcileInterruption("socket:old", now.minusSeconds(16), 12000);
    when(deadlines.findDeadline(30L)).thenReturn(Optional.of(now.minusSeconds(1).toInstant()));
    service.socketConnected(room, owner);
    verify(cancellations).cancel(room, current, PerformanceCancelReason.PERFORMER_DISCONNECTED);
  }

  @Test
  void actualPositionRemainsTheAnchorAcrossResumeAndAnotherDisconnect() {
    current = current.reconcileInterruption("first", now.minusSeconds(2), 35000);
    var start = current.startedAt();
    current = current.resumeAfterPerformerReconnect(now.plusSeconds(1));
    assertThat(current.playbackPositionAt(now.plusSeconds(6))).isEqualTo(40000L);
    current = current.suspendForPerformerDisconnect(now.plusSeconds(6));
    assertThat(current.playbackPositionAt(now.plusMinutes(2))).isEqualTo(40000L);
    current = current.resumeAfterPerformerReconnect(now.plusSeconds(10));
    assertThat(current.playbackPositionAt(now.plusSeconds(12))).isEqualTo(42000L);
    assertThat(current.startedAt()).isEqualTo(start);
  }

  @Test
  void staleInterruptionFromBeforeLastResumeCannotRewindPerformance() {
    current =
        current
            .reconcileInterruption("first", now.minusSeconds(3), 22000)
            .resumeAfterPerformerReconnect(now.minusSeconds(1));
    assertThatThrownBy(() -> service.reconcile(1L, 10L, 30L, request("stale", 2, 10000)))
        .isInstanceOf(RuntimeException.class);
    verify(performances, never()).replaceWithRecoveryDeadline(any(), any(), any());
  }

  @Test
  void disconnectDuringTheScheduledResumeWindowFreezesAtTheSameAudioPosition() {
    current =
        current
            .reconcileInterruption("first", now.minusSeconds(1), 22000)
            .resumeAfterPerformerReconnect(now.plusNanos(500_000_000));
    service.socketDisconnected(room, owner, "new-socket");
    assertThat(current.status()).isEqualTo(PerformanceStatus.SUSPENDED);
    assertThat(current.playbackPositionMs()).isEqualTo(22000L);
  }
}
