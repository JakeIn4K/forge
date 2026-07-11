package dev.jakesalvatore.forge.worker;

import dev.jakesalvatore.forge.jobs.Job;
import dev.jakesalvatore.forge.jobs.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    private final JobHandlerRegistry registry;
    private final JobRepository repository;

    public JobExecutor(JobHandlerRegistry registry, JobRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }

    public void execute(Job job) {
        var handler = registry.forType(job.type());
        if (handler.isEmpty()) {
            repository.markFailed(job.id(), "no handler registered for type '" + job.type() + "'");
            return;
        }

        repository.markRunning(job.id());
        try {
            handler.get().handle(job);
            repository.markSucceeded(job.id());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            repository.markFailed(job.id(), "worker interrupted during execution");
        } catch (Exception e) {
            log.warn("job {} of type {} failed", job.id(), job.type(), e);
            repository.markFailed(job.id(), e.toString());
        }
    }
}
