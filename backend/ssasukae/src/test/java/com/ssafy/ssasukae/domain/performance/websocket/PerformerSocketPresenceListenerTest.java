package com.ssafy.ssasukae.domain.performance.websocket;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.ssafy.ssasukae.domain.performance.service.PerformanceSocketRecoveryService;
import com.ssafy.ssasukae.domain.room.entity.*;
import com.ssafy.ssasukae.domain.room.repository.*;
import com.ssafy.ssasukae.domain.room.type.*;

class PerformerSocketPresenceListenerTest {
  @Test
  void lateCloseFromOldSocketDoesNotSuspendNewConnection() {
    check(0L, false);
  }

  @Test
  void currentSocketCloseInvokesRecovery() {
    check(1L, true);
  }

  @SuppressWarnings("unchecked")
  private void check(Long removed, boolean expected) {
    var rooms = mock(RoomRepository.class);
    var participants = mock(RoomParticipantRepository.class);
    var redis = mock(StringRedisTemplate.class);
    var recovery = mock(PerformanceSocketRecoveryService.class);
    var room = mock(Room.class);
    var owner = mock(RoomParticipant.class);
    when(owner.getRoom()).thenReturn(room);
    when(room.getId()).thenReturn(10L);
    when(owner.getId()).thenReturn(100L);
    when(owner.isActive()).thenReturn(true);
    when(room.getStatus()).thenReturn(RoomStatus.PLAYING);
    when(participants.findFirstByUserIdAndConnectionStatusInOrderByJoinedAtDescIdDesc(
            eq(1L), anyList()))
        .thenReturn(Optional.of(owner));
    when(rooms.findModeById(10L)).thenReturn(Optional.of(RoomMode.GENERAL));
    when(rooms.findByIdForUpdate(10L)).thenReturn(Optional.of(room));
    when(participants.findByIdForUpdate(100L)).thenReturn(Optional.of(owner));
    when(redis.execute(any(DefaultRedisScript.class), anyList(), eq("old"))).thenReturn(removed);
    var listener = new PerformerSocketPresenceListener(rooms, participants, redis, recovery);
    var event =
        new SessionDisconnectEvent(
            this,
            MessageBuilder.withPayload(new byte[0]).build(),
            "old",
            CloseStatus.NORMAL,
            () -> "1");
    listener.disconnected(event);
    if (expected) verify(recovery).socketDisconnected(room, owner, "old");
    else verifyNoInteractions(recovery);
  }
}
