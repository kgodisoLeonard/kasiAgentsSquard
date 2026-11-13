package com.spaza.persistence;

import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.concurrent.*;

/**
 * Ordered background database writer. Services update their in-memory working copy immediately and hand the
 * database write to this single thread, so a request never waits for a round trip to Supabase (Frankfurt) and writes
 * still land in exactly the order they happened (important for the hash-chained audit log).
 *
 * {@link #flush()} waits until everything queued so far is written; it runs automatically on shutdown. Depending on
 * the DataSource and EntityManagerFactory makes Spring close this bean (and flush) before the database connections.
 * A hard kill can lose writes from the last moment before the kill; a normal stop loses nothing.
 */
@Component
public class WriteBehind implements DisposableBean {
  private static final Logger log = LoggerFactory.getLogger(WriteBehind.class);
  private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "db-writer"); t.setDaemon(true); return t; });

  public WriteBehind(DataSource dataSource, EntityManagerFactory emf) { /* dependencies only fix the shutdown order */ }

  public void submit(String what, Runnable task) {
    exec.execute(() -> {
      for (int attempt = 1; ; attempt++) {
        try { task.run(); return; }
        catch (RuntimeException e) {
          if (attempt >= 3) { log.error("Database write failed after 3 attempts ({}): {}", what, e.getMessage()); return; }
          try { Thread.sleep(500L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
      }
    });
  }

  /** Blocks until every write queued before this call has been attempted. */
  public void flush() {
    try { exec.submit(() -> {}).get(2, TimeUnit.MINUTES); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    catch (ExecutionException | TimeoutException e) { log.warn("Flush did not complete: {}", e.getMessage()); }
  }

  @Override public void destroy() {
    flush();
    exec.shutdown();
  }
}
