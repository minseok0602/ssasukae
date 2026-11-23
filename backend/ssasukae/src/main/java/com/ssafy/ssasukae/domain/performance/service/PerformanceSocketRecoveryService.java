package com.ssafy.ssasukae.domain.performance.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.ssasukae.domain.card.service.CardService;
import com.ssafy.ssasukae.domain.card.websocket.type.CardEffectEndReason;
import com.ssafy.ssasukae.domain.performance.recovery.*;
import com.ssafy.ssasukae.domain.performance.redis.performance.*;
import com.ssafy.ssasukae.domain.performance.type.*;
import com.ssafy.ssasukae.domain.performance.websocket.*;
import com.ssafy.ssasukae.domain.performance.websocket.payload.PerformanceSuspendedPayload;
import com.ssafy.ssasukae.domain.performance.websocket.request.PerformanceConnectionRecoveryRequest;
import com.ssafy.ssasukae.domain.room.entity.*;
import com.ssafy.ssasukae.domain.room.repository.*;
import com.ssafy.ssasukae.domain.room.type.RoomStatus;
import com.ssafy.ssasukae.global.exception.websocket.*;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PerformanceSocketRecoveryService {
  private final RoomRepository rooms;
  private final RoomParticipantRepository participants;
  private final PerformanceStore performances;
  private final PerformanceRecoveryDeadlineStore deadlines;
  private final PerformanceRecoveryProperties properties;
  private final PerformanceTransactionSupport transactions;
  private final PerformanceConnectionRecoveryService connections;
  private final PerformanceCancellationProcessor cancellations;
  private final CardService cards;
  private final PerformanceWebSocketEventPublisher events;
  private final Clock clock;

  @Transactional
  public void reconcile(
      Long userId, Long roomId, Long performanceId, PerformanceConnectionRecoveryRequest request) {
    Room room =
        rooms
            .findByIdForUpdate(roomId)
            .orElseThrow(() -> error(WebSocketErrorCode.RESOURCE_NOT_FOUND));
    RoomParticipant owner =
        participants
            .findByRoomIdAndUserId(roomId, userId)
            .orElseThrow(() -> error(WebSocketErrorCode.ROOM_ACCESS_DENIED));
    PerformanceSnapShot current = performances.findActiveByRoomId(roomId).orElse(null);
    // A terminal/new performance is authoritative; the client then reloads the room snapshot.
    if (current == null || !current.performanceId().equals(performanceId)) return;
    if (!owner.isActive()
        || !owner.isPerformer()
        || !current.performerUserId().equals(userId)
        || !current.isPerformedBy(owner.getId()))
      throw error(WebSocketErrorCode.PERFORMER_PERMISSION_REQUIRED);
    if (room.getStatus() != RoomStatus.PLAYING
        || (current.status() != PerformanceStatus.PLAYING
            && current.status() != PerformanceStatus.PREPARING
            && current.status() != PerformanceStatus.SUSPENDED)) return;
    if (Objects.equals(current.lastInterruptionId(), request.interruptionId())) return;
    OffsetDateTime now = OffsetDateTime.now(clock);
    OffsetDateTime at = request.interruptedAt().isAfter(now) ? now : request.interruptedAt();
    if (at.isBefore(current.preparedAt())
        || request.playbackPositionMs() < 0
        || (current.songDurationMs() > 0
            && request.playbackPositionMs() > current.songDurationMs()))
      throw error(WebSocketErrorCode.INVALID_REQUEST);
    if ((current.status() != PerformanceStatus.SUSPENDED
            && !now.isBefore(at.plus(properties.getPerformerDisconnectGrace())))
        || deadlines
            .findDeadline(performanceId)
            .filter(deadline -> !now.toInstant().isBefore(deadline))
            .isPresent()) {
      cancellations.cancel(room, current, PerformanceCancelReason.PERFORMER_DISCONNECTED);
      transactions.deleteRecoveryDeadline(performanceId);
      return;
    }
    PerformanceSnapShot suspended;
    try {
      suspended =
          current.reconcileInterruption(request.interruptionId(), at, request.playbackPositionMs());
    } catch (IllegalStateException invalid) {
      throw error(WebSocketErrorCode.INVALID_PERFORMANCE_STATE);
    }
    saveSuspended(room, current, suspended, false);
  }

  public void socketConnected(Room room, RoomParticipant owner) {
    if (!owner.isPerformer()) return;
    PerformanceSnapShot current = performances.findActiveByRoomId(room.getId()).orElse(null);
    if (current == null
        || !current.isPerformedBy(owner.getId())
        || current.status() != PerformanceStatus.SUSPENDED
        || current.lastInterruptionId() == null) return;
    var deadline = deadlines.findDeadline(current.performanceId());
    if (deadline.filter(at -> !clock.instant().isBefore(at)).isPresent()) {
      cancellations.cancel(room, current, PerformanceCancelReason.PERFORMER_DISCONNECTED);
    }
    deadlines.delete(current.performanceId());
  }

  /** Caller owns the room lock; a lost spectator socket never reaches this method. */
  public void socketDisconnected(Room room, RoomParticipant owner, String sessionId) {
    if (!owner.isPerformer() || room.getStatus() != RoomStatus.PLAYING) return;
    PerformanceSnapShot current = performances.findActiveByRoomId(room.getId()).orElse(null);
    if (current == null
        || !current.isPerformedBy(owner.getId())
        || (current.status() != PerformanceStatus.PLAYING
            && current.status() != PerformanceStatus.PREPARING
            && current.status() != PerformanceStatus.SUSPENDED)) return;
    OffsetDateTime now = OffsetDateTime.now(clock);
    saveSuspended(
        room,
        current,
        current.reconcileInterruption("socket:" + sessionId, now, current.playbackPositionAt(now)),
        true);
  }

  private void saveSuspended(
      Room room, PerformanceSnapShot current, PerformanceSnapShot suspended, boolean disconnected) {
    cards.suspendForPerformance(current, CardEffectEndReason.PERFORMER_DISCONNECTED);
    // Snapshot and reconnect deadline must survive a crash together.
    transactions.saveWithRecoveryDeadline(
        current,
        suspended,
        disconnected ? clock.instant().plus(properties.getPerformerDisconnectGrace()) : null);
    transactions.afterCommit(
        () ->
            events.publish(
                room.getId(),
                PerformanceWebSocketEventType.PERFORMANCE_SUSPENDED,
                new PerformanceSuspendedPayload(
                    suspended.performanceId(),
                    suspended.performerParticipantId(),
                    suspended.suspendedFromStatus(),
                    suspended.status(),
                    suspended.suspendedAt(),
                    suspended.playbackPositionMs())));
  }

  @Transactional
  public void resume(Long userId, Long roomId, Long performanceId) {
    Room room =
        rooms
            .findByIdForUpdate(roomId)
            .orElseThrow(() -> error(WebSocketErrorCode.RESOURCE_NOT_FOUND));
    PerformanceSnapShot current = performances.findActiveByRoomId(roomId).orElse(null);
    if (current != null
        && current.performanceId().equals(performanceId)
        && current.performerUserId().equals(userId)
        && current.status() == PerformanceStatus.SUSPENDED
        && current.lastInterruptionId() != null
        && deadlines
            .findDeadline(performanceId)
            .filter(deadline -> !clock.instant().isBefore(deadline))
            .isPresent()) {
      cancellations.cancel(room, current, PerformanceCancelReason.PERFORMER_DISCONNECTED);
      transactions.deleteRecoveryDeadline(performanceId);
      return;
    }
    connections.resumeAfterPerformerReady(userId, roomId, performanceId);
    transactions.deleteRecoveryDeadline(performanceId);
  }

  private WebSocketBusinessException error(WebSocketErrorCode code) {
    return new WebSocketBusinessException(code);
  }
}
