package com.ssafy.ssasukae.domain.card.redis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
@RequiredArgsConstructor
public class RedisCardStateStore implements CardStateStore {

  private static final Duration STATE_TTL = Duration.ofMinutes(30);
  private static final DefaultRedisScript<Long> ACTIVATE =
      new DefaultRedisScript<>(
          "if redis.call('get',KEYS[1]) ~= ARGV[1] then return 0 end if"
              + " redis.call('hget',KEYS[2],ARGV[2]) ~= ARGV[3] then return 0 end"
              + " redis.call('hset',KEYS[2],ARGV[2],ARGV[4]);"
              + " redis.call('pexpire',KEYS[2],ARGV[6]);"
              + " redis.call('psetex',KEYS[1],ARGV[6],ARGV[5]); return 1",
          Long.class);
  private static final DefaultRedisScript<Long> REMOVE =
      new DefaultRedisScript<>(
          "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) end return"
              + " 0",
          Long.class);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  @Override
  public void saveAssignment(CardAssignmentSnapshot assignment) {
    String key = assignmentsKey(assignment.roomId(), assignment.performanceId());
    redisTemplate
        .opsForHash()
        .put(key, assignment.participantId().toString(), serialize(assignment));
    redisTemplate.expire(key, STATE_TTL);
  }

  @Override
  public Optional<CardAssignmentSnapshot> findAssignment(
      Long roomId, Long performanceId, Long participantId) {
    Object json =
        redisTemplate
            .opsForHash()
            .get(assignmentsKey(roomId, performanceId), participantId.toString());
    return Optional.ofNullable(json)
        .map(Object::toString)
        .map(value -> deserialize(value, CardAssignmentSnapshot.class));
  }

  @Override
  public List<CardAssignmentSnapshot> findAssignments(Long roomId, Long performanceId) {
    return redisTemplate.opsForHash().values(assignmentsKey(roomId, performanceId)).stream()
        .map(Object::toString)
        .map(value -> deserialize(value, CardAssignmentSnapshot.class))
        .toList();
  }

  @Override
  public Optional<RoomCardSnapshot> findRoomCard(Long roomId) {
    String json = redisTemplate.opsForValue().get(roomCardKey(roomId));
    return Optional.ofNullable(json).map(value -> deserialize(value, RoomCardSnapshot.class));
  }

  @Override
  public void saveRoomCard(RoomCardSnapshot roomCard) {
    redisTemplate.opsForValue().set(roomCardKey(roomCard.roomId()), serialize(roomCard), STATE_TTL);
  }

  @Override
  public void deleteRoomCard(Long roomId) {
    redisTemplate.delete(roomCardKey(roomId));
  }

  @Override
  public List<Long> findRoomIdsWithCards() {
    Set<Long> rooms = new HashSet<>();
    try (Cursor<String> cursor =
        redisTemplate.scan(
            ScanOptions.scanOptions().match("room:*:active-card").count(100).build())) {
      while (cursor.hasNext()) {
        String[] parts = cursor.next().split(":");
        if (parts.length == 3 && parts[1].matches("[1-9][0-9]*")) {
          try {
            rooms.add(Long.valueOf(parts[1]));
          } catch (NumberFormatException ignored) {
            /* Ignore keys outside the room ID range. */
          }
        }
      }
    }
    return new ArrayList<>(rooms);
  }

  @Override
  public boolean activateRoomCard(
      RoomCardSnapshot expected,
      RoomCardSnapshot active,
      CardAssignmentSnapshot assignment,
      CardAssignmentSnapshot used) {
    if (expected.status() != RoomCardStatus.PENDING
        || active.status() != RoomCardStatus.ACTIVE
        || !active.sameExecution(expected)
        || assignment.status() != CardAssignmentStatus.ASSIGNED
        || used.status() != CardAssignmentStatus.USED
        || !assignment.used(used.usedAt()).equals(used)) return false;
    String cardKey = roomCardKey(expected.roomId());
    String raw = redisTemplate.opsForValue().get(cardKey);
    if (raw == null) return false;
    RoomCardSnapshot current = deserialize(raw, RoomCardSnapshot.class);
    if (current.status() != RoomCardStatus.PENDING || !current.sameExecution(expected))
      return false;
    String assignments = assignmentsKey(expected.roomId(), expected.performanceId());
    Object rawAssignment =
        redisTemplate.opsForHash().get(assignments, expected.sourceParticipantId().toString());
    if (rawAssignment == null
        || !deserialize(rawAssignment.toString(), CardAssignmentSnapshot.class).equals(assignment))
      return false;
    Long changed =
        redisTemplate.execute(
            ACTIVATE,
            List.of(cardKey, assignments),
            raw,
            expected.sourceParticipantId().toString(),
            rawAssignment.toString(),
            serialize(used),
            serialize(active),
            Long.toString(STATE_TTL.toMillis()));
    return Long.valueOf(1).equals(changed);
  }

  @Override
  public boolean removeRoomCard(RoomCardSnapshot expected) {
    String key = roomCardKey(expected.roomId());
    String raw = redisTemplate.opsForValue().get(key);
    if (raw == null) return false;
    RoomCardSnapshot current = deserialize(raw, RoomCardSnapshot.class);
    if (current.status() != expected.status() || !current.sameExecution(expected)) return false;
    return Long.valueOf(1).equals(redisTemplate.execute(REMOVE, List.of(key), raw));
  }

  @Override
  public void deleteCardState(Long roomId, Long performanceId) {
    redisTemplate.delete(assignmentsKey(roomId, performanceId));
    findRoomCard(roomId)
        .filter(roomCard -> performanceId.equals(roomCard.performanceId()))
        .ifPresent(this::removeRoomCard);
  }

  private String serialize(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new SerializationException("카드 상태 직렬화에 실패했습니다.", exception);
    }
  }

  private <T> T deserialize(String value, Class<T> type) {
    try {
      return objectMapper.readValue(value, type);
    } catch (JacksonException exception) {
      throw new SerializationException("카드 상태 역직렬화에 실패했습니다.", exception);
    }
  }

  private String assignmentsKey(Long roomId, Long performanceId) {
    return "room:" + roomId + ":performance:" + performanceId + ":cards";
  }

  private String roomCardKey(Long roomId) {
    return "room:" + roomId + ":active-card";
  }
}
