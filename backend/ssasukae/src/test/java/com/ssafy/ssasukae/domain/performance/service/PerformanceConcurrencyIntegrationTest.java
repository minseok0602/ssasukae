package com.ssafy.ssasukae.domain.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.ssasukae.domain.card.service.CardService;
import com.ssafy.ssasukae.domain.performance.recovery.PerformanceRecoveryProperties;
import com.ssafy.ssasukae.domain.performance.recovery.RedisPerformanceRecoveryDeadlineStore;
import com.ssafy.ssasukae.domain.performance.redis.performance.PerformanceSnapShot;
import com.ssafy.ssasukae.domain.performance.redis.performance.PerformanceStore;
import com.ssafy.ssasukae.domain.performance.redis.performance.RedisPerformanceStore;
import com.ssafy.ssasukae.domain.performance.type.PerformanceStatus;
import com.ssafy.ssasukae.domain.performance.websocket.PerformanceWebSocketEventPublisher;
import com.ssafy.ssasukae.domain.performance.websocket.PerformanceWebSocketEventType;
import com.ssafy.ssasukae.domain.performance.websocket.payload.PerformancePreparationStartedPayload;
import com.ssafy.ssasukae.domain.performance.websocket.payload.PerformanceStartedPayload;
import com.ssafy.ssasukae.domain.performance.websocket.request.PerformancePrepareRequest;
import com.ssafy.ssasukae.domain.room.entity.Room;
import com.ssafy.ssasukae.domain.room.entity.RoomParticipant;
import com.ssafy.ssasukae.domain.room.repository.RoomParticipantRepository;
import com.ssafy.ssasukae.domain.room.repository.RoomRepository;
import com.ssafy.ssasukae.domain.room.type.RoomMode;
import com.ssafy.ssasukae.domain.room.type.RoomStatus;
import com.ssafy.ssasukae.domain.song.entity.Song;
import com.ssafy.ssasukae.domain.song.repository.SongRepository;
import com.ssafy.ssasukae.domain.user.entity.User;
import com.ssafy.ssasukae.domain.user.repository.UserRepository;
import com.ssafy.ssasukae.domain.user.type.OAuthProvider;
import com.ssafy.ssasukae.domain.user.type.Role;
import com.ssafy.ssasukae.global.exception.websocket.WebSocketBusinessException;
import com.ssafy.ssasukae.global.exception.websocket.WebSocketErrorCode;
import com.ssafy.ssasukae.integration.aws.S3StorageService;
import com.ssafy.ssasukae.support.IntegrationTestSupport;

/** Real MySQL transactions and Redis Lua scripts; only external side effects are mocked. */
@SpringBootTest(classes = PerformanceConcurrencyIntegrationTest.TestApplication.class)
class PerformanceConcurrencyIntegrationTest extends IntegrationTestSupport {

  private static final int REQUEST_COUNT = 20;

  @Autowired private PerformanceService performanceService;
  @Autowired private PerformanceStore performanceStore;
  @Autowired private StringRedisTemplate redisTemplate;
  @Autowired private RoomRepository roomRepository;
  @Autowired private RoomParticipantRepository participantRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private SongRepository songRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private S3StorageService s3StorageService;
  @MockitoBean private PerformanceWebSocketEventPublisher eventPublisher;
  @MockitoBean private CardService cardService;

  private Fixture fixture;
  private final List<Long> candidatePerformanceIds = new ArrayList<>();

