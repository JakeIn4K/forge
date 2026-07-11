package dev.jakesalvatore.forge.api;

import dev.jakesalvatore.forge.jobs.JobService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/queues")
@Validated
public class QueueController {

    private final JobService jobService;

    public QueueController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/{queue}/dead")
    public List<JobResponse> dead(@PathVariable String queue,
                                  @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return jobService.deadJobs(queue, limit).stream()
                .map(JobResponse::from)
                .toList();
    }
}
