package com.ssafy.ssasukae.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.ssafy.ssasukae.global.websocket.relay.RedisWebSocketEventRelay;
import com.ssafy.ssasukae.global.websocket.relay.WebSocketRelayProperties;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(WebSocketRelayProperties.class)
public class WebSocketRelayConfig {
  @Bean
  public RedisWebSocketEventRelay redisWebSocketEventRelay(
      StringRedisTemplate redis,
      ObjectMapper mapper,
      SimpMessagingTemplate local,
      WebSocketRelayProperties properties) {
    return new RedisWebSocketEventRelay(redis, mapper, local, properties);
  }

  @Bean("webSocketRelayScheduler")
  public TaskScheduler webSocketRelayScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("websocket-relay-");
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    return scheduler;
  }
}
