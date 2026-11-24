package com.ssafy.ssasukae.global.websocket.relay;

import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;

import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import com.ssafy.ssasukae.global.websocket.message.WebSocketEvent;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * Each node reads every stream record and delivers it only to its local broker. A shared consumer
 * group would distribute records to nodes and lose room/user fan-out. The cursor survives transient
 * Redis disconnections within this process; new processes start at the tail because their newly
 * connected clients first load a snapshot.
 */
@Slf4j
public class RedisWebSocketEventRelay {
  private static final DefaultRedisScript<String> APPEND =
      new DefaultRedisScript<>(
          "return redis.call('XADD',KEYS[1],'MAXLEN','~',ARGV[1],'*','message',ARGV[2])",
          String.class);
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;
  private final SimpMessagingTemplate local;
  private final WebSocketRelayProperties properties;
  private String lastReadId = "0-0";
  private volatile boolean running = true;

  public record Envelope(String userId, String destination, String eventJson) {}

  public RedisWebSocketEventRelay(
      StringRedisTemplate redis,
      ObjectMapper mapper,
      SimpMessagingTemplate local,
      WebSocketRelayProperties properties) {
    this.redis = redis;
    this.mapper = mapper;
    this.local = local;
    this.properties = properties;
  }

  @PostConstruct
  public void initialize() {
    if (!properties.isEnabled()) return;
    // Fail startup rather than serve authenticated sessions without a working event transport.
    List<MapRecord<String, Object, Object>> tail =
        redis
            .opsForStream()
            .reverseRange(properties.getStreamKey(), Range.unbounded(), Limit.limit().count(1));
    if (tail != null && !tail.isEmpty()) lastReadId = tail.get(0).getId().getValue();
  }

  public void publish(String userId, String destination, WebSocketEvent<?> event) {
    Envelope envelope = new Envelope(userId, destination, mapper.writeValueAsString(event));
    validate(envelope);
    if (!properties.isEnabled()) {
      deliver(envelope);
      return;
    }
    redis.execute(
        APPEND,
        List.of(properties.getStreamKey()),
        Long.toString(properties.getMaxLength()),
        mapper.writeValueAsString(envelope));
  }

  @Scheduled(
      fixedDelayString = "${websocket.relay.poll-delay:50ms}",
      scheduler = "webSocketRelayScheduler")
  public synchronized void poll() {
    if (!running || !properties.isEnabled()) return;
    try {
      List<MapRecord<String, Object, Object>> records =
          redis
              .opsForStream()
              .read(
                  StreamReadOptions.empty().count(properties.getBatchSize()),
                  StreamOffset.create(properties.getStreamKey(), ReadOffset.from(lastReadId)));
      if (records == null) return;
      for (MapRecord<String, Object, Object> record : records) {
        Envelope envelope;
        Map<?, ?> event;
        try {
          Object value = record.getValue().get("message");
          envelope = mapper.readValue(value.toString(), Envelope.class);
          validate(envelope);
          event = mapper.readValue(envelope.eventJson(), Map.class);
          if (event == null || !event.containsKey("eventType"))
            throw new IllegalArgumentException("Invalid event payload");
        } catch (RuntimeException malformed) {
          log.error("잘못된 WebSocket 릴레이 메시지. streamId={}", record.getId(), malformed);
          lastReadId = record.getId().getValue();
          continue;
        }
        // Advance only after local acceptance. A Redis/read or local delivery failure is retried.
        deliver(envelope, event);
        lastReadId = record.getId().getValue();
      }
    } catch (RuntimeException error) {
      if (running) log.error("WebSocket 이벤트 릴레이 실패. 다음 조회에서 재시도합니다.", error);
    }
  }

  private void validate(Envelope envelope) {
    if (envelope.userId() == null
        || envelope.destination() == null
        || envelope.eventJson() == null
        || (envelope.userId().isEmpty()
            ? !envelope.destination().startsWith("/topic/rooms/")
            : !envelope.destination().startsWith("/queue/")))
      throw new IllegalArgumentException("Invalid event destination");
  }

  private void deliver(Envelope envelope) {
    deliver(envelope, mapper.readValue(envelope.eventJson(), Map.class));
  }

  private void deliver(Envelope envelope, Map<?, ?> event) {
    if (envelope.userId().isEmpty()) local.convertAndSend(envelope.destination(), (Object) event);
    else local.convertAndSendToUser(envelope.userId(), envelope.destination(), (Object) event);
  }

  @EventListener(ContextClosedEvent.class)
  public void stop() {
    running = false;
  }
}
