package dev.jakesalvatore.forge.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jakesalvatore.forge.TestcontainersConfiguration;
import dev.jakesalvatore.forge.jobs.JobRepository;
import dev.jakesalvatore.forge.jobs.NewJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class JobApiIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JobRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void submittedJobCanBeFetchedById() {
        ResponseEntity<JobResponse> submitted = submit(Map.of(
                "queue", "emails",
                "type", "http-callback",
                "payload", Map.of("to", "user@example.com"),
                "priority", 5
        ));

        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(submitted.getHeaders().getLocation()).isNotNull();

        JobResponse job = submitted.getBody();
        assertThat(job.queue()).isEqualTo("emails");
        assertThat(job.type()).isEqualTo("http-callback");
        assertThat(job.status().name()).isEqualTo("PENDING");
        assertThat(job.priority()).isEqualTo(5);
        assertThat(job.attempts()).isZero();
        assertThat(job.payload().get("to").asText()).isEqualTo("user@example.com");

        ResponseEntity<JobResponse> fetched = restTemplate.getForEntity(
                submitted.getHeaders().getLocation(), JobResponse.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().id()).isEqualTo(job.id());
    }

    @Test
    void submitAppliesDefaults() {
        ResponseEntity<JobResponse> submitted = submit(Map.of("type", "sleep", "payload", Map.of("k", "v")));

        JobResponse job = submitted.getBody();
        assertThat(job.queue()).isEqualTo("default");
        assertThat(job.priority()).isZero();
        assertThat(job.maxAttempts()).isEqualTo(5);
        assertThat(job.runAt()).isNotNull();
    }

    @Test
    void duplicateIdempotencyKeyReturnsOriginalJob() {
        String key = "idem-" + UUID.randomUUID();

        ResponseEntity<JobResponse> first = submit(Map.of(
                "type", "sleep",
                "payload", Map.of("n", 1),
                "idempotencyKey", key
        ));
        ResponseEntity<JobResponse> second = submit(Map.of(
                "type", "sleep",
                "payload", Map.of("n", 2),
                "idempotencyKey", key
        ));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().id()).isEqualTo(first.getBody().id());
        // the original payload wins; the duplicate's is discarded
        assertThat(second.getBody().payload().get("n").asInt()).isEqualTo(1);
    }

    @Test
    void missingPayloadIsRejectedWithProblemJson() {
        ResponseEntity<String> response = restTemplate.exchange(
                RequestEntity.post(URI.create("/api/v1/jobs"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("queue", "emails", "type", "sleep")),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void unknownJobIdReturns404ProblemJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/jobs/" + UUID.randomUUID(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void deadJobsAreListedPerQueue() {
        var dead = repository.insert(new NewJob("reports", "sleep",
                objectMapper.createObjectNode(), 0, Instant.now(), 1, null)).orElseThrow();
        repository.markDead(dead.id(), "boom");

        ResponseEntity<JobResponse[]> response = restTemplate.getForEntity(
                "/api/v1/queues/reports/dead", JobResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .anySatisfy(job -> {
                    assertThat(job.id()).isEqualTo(dead.id());
                    assertThat(job.status().name()).isEqualTo("DEAD");
                    assertThat(job.lastError()).isEqualTo("boom");
                });
    }

    private ResponseEntity<JobResponse> submit(Map<String, Object> body) {
        return restTemplate.exchange(
                RequestEntity.post(URI.create("/api/v1/jobs"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
                JobResponse.class);
    }
}
