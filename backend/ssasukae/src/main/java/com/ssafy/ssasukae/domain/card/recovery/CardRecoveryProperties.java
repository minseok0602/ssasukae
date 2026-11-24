package com.ssafy.ssasukae.domain.card.recovery;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("card.recovery")
public class CardRecoveryProperties {
  private boolean enabled = true;
  // A registered timer may have normal scheduling jitter; longer misfires are cancelled.
  private Duration startExecutionGrace = Duration.ofMillis(500);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Duration getStartExecutionGrace() {
    return startExecutionGrace;
  }

  public void setStartExecutionGrace(Duration value) {
    if (value == null || value.isNegative())
      throw new IllegalArgumentException("start-execution-grace must be non-negative");
    startExecutionGrace = value;
  }
}
