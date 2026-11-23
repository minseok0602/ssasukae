package com.ssafy.ssasukae.domain.performance.websocket;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.*;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;

import com.ssafy.ssasukae.domain.performance.service.PerformanceSocketRecoveryService;
import com.ssafy.ssasukae.global.security.jwt.AuthenticatedUser;

class PerformanceConnectionRecoveryControllerTest {
  PerformanceSocketRecoveryService service;
  MockMvc mvc;

  @BeforeEach
  void setup() {
    service = mock(PerformanceSocketRecoveryService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new PerformanceConnectionRecoveryController(service))
            .setCustomArgumentResolvers(
                new HandlerMethodArgumentResolver() {
                  public boolean supportsParameter(MethodParameter p) {
                    return p.getParameterType() == AuthenticatedUser.class;
                  }

                  public Object resolveArgument(
                      MethodParameter p,
                      ModelAndViewContainer c,
                      NativeWebRequest r,
                      WebDataBinderFactory f) {
                    return new AuthenticatedUser(1L, "test@example.invalid", "USER");
                  }
                })
            .build();
  }

  @Test
  void recoveryAcknowledgesWithNoContentForTheFrontendApiClient() throws Exception {
    mvc.perform(
            post("/api/rooms/10/performances/30/connection-recovery")
                .contentType("application/json")
                .content(
                    "{\"interruptionId\":\"freeze\",\"interruptedAt\":\"2026-11-23T00:00:00Z\",\"playbackPositionMs\":22000}"))
        .andExpect(status().isNoContent());
    verify(service).reconcile(eq(1L), eq(10L), eq(30L), any());
  }

  @Test
  void negativeAudioPositionIsRejectedBeforeStateChanges() throws Exception {
    mvc.perform(
            post("/api/rooms/10/performances/30/connection-recovery")
                .contentType("application/json")
                .content(
                    "{\"interruptionId\":\"freeze\",\"interruptedAt\":\"2026-11-23T00:00:00Z\",\"playbackPositionMs\":-1}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }
}
