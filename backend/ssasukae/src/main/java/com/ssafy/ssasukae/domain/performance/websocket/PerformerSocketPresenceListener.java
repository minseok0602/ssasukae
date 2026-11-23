package com.ssafy.ssasukae.domain.performance.websocket;

import java.security.Principal;
import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.messaging.*;

import com.ssafy.ssasukae.domain.performance.service.PerformanceSocketRecoveryService;
import com.ssafy.ssasukae.domain.room.entity.*;
import com.ssafy.ssasukae.domain.room.repository.*;
import com.ssafy.ssasukae.domain.room.type.*;

import lombok.RequiredArgsConstructor;

/** Media presence and application connectivity are distinct in OpenVidu rooms. */
@Component
@RequiredArgsConstructor
public class PerformerSocketPresenceListener {
  private final RoomRepository rooms;
  private final RoomParticipantRepository participants;
  private final StringRedisTemplate redis;
  private final PerformanceSocketRecoveryService recovery;
  private static final DefaultRedisScript<Long> REMOVE =
      new DefaultRedisScript<>(
          "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) end return"
              + " 0",
          Long.class);

  @EventListener
  @Transactional
  public void connected(SessionConnectedEvent event) {
    update(event.getUser(), StompHeaderAccessor.wrap(event.getMessage()).getSessionId(), false);
  }

  @EventListener
  @Transactional
  public void disconnected(SessionDisconnectEvent event) {
    update(event.getUser(), event.getSessionId(), true);
  }

  private void update(Principal user, String sessionId, boolean disconnected) {
    if (user == null || sessionId == null) return;
    Long userId;
    try {
      userId = Long.valueOf(user.getName());
    } catch (NumberFormatException invalid) {
      return;
    }
    RoomParticipant candidate =
        participants
            .findFirstByUserIdAndConnectionStatusInOrderByJoinedAtDescIdDesc(
                userId,
                List.of(
                    ConnectionStatus.PREPARING,
                    ConnectionStatus.CONNECTED,
                    ConnectionStatus.DISCONNECTED))
            .orElse(null);
    if (candidate == null) return;
    // LOW_LATENCY already owns STOMP-driven media presence in its dedicated listener.
    if (rooms
        .findModeById(candidate.getRoom().getId())
        .filter(mode -> mode != RoomMode.LOW_LATENCY)
        .isEmpty()) return;
    Room room = rooms.findByIdForUpdate(candidate.getRoom().getId()).orElse(null);
    if (room == null || room.getStatus() == RoomStatus.TERMINATED) return;
    RoomParticipant current = participants.findByIdForUpdate(candidate.getId()).orElse(null);
    if (current == null || !current.isActive()) return;
    String key = "room:participant:" + current.getId() + ":application-socket";
    if (!disconnected) {
      redis.opsForValue().set(key, sessionId, java.time.Duration.ofHours(24));
      recovery.socketConnected(room, current);
    } else if (Long.valueOf(1).equals(redis.execute(REMOVE, List.of(key), sessionId))) {
      // A late close from the previous socket may not suspend an already reconnected performer.
      recovery.socketDisconnected(room, current, sessionId);
    }
  }
}
