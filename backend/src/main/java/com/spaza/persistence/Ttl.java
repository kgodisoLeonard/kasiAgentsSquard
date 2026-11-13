package com.spaza.persistence;

import java.time.Duration;
import java.util.function.Supplier;

/** A value re-read from its source at most once per time-to-live (for data that rarely changes, e.g. the user list). */
public final class Ttl<T> implements Supplier<T> {
  private final Supplier<T> source; private final long ttlNanos;
  private T value; private long loadedAt;

  public Ttl(Duration ttl, Supplier<T> source) { this.source = source; this.ttlNanos = ttl.toNanos(); }

  @Override public synchronized T get() {
    long now = System.nanoTime();
    if (value == null || now - loadedAt > ttlNanos) { value = source.get(); loadedAt = now; }
    return value;
  }

  public synchronized void invalidate() { value = null; }
}
