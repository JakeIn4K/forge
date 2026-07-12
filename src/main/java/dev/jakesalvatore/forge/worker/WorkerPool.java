package dev.jakesalvatore.forge.worker;

import dev.jakesalvatore.forge.jobs.JobRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * N threads, each looping: claim one job, execute it, repeat. Sleeps for the
 * poll interval only when the queue is empty, so a busy queue is drained at
 * full speed. Only active under the "worker" Spring profile.
 */
@Component
@Profile("worker")
public class WorkerPool implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    private final WorkerProperties properties;
    private final JobRepository repository;
    private final JobExecutor executor;
    private final String workerId;
    private final Timer claimHit;
    private final Timer claimEmpty;

    private volatile boolean running = false;
    private ExecutorService pool;

    public WorkerPool(WorkerProperties properties, JobRepository repository, JobExecutor executor,
                      WorkerIdentity identity, MeterRegistry registry) {
        this.properties = properties;
        this.repository = repository;
        this.executor = executor;
        this.workerId = identity.id();
        this.claimHit = registry.timer("forge.claim.duration", "result", "hit");
        this.claimEmpty = registry.timer("forge.claim.duration", "result", "empty");
    }

    @Override
    public void start() {
        running = true;
        pool = Executors.newFixedThreadPool(properties.threads());
        for (int i = 0; i < properties.threads(); i++) {
            // claims carry the process id, not a per-thread one: liveness (and
            // therefore reclaim) is decided at the process level
            pool.submit(() -> claimLoop(workerId));
        }
        log.info("worker {} started with {} threads polling queue '{}' every {}",
                workerId, properties.threads(), properties.queue(), properties.pollInterval());
    }

    private void claimLoop(String claimant) {
        while (running) {
            try {
                long started = System.nanoTime();
                var jobs = repository.claimBatch(properties.queue(), claimant, properties.claimBatchSize());
                (jobs.isEmpty() ? claimEmpty : claimHit)
                        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                if (!jobs.isEmpty()) {
                    for (var job : jobs) {
                        executor.execute(job);
                    }
                } else {
                    Thread.sleep(properties.pollInterval().toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // never let one bad claim kill the loop; back off and retry
                log.error("claim loop error, backing off", e);
                try {
                    Thread.sleep(properties.pollInterval().toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        pool.shutdown();
        try {
            // graceful: let in-flight jobs finish before the app closes
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("in-flight jobs did not finish within 30s, interrupting");
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
        log.info("worker {} stopped", workerId);
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
