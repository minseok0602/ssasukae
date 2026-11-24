package com.ssafy.ssasukae.domain.card.recovery;

import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssafy.ssasukae.domain.card.redis.CardStateStore;
import com.ssafy.ssasukae.domain.card.service.CardService;

class CardRecoveryWorkerTest {
  @Test
  void failingRoomDoesNotBlockOtherRoomsOrNextScan() {
    CardStateStore store = mock(CardStateStore.class);
    CardService service = mock(CardService.class);
    when(store.findRoomIdsWithCards()).thenReturn(List.of(1L, 2L));
    doThrow(new IllegalStateException("temporary")).doNothing().when(service).recoverRoomCard(1L);
    CardRecoveryWorker worker =
        new CardRecoveryWorker(store, service, new CardRecoveryProperties());
    worker.recover();
    worker.recover();
    verify(service, times(2)).recoverRoomCard(1L);
    verify(service, times(2)).recoverRoomCard(2L);
  }

  @Test
  void disabledRecoveryDoesNotReadOrModifyState() {
    CardStateStore store = mock(CardStateStore.class);
    CardService service = mock(CardService.class);
    CardRecoveryProperties properties = new CardRecoveryProperties();
    properties.setEnabled(false);
    new CardRecoveryWorker(store, service, properties).recover();
    verifyNoInteractions(store, service);
  }
}
