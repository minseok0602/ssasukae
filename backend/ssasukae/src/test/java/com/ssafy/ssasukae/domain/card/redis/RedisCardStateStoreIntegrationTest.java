package com.ssafy.ssasukae.domain.card.redis;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.ssafy.ssasukae.domain.card.type.CardTier;
import com.ssafy.ssasukae.domain.card.websocket.type.*;

import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "REDIS_INTEGRATION_HOST", matches = ".+")
class RedisCardStateStoreIntegrationTest {
  private LettuceConnectionFactory factory;
  private StringRedisTemplate redis;
  private RedisCardStateStore store;
  private Long room;
  private RoomCardSnapshot pending, active;
  private CardAssignmentSnapshot assignment, used;

  @BeforeEach
  void setup() {
    factory = new LettuceConnectionFactory(System.getenv("REDIS_INTEGRATION_HOST"), 6379);
    factory.afterPropertiesSet();
    redis = new StringRedisTemplate(factory);
    redis.afterPropertiesSet();
    store = new RedisCardStateStore(redis, new ObjectMapper());
    room = (System.nanoTime() & Long.MAX_VALUE) + 1;
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    pending =
        new RoomCardSnapshot(
            room,
            100L,
            RoomCardStatus.PENDING,
            15L,
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
            now,
            now.plusSeconds(3),
            null,
            null);
    active = pending.active(now.plusSeconds(3), now.plusSeconds(18), null);
    assignment =
        new CardAssignmentSnapshot(
            room,
            100L,
            15L,
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
            now,
            null);
    used = assignment.used(now.plusSeconds(3));
    store.saveAssignment(assignment);
    store.saveRoomCard(pending);
  }

  @AfterEach
  void close() {
    redis.delete(
        List.of("room:" + room + ":active-card", "room:" + room + ":performance:100:cards"));
    factory.destroy();
  }

  private List<Boolean> race(Callable<Boolean> action) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(20);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Boolean>> futures = new ArrayList<>();
      for (int i = 0; i < 20; i++)
        futures.add(
            executor.submit(
                () -> {
                  go.await();
                  return action.call();
                }));
      go.countDown();
      List<Boolean> answers = new ArrayList<>();
      for (Future<Boolean> future : futures) answers.add(future.get(10, TimeUnit.SECONDS));
      return answers;
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void concurrentActivationConsumesAssignmentAndActivatesExactlyOnce() throws Exception {
    assertThat(
            race(() -> store.activateRoomCard(pending, active, assignment, used)).stream()
                .filter(Boolean::booleanValue)
                .count())
        .isEqualTo(1);
    assertThat(store.findRoomCard(room).orElseThrow().status()).isEqualTo(RoomCardStatus.ACTIVE);
    assertThat(store.findAssignment(room, 100L, 15L).orElseThrow().status())
        .isEqualTo(CardAssignmentStatus.USED);
    assertThat(redis.getExpire("room:" + room + ":active-card")).isPositive();
    assertThat(redis.getExpire("room:" + room + ":performance:100:cards")).isPositive();
    assertThat(
            race(() -> store.removeRoomCard(active)).stream().filter(Boolean::booleanValue).count())
        .isEqualTo(1);
    assertThat(store.findRoomCard(room)).isEmpty();
  }

  @Test
  void staleExecutionCannotRemoveOrActivateReplacement() {
    RoomCardSnapshot replacement =
        new RoomCardSnapshot(
            room,
            100L,
            RoomCardStatus.PENDING,
            15L,
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
            pending.approvedAt().plusSeconds(1),
            pending.activateAt().plusSeconds(1),
            null,
            null);
    store.saveRoomCard(replacement);
    assertThat(store.activateRoomCard(pending, active, assignment, used)).isFalse();
    assertThat(store.removeRoomCard(pending)).isFalse();
    assertThat(store.findRoomCard(room).orElseThrow().sameExecution(replacement)).isTrue();
    assertThat(store.findAssignment(room, 100L, 15L).orElseThrow().status())
        .isEqualTo(CardAssignmentStatus.ASSIGNED);
  }

  @Test
  void mismatchedAssignmentDoesNotPartiallyActivate() {
    store.saveAssignment(used);
    assertThat(store.activateRoomCard(pending, active, assignment, used)).isFalse();
    assertThat(store.findRoomCard(room).orElseThrow().status()).isEqualTo(RoomCardStatus.PENDING);
  }

  @Test
  void discoversSnapshotsWithoutAnIndex() {
    assertThat(store.findRoomIdsWithCards()).contains(room);
  }
}
