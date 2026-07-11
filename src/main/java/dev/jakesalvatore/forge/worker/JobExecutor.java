package dev.jakesalvatore.forge.worker;

import dev.jakesalvatore.forge.jobs.Job;
import dev.jakesalvatore.forge.jobs.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    private final JobHandlerRegistry registry;
    private final JobRepository repository;
    private final BackoffCalculator backoff;

    public JobExecutor(JobHandlerRegistry registry, JobRepository repository, BackoffCalculator backoff) {
        this.registry = registry;
        this.repository = repository;
        this.backoff = backoff;
    }

    public void execute(Job job) {
        var handler = registry.forType(job.type());
        if (handler.isEmpty()) {
            // a missing handler is permanent: no number of retries can fix it
            repository.markDead(job.id(), "no handler registered for type '" + job.type() + "'");
            return;
        }

        repository.markRunning(job.id());
        try {
            handler.get().handle(job);
            repository.markSucceeded(job.id());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            retryOrDead(job, "worker interrupted during execution");
        } catch (Exception e) {
            log.warn("job {} of type {} failed", job.id(), job.type(), e);
            retryOrDead(job, e.toString());
        }
    }

    private void retryOrDead(Job job, String error) {
        int attemptsAfterThis = job.attempts() + 1;
        if (attemptsAfterThis >= job.maxAttempts()) {
            repository.markDead(job.id(), error);
            log.warn("job {} dead-lettered after {} attempts", job.id(), attemptsAfterThis);
        } else {
            var retryAt = Instant.now().plus(backoff.delayFor(attemptsAfterThis));
            repository.scheduleRetry(job.id(), error, retryAt);
        }
    }
}