  @BeforeEach
  void createCommittedFixture() {
    // Worker transactions must see committed fixture data. Do not annotate this test
    // @Transactional.
    fixture =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> {
                  String suffix = UUID.randomUUID().toString();
                  User user =
                      userRepository.save(
                          User.builder()
                              .email(suffix + "@test.com")
                              .nickname("concurrency")
                              .provider(OAuthProvider.GOOGLE)
                              .providerId(suffix)
                              .role(Role.USER)
                              .build());
                  Room room =
                      roomRepository.save(
                          Room.create(
                              suffix.substring(0, 6),
                              "concurrency test",
                              RoomMode.GENERAL,
                              user,
                              "test-" + suffix,
                              LocalDateTime.now()));
                  RoomParticipant participant =
                      RoomParticipant.join(room, user, LocalDateTime.now());
                  participant.connect("connection-" + suffix);
                  participant.promoteToPerformer();
                  participantRepository.save(participant);
                  Song song =
                      songRepository.save(
                          Song.create(
                              "concurrency song",
                              "test artist",
                              180,
                              3,
                              "cover.jpg",
                              "mr.mp3",
                              "midi.json",
                              "lyrics.json"));
                  return new Fixture(user.getId(), room.getId(), participant.getId(), song.getId());
                });
  }

  @AfterEach
  void deleteOwnedFixture() {
    if (fixture == null) {
      return;
    }
    performanceStore.findActiveByRoomId(fixture.roomId()).ifPresent(performanceStore::delete);
    candidatePerformanceIds.forEach(id -> redisTemplate.delete("performance:session:" + id));
    candidatePerformanceIds.clear();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              participantRepository.deleteById(fixture.participantId());
              roomRepository.deleteById(fixture.roomId());
              songRepository.deleteById(fixture.songId());
              userRepository.deleteById(fixture.userId());
            });
  }

  @RepeatedTest(3)
  @DisplayName("동일한 방에 공연 준비 20건이 동시에 들어오면 하나만 성공하고 시작 이벤트도 한 세트만 발행한다")
  void concurrentPrepareCreatesExactlyOnePerformance() throws Exception {
    when(s3StorageService.presignedUrl(any())).thenReturn("https://test.invalid/resource");

    List<WebSocketErrorCode> outcomes =
        runConcurrently(
            IntStream.range(0, REQUEST_COUNT)
                .<Callable<WebSocketErrorCode>>mapToObj(
                    index ->
                        () -> {
                          try {
                            // Calls the Spring proxy: every worker owns a real transaction.
                            performanceService.prepare(
                                fixture.userId(),
                                fixture.roomId(),
                                new PerformancePrepareRequest(fixture.songId()));
                            return null;
                          } catch (WebSocketBusinessException exception) {
                            return exception.getErrorCode();
                          }
                        })
                .toList());

    assertThat(outcomes.stream().filter(outcome -> outcome == null).count()).isEqualTo(1);
    // Requests waiting on the room lock observe PLAYING after the winner commits.
    assertThat(outcomes.stream().filter(outcome -> outcome != null).toList())
        .hasSize(REQUEST_COUNT - 1)
        .containsOnly(WebSocketErrorCode.INVALID_ROOM_STATE);
    assertThat(roomRepository.findById(fixture.roomId()).orElseThrow().getStatus())
        .isEqualTo(RoomStatus.PLAYING);

    PerformanceSnapShot active =
        performanceStore.findActiveByRoomId(fixture.roomId()).orElseThrow();
    assertThat(active.status()).isEqualTo(PerformanceStatus.PREPARING);
    assertThat(active.performerParticipantId()).isEqualTo(fixture.participantId());
    assertThat(active.performerUserId()).isEqualTo(fixture.userId());
    assertThat(active.songId()).isEqualTo(fixture.songId());
    assertThat(performanceStore.findByPerformanceId(active.performanceId())).contains(active);
    assertThat(redisTemplate.keys("performance:session:*"))
        .containsExactly("performance:session:" + active.performanceId());

    verify(eventPublisher)
        .publish(
            eq(fixture.roomId()),
            eq(PerformanceWebSocketEventType.PERFORMANCE_STARTED),
            eq(
                new PerformanceStartedPayload(
                    active.performanceId(),
                    fixture.participantId(),
                    fixture.songId(),
                    PerformanceStatus.PREPARING,
                    RoomStatus.PLAYING)));
    verify(eventPublisher)
        .publish(
            eq(fixture.roomId()),
            eq(PerformanceWebSocketEventType.PERFORMANCE_PREPARATION_STARTED),
            any(PerformancePreparationStartedPayload.class));
    verifyNoMoreInteractions(eventPublisher);
    verify(s3StorageService, times(3)).presignedUrl(any());
  }

  @RepeatedTest(3)
  @DisplayName("DB 잠금 없이 Redis Lua 생성 20건이 경쟁해도 활성 공연과 세션은 하나만 생성된다")
  void concurrentRedisReservationsHaveExactlyOneWinner() throws Exception {
    List<PerformanceSnapShot> candidates =
        IntStream.range(0, REQUEST_COUNT)
            .mapToObj(
                index ->
                    PerformanceSnapShot.prepare(
                        performanceStore.nextPerformanceId(),
                        fixture.roomId(),
                        fixture.participantId(),
                        fixture.userId(),
                        fixture.songId(),
                        OffsetDateTime.now()))
            .toList();
    candidates.forEach(candidate -> candidatePerformanceIds.add(candidate.performanceId()));

    List<Boolean> outcomes =
        runConcurrently(
            candidates.stream()
                .<Callable<Boolean>>map(candidate -> () -> performanceStore.create(candidate))
                .toList());

    assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
    assertThat(outcomes.stream().filter(outcome -> !outcome).count()).isEqualTo(REQUEST_COUNT - 1);
    PerformanceSnapShot winner = candidates.get(outcomes.indexOf(true));
    assertThat(performanceStore.findActiveByRoomId(fixture.roomId())).contains(winner);
    assertThat(redisTemplate.keys("performance:session:*"))
        .containsExactly("performance:session:" + winner.performanceId());
    for (int index = 0; index < candidates.size(); index++) {
      assertThat(
              performanceStore
                  .findByPerformanceId(candidates.get(index).performanceId())
                  .isPresent())
          .isEqualTo(outcomes.get(index));
    }
    verifyNoInteractions(eventPublisher, s3StorageService, cardService);
  }

  private <T> List<T> runConcurrently(List<Callable<T>> requests) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(requests.size());
    CountDownLatch ready = new CountDownLatch(requests.size());
    CountDownLatch start = new CountDownLatch(1);
    List<Future<T>> futures = new ArrayList<>();
    try {
      for (Callable<T> request : requests) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent start gate timed out");
                  }
                  return request.call();
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        // Unexpected failures and lock timeouts fail the test instead of counting as rejections.
        results.add(future.get(30, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private record Fixture(Long userId, Long roomId, Long participantId, Long songId) {}

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @EnableJpaAuditing
  @EntityScan("com.ssafy.ssasukae.domain")
  @EnableJpaRepositories("com.ssafy.ssasukae.domain")
  @Import({
    PerformanceService.class,
    PerformanceTransactionSupport.class,
    PerformanceCancellationProcessor.class,
    RedisPerformanceStore.class,
    RedisPerformanceRecoveryDeadlineStore.class,
    PerformanceRecoveryProperties.class
  })
  static class TestApplication {}
}
