package dev.jakesalvatore.forge.worker;

import dev.jakesalvatore.forge.jobs.Job;
import dev.jakesalvatore.forge.jobs.JobRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    private final JobHandlerRegistry registry;
    private final JobRepository repository;
    private final BackoffCalculator backoff;
    private final MeterRegistry meters;

    public JobExecutor(JobHandlerRegistry registry, JobRepository repository, BackoffCalculator backoff,
                       MeterRegistry meters) {
        this.registry = registry;
        this.repository = repository;
        this.backoff = backoff;
        this.meters = meters;
    }

    public void execute(Job job) {
        var handler = registry.forType(job.type());
        if (handler.isEmpty()) {
            // a missing handler is permanent: no number of retries can fix it
            repository.markDead(job.id(), "no handler registered for type '" + job.type() + "'");
            meters.counter("forge.jobs.dead", "reason", "unknown-type").increment();
            return;
        }

        repository.markRunning(job.id());
        long started = System.nanoTime();
        String outcome;
        try {
            handler.get().handle(job);
            repository.markSucceeded(job.id());
            outcome = "success";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = retryOrDead(job, "worker interrupted during execution");
        } catch (Exception e) {
            log.warn("job {} of type {} failed", job.id(), job.type(), e);
            outcome = retryOrDead(job, e.toString());
        }
        durationTimer(job.type(), outcome).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }

    private String retryOrDead(Job job, String error) {
        int attemptsAfterThis = job.attempts() + 1;
        if (attemptsAfterThis >= job.maxAttempts()) {
            repository.markDead(job.id(), error);
            meters.counter("forge.jobs.dead", "reason", "max-attempts").increment();
            log.warn("job {} dead-lettered after {} attempts", job.id(), attemptsAfterThis);
            return "dead";
        }
        var retryAt = Instant.now().plus(backoff.delayFor(attemptsAfterThis));
        repository.scheduleRetry(job.id(), error, retryAt);
        meters.counter("forge.jobs.retried").increment();
        return "retry";
    }

    private Timer durationTimer(String type, String outcome) {
        return meters.timer("forge.job.duration", "type", type, "outcome", outcome);
    }
}
