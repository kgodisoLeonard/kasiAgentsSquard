package com.spaza.security;

import java.util.concurrent.ConcurrentHashMap;

/** Fixed-window rate limiter keyed by client (in memory, per server instance). */
public class RateLimiter {
  private final int limit; private final long windowMillis;
  private final ConcurrentHashMap<String,long[]> windows = new ConcurrentHashMap<>();

  public RateLimiter(int limit, int windowSeconds) { this.limit = limit; this.windowMillis = windowSeconds * 1000L; }

  public boolean allow(String key) {
    long now = System.currentTimeMillis();
    if (windows.size() > 10_000) windows.entrySet().removeIf(e -> now - e.getValue()[0] > windowMillis);
    long[] w = windows.compute(key, (k, v) -> {
      if (v == null || now - v[0] > windowMillis) return new long[]{now, 1};
      v[1]++; return v;
    });
    return w[1] <= limit;
  }
}
