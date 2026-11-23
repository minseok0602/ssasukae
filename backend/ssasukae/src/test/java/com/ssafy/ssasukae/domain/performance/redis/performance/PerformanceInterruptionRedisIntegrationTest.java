package com.ssafy.ssasukae.domain.performance.redis.performance;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "PERFORMER_TEST_REDIS_PORT", matches = ".+")
class PerformanceInterruptionRedisIntegrationTest {
  LettuceConnectionFactory factory;
  StringRedisTemplate redis;
  RedisPerformanceStore store;
  PerformanceSnapShot playing;
  Long room, performance;

  @BeforeEach
  void setup() {
    factory =
        new LettuceConnectionFactory(
            "127.0.0.1", Integer.parseInt(System.getenv("PERFORMER_TEST_REDIS_PORT")));
    factory.afterPropertiesSet();
    redis = new StringRedisTemplate(factory);
    redis.afterPropertiesSet();
    store = new RedisPerformanceStore(redis, new ObjectMapper());
    room = (System.nanoTime() & Long.MAX_VALUE);
    performance = room + 1;
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    playing =
        PerformanceSnapShot.prepare(performance, room, 100L, 1L, 20L, 180000L, now.minusSeconds(30))
            .startPlayback(now.minusSeconds(28));
    assertThat(store.create(playing)).isTrue();
  }

  @AfterEach
  void close() {
    store.delete(store.findByPerformanceId(performance).orElse(playing));
    redis.opsForZSet().remove("performance:recovery:deadlines", performance.toString());
    factory.destroy();
  }

  @Test
  void concurrentSuspensionWritesStateAndExactlyOneDeadlineTogether() throws Exception {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    var suspended = playing.reconcileInterruption("freeze", now, 22000);
    ExecutorService pool = Executors.newFixedThreadPool(10);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Boolean>> jobs = new ArrayList<>();
      for (int i = 0; i < 10; i++)
        jobs.add(
            pool.submit(
                () -> {
                  go.await();
                  return store.replaceWithRecoveryDeadline(
                      playing, suspended, now.plusSeconds(15).toInstant());
                }));
      go.countDown();
      int successes = 0;
      for (var job : jobs) if (job.get(10, TimeUnit.SECONDS)) successes++;
      assertThat(successes).isEqualTo(1);
      var current = store.findByPerformanceId(performance).orElseThrow();
      assertThat(current.lastInterruptionId()).isEqualTo("freeze");
      assertThat(redis.opsForZSet().score("performance:recovery:deadlines", performance.toString()))
          .isEqualTo((double) now.plusSeconds(15).toInstant().toEpochMilli());
      var ready = current.reconcileInterruption("client-freeze", now, 21000);
      assertThat(store.replaceWithRecoveryDeadline(current, ready, null)).isTrue();
      assertThat(redis.opsForZSet().score("performance:recovery:deadlines", performance.toString()))
          .isNull();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void staleUpdateDoesNotChangeStateOrRecoveryDeadline() {
    var at = OffsetDateTime.now(ZoneOffset.UTC);
    var changed = playing.reconcileInterruption("new", at, 20000);
    assertThat(store.replaceWithRecoveryDeadline(playing, changed, at.plusSeconds(15).toInstant()))
        .isTrue();
    assertThat(
            store.replaceWithRecoveryDeadline(
                playing, playing.reconcileInterruption("old", at, 10000), null))
        .isFalse();
    assertThat(store.findByPerformanceId(performance).orElseThrow().lastInterruptionId())
        .isEqualTo("new");
    assertThat(redis.opsForZSet().score("performance:recovery:deadlines", performance.toString()))
        .isNotNull();
  }

  @Test
  void oldSnapshotJsonWithoutNewFieldsCanStillBeConditionallyReplaced() {
    String key = "performance:session:" + performance;
    String raw = redis.opsForValue().get(key);
    assertThat(raw).doesNotContain("lastInterruptionId").doesNotContain("playbackAnchorAt");
    var decoded = store.findByPerformanceId(performance).orElseThrow();
    assertThat(
            store.replaceWithRecoveryDeadline(
                decoded,
                decoded.reconcileInterruption("upgrade", OffsetDateTime.now(ZoneOffset.UTC), 10000),
                null))
        .isTrue();
  }
}
