package com.ssafy.ssasukae.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.ssafy.ssasukae.domain.card.recovery.CardRecoveryProperties;
import com.ssafy.ssasukae.domain.card.recovery.CardRecoveryWorker;
import com.ssafy.ssasukae.domain.card.redis.CardStateStore;
import com.ssafy.ssasukae.domain.card.service.CardService;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(CardRecoveryProperties.class)
public class CardSchedulerConfig {

  @Bean
  public CardRecoveryWorker cardRecoveryWorker(
      CardStateStore store, CardService service, CardRecoveryProperties properties) {
    return new CardRecoveryWorker(store, service, properties);
  }

  @Bean(name = "cardRecoveryScheduler")
  public TaskScheduler cardRecoveryScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("card-recovery-");
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    return scheduler;
  }

  @Bean(name = "cardTaskScheduler")
  public TaskScheduler cardTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("card-effect-");
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    return scheduler;
  }
}
