package com.mayshop.cart.writebehind.store;

import com.mayshop.cart.repository.CartKey;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes guest snapshots that are older than the guest cart TTL, once a night, in batches. A guest
 * cart whose Redis key has expired has no use for its snapshot; user snapshots are kept. A failure
 * is logged and the job runs again the next night.
 */
@Component
public class SnapshotCleanupJob {

  static final int BATCH_SIZE = 1_000;

  private static final Logger LOG = LoggerFactory.getLogger(SnapshotCleanupJob.class);

  private final CartSnapshotStore store;
  private final Clock clock;

  @Autowired
  public SnapshotCleanupJob(CartSnapshotStore store) {
    this(store, Clock.systemUTC());
  }

  SnapshotCleanupJob(CartSnapshotStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /** Runs at {@code CART_SNAPSHOT_CLEANUP_CRON} (default 03:30) in Ho Chi Minh City time. */
  @Scheduled(cron = "${cart.snapshot-cleanup.cron}", zone = "Asia/Ho_Chi_Minh")
  public void scheduled() {
    try {
      run();
    } catch (RuntimeException e) {
      LOG.error("Guest snapshot cleanup failed; it runs again at the next schedule", e);
    }
  }

  /** Deletes batches until fewer than a full batch is left; returns what was deleted. */
  Result run() {
    Instant cutoff = clock.instant().minus(CartKey.GUEST_TTL);
    int deleted = 0;
    int batches = 0;
    int last;
    do {
      last = store.deleteExpiredGuestSnapshots(cutoff, BATCH_SIZE);
      if (last > 0) {
        deleted += last;
        batches++;
      }
    } while (last == BATCH_SIZE);
    LOG.info("Guest snapshot cleanup deleted {} rows in {} batches", deleted, batches);
    return new Result(deleted, batches);
  }

  record Result(int deleted, int batches) {}
}
