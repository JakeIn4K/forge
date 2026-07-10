package dev.jakesalvatore.forge.jobs;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class JobService {

    private final JobRepository repository;
    private final IdempotencyCache idempotencyCache;

    public JobService(JobRepository repository, IdempotencyCache idempotencyCache) {
        this.repository = repository;
        this.idempotencyCache = idempotencyCache;
    }

    public SubmissionResult submit(NewJob newJob) {
        if (newJob.idempotencyKey() != null) {
            var cachedId = idempotencyCache.findJobId(newJob.idempotencyKey());
            if (cachedId.isPresent()) {
                return new SubmissionResult(getJob(cachedId.get()), false);
            }
        }

        var inserted = repository.insert(newJob);
        if (inserted.isPresent()) {
            Job job = inserted.get();
            if (job.idempotencyKey() != null) {
                idempotencyCache.remember(job.idempotencyKey(), job.id());
            }
            return new SubmissionResult(job, true);
        }

        // ON CONFLICT DO NOTHING returned no row: another submission with the
        // same idempotency key already won, so return that original job.
        Job existing = repository.findByIdempotencyKey(newJob.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException(
                        "insert conflicted on idempotency key but no job found for it"));
        return new SubmissionResult(existing, false);
    }

    public Job getJob(UUID id) {
        return repository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
    }

    public record SubmissionResult(Job job, boolean created) {
    }
}
