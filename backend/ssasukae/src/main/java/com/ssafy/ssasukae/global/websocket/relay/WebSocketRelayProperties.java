package com.ssafy.ssasukae.global.websocket.relay;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("websocket.relay")
public class WebSocketRelayProperties {
  private boolean enabled = true;
  private String streamKey = "ssasukae:websocket:events";
  private long maxLength = 100_000;
  private int batchSize = 100;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean value) {
    enabled = value;
  }

  public String getStreamKey() {
    return streamKey;
  }

  public void setStreamKey(String value) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("stream-key is required");
    streamKey = value;
  }

  public long getMaxLength() {
    return maxLength;
  }

  public void setMaxLength(long value) {
    if (value < 1) throw new IllegalArgumentException("max-length must be positive");
    maxLength = value;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int value) {
    if (value < 1) throw new IllegalArgumentException("batch-size must be positive");
    batchSize = value;
  }
}
