package com.ssafy.ssasukae.global.websocket.relay;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.ssafy.ssasukae.global.websocket.message.WebSocketEvent;

import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "REDIS_INTEGRATION_HOST", matches = ".+")
class RedisWebSocketEventRelayIntegrationTest {
  private LettuceConnectionFactory factoryA, factoryB;
  private StringRedisTemplate redis;
  private SimpMessagingTemplate localA, localB;
  private RedisWebSocketEventRelay a, b;
  private WebSocketRelayProperties properties;

  @BeforeEach
  void setup() {
    factoryA = connection();
    factoryB = connection();
    redis = new StringRedisTemplate(factoryA);
    redis.afterPropertiesSet();
    StringRedisTemplate second = new StringRedisTemplate(factoryB);
    second.afterPropertiesSet();
    localA = mock(SimpMessagingTemplate.class);
    localB = mock(SimpMessagingTemplate.class);
    properties = new WebSocketRelayProperties();
    properties.setStreamKey("test:relay:" + UUID.randomUUID());
    a = new RedisWebSocketEventRelay(redis, new ObjectMapper(), localA, properties);
    b = new RedisWebSocketEventRelay(second, new ObjectMapper(), localB, properties);
    a.initialize();
    b.initialize();
  }

  private LettuceConnectionFactory connection() {
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(System.getenv("REDIS_INTEGRATION_HOST"), 6379);
    factory.afterPropertiesSet();
    return factory;
  }

  @AfterEach
  void close() {
    a.stop();
    b.stop();
    redis.delete(properties.getStreamKey());
    factoryA.destroy();
    factoryB.destroy();
  }

  private WebSocketEvent<?> event(int index) {
    return WebSocketEvent.roomEvent(() -> "TEST", 10L, Map.of("index", index));
  }

  @Test
  void roomAndPrivateEventsFanOutToEveryNodeWithoutLocalEchoDuplication() {
    b.publish("", "/topic/rooms/10", event(1));
    b.publish("7", "/queue/cards", event(2));
    a.poll();
    b.poll();
    a.poll();
    b.poll();
    for (SimpMessagingTemplate local : List.of(localA, localB)) {
      verify(local).convertAndSend(eq("/topic/rooms/10"), any(Object.class));
      verify(local).convertAndSendToUser(eq("7"), eq("/queue/cards"), any(Object.class));
      verifyNoMoreInteractions(local);
    }
  }

  @Test
  void liveNodeCatchesUpAfterItsRedisConnectionStops() {
    a.publish("", "/topic/rooms/10", event(1));
    a.poll();
    b.poll();
    factoryB.stop();
    b.poll();
    a.publish("", "/topic/rooms/10", event(2));
    a.publish("", "/topic/rooms/10", event(3));
    a.poll();
    factoryB.start();
    b.poll();
    b.poll();
    ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
    verify(localB, times(3)).convertAndSend(eq("/topic/rooms/10"), payload.capture());
    List<Integer> indexes =
        payload.getAllValues().stream()
            .map(
                value ->
                    ((Number) ((Map<?, ?>) ((Map<?, ?>) value).get("payload")).get("index"))
                        .intValue())
            .toList();
    assertThat(indexes).containsExactly(1, 2, 3);
  }

  @Test
  void newProcessStartsAtTailAndDoesNotReplayEventsBeforeItHadClients() {
    a.publish("", "/topic/rooms/10", event(1));
    RedisWebSocketEventRelay newcomer =
        new RedisWebSocketEventRelay(redis, new ObjectMapper(), localB, properties);
    newcomer.initialize();
    newcomer.poll();
    verifyNoInteractions(localB);
    a.publish("", "/topic/rooms/10", event(2));
    newcomer.poll();
    verify(localB).convertAndSend(eq("/topic/rooms/10"), any(Object.class));
  }

  @Test
  void malformedRecordDoesNotBlockSubsequentEvents() {
    redis.opsForStream().add(properties.getStreamKey(), Map.of("message", "invalid JSON"));
    a.publish("", "/topic/rooms/10", event(1));
    b.poll();
    b.poll();
    verify(localB).convertAndSend(eq("/topic/rooms/10"), any(Object.class));
    verifyNoMoreInteractions(localB);
  }
}
