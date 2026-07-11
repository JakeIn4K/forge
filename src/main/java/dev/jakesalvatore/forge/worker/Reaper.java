package dev.jakesalvatore.forge.worker;

import dev.jakesalvatore.forge.jobs.Job;
import dev.jakesalvatore.forge.jobs.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Recovers jobs stranded by crashed workers. A job is reclaimed only when its
 * claim is older than the visibility timeout AND its worker's heartbeat is
 * gone — a slow job on a live worker is never touched. Every worker runs a
 * reaper; the conditional UPDATE in reclaim() makes that safe without any
 * coordination between them.
 */
@Component
@Profile("worker")
public class Reaper {

    private static final Logger log = LoggerFactory.getLogger(Reaper.class);
    private static final int BATCH_SIZE = 100;

    private final JobRepository repository;
    private final WorkerHeartbeat heartbeat;
    private final BackoffCalculator backoff;
    private final WorkerProperties properties;

    public Reaper(JobRepository repository, WorkerHeartbeat heartbeat, BackoffCalculator backoff,
                  WorkerProperties properties) {
        this.repository = repository;
        this.heartbeat = heartbeat;
        this.backoff = backoff;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${forge.worker.reap-interval}",
            fixedDelayString = "${forge.worker.reap-interval}")
    public void reap() {
        var cutoff = Instant.now().minus(properties.visibilityTimeout());
        for (Job job : repository.findStaleClaimed(cutoff, BATCH_SIZE)) {
            try {
                if (heartbeat.isAlive(job.claimedBy())) {
                    continue;
                }
            } catch (RuntimeException e) {
                // without Redis we can't tell dead workers from live ones;
                // reclaiming from a live worker is the worse mistake, so wait
                log.warn("redis unavailable, skipping reap pass", e);
                return;
            }

            var retryAt = Instant.now().plus(backoff.delayFor(job.attempts() + 1));
            repository.reclaim(job.id(), job.claimedBy(),
                            "reclaimed: worker " + job.claimedBy() + " missed heartbeats", retryAt)
                    .ifPresent(reclaimed -> log.info("reclaimed job {} from dead worker {} -> {}",
                            reclaimed.id(), job.claimedBy(), reclaimed.status()));
        }
    }
}
