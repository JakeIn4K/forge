package dev.jakesalvatore.forge.api;

import dev.jakesalvatore.forge.jobs.JobService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public ResponseEntity<JobResponse> submit(@Valid @RequestBody SubmitJobRequest request,
                                              UriComponentsBuilder uriBuilder) {
        var result = jobService.submit(request.toNewJob());
        var body = JobResponse.from(result.job());
        var location = uriBuilder.path("/api/v1/jobs/{id}").buildAndExpand(body.id()).toUri();

        // 201 for a new job; 200 when an idempotency key matched an existing one
        return result.created()
                ? ResponseEntity.created(location).body(body)
                : ResponseEntity.ok().location(location).body(body);
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return JobResponse.from(jobService.getJob(id));
    }
}
