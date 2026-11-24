package com.ssafy.ssasukae.domain.card.recovery;

import org.springframework.scheduling.annotation.Scheduled;

import com.ssafy.ssasukae.domain.card.redis.CardStateStore;
import com.ssafy.ssasukae.domain.card.service.CardService;

import lombok.extern.slf4j.Slf4j;

/** Snapshots are the durable deadline records, including records without a separate job index. */
@Slf4j
public class CardRecoveryWorker {
  private final CardStateStore store;
  private final CardService service;
  private final CardRecoveryProperties properties;

  public CardRecoveryWorker(
      CardStateStore store, CardService service, CardRecoveryProperties properties) {
    this.store = store;
    this.service = service;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${card.recovery.scan-delay:250ms}",
      scheduler = "cardRecoveryScheduler")
  public void recover() {
    if (!properties.isEnabled()) return;
    try {
      for (Long roomId : store.findRoomIdsWithCards()) {
        try {
          service.recoverRoomCard(roomId);
        } catch (RuntimeException error) {
          log.error("카드 복구 실패. roomId={}", roomId, error);
        }
      }
    } catch (RuntimeException error) {
      log.error("카드 복구 대상 조회 실패", error);
    }
  }
}
