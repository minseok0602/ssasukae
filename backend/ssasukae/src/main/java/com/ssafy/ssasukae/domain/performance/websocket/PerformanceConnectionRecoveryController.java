package com.ssafy.ssasukae.domain.performance.websocket;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.ssafy.ssasukae.domain.performance.service.PerformanceSocketRecoveryService;
import com.ssafy.ssasukae.domain.performance.websocket.request.PerformanceConnectionRecoveryRequest;
import com.ssafy.ssasukae.global.security.jwt.AuthenticatedUser;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rooms/{roomId}/performances/{performanceId}")
public class PerformanceConnectionRecoveryController {
  private final PerformanceSocketRecoveryService recovery;

  @PostMapping("/connection-recovery")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void reconcile(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable Long roomId,
      @PathVariable Long performanceId,
      @Valid @RequestBody PerformanceConnectionRecoveryRequest request) {
    recovery.reconcile(user.userId(), roomId, performanceId, request);
  }
}
