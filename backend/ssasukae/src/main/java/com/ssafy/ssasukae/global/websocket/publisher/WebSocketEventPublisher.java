package com.ssafy.ssasukae.global.websocket.publisher;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.ssafy.ssasukae.global.websocket.destination.WebSocketDestinations;
import com.ssafy.ssasukae.global.websocket.message.WebSocketEvent;
import com.ssafy.ssasukae.global.websocket.relay.RedisWebSocketEventRelay;

/** 서버에서 만든 WebSocketEvent를 실제 STOMP Destination으로 전송하는 공통 발행 컴포넌트 */
@Component
public class WebSocketEventPublisher {

  private static final String USER_QUEUE_PREFIX = "/queue/";

  private final SimpMessagingTemplate messagingTemplate;
  private final RedisWebSocketEventRelay relay;

  @Autowired
  public WebSocketEventPublisher(
      SimpMessagingTemplate messagingTemplate, RedisWebSocketEventRelay relay) {
    this.messagingTemplate = Objects.requireNonNull(messagingTemplate, "messagingTemplate은 필수입니다.");
    this.relay = Objects.requireNonNull(relay, "relay는 필수입니다.");
  }

  /** Compatibility constructor for isolated local-broker tests. Production uses the relay. */
  public WebSocketEventPublisher(SimpMessagingTemplate messagingTemplate) {
    this.messagingTemplate = Objects.requireNonNull(messagingTemplate, "messagingTemplate은 필수입니다.");
    this.relay = null;
  }

  // 특정 방을 구독하는 클라이언트 모두에게 이벤트를 보내는 메서드
  public void publishToRoom(Long roomId, WebSocketEvent<?> event) {
    Objects.requireNonNull(event, "event는 필수입니다.");

    String destination = WebSocketDestinations.roomTopic(roomId);
    if (relay == null) messagingTemplate.convertAndSend(destination, event);
    else relay.publish("", destination, event);
  }

  // 특정 사용자에게 이벤트를 보내는 메서드
  public void publishToUser(String userId, String destination, WebSocketEvent<?> event) {
    validateUserId(userId);
    validateUserQueue(destination);
    Objects.requireNonNull(event, "event는 필수입니다.");

    if (relay == null) messagingTemplate.convertAndSendToUser(userId, destination, event);
    else relay.publish(userId, destination, event);
  }

  private void validateUserId(String userId) {
    if (!StringUtils.hasText(userId)) {
      throw new IllegalArgumentException("userId는 비어 있을 수 없습니다.");
    }
  }

  private void validateUserQueue(String destination) {
    if (!StringUtils.hasText(destination) || !destination.startsWith(USER_QUEUE_PREFIX)) {
      throw new IllegalArgumentException("사용자 Destination은 /queue/로 시작해야 합니다.");
    }
  }
}
