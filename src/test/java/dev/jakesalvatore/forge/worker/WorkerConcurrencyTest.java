package dev.jakesalvatore.forge.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jakesalvatore.forge.TestcontainersConfiguration;
import dev.jakesalvatore.forge.jobs.Job;
import dev.jakesalvatore.forge.jobs.JobRepository;
import dev.jakesalvatore.forge.jobs.NewJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Phase 2 exit criterion: many worker threads drain a queue concurrently
 * and no job is ever executed twice. Uses a recording handler that counts
 * executions per job id.
 */
@SpringBootTest
@ActiveProfiles("worker")
@Import({TestcontainersConfiguration.class, WorkerConcurrencyTest.RecordingHandlerConfig.class})
class WorkerConcurrencyTest {

    private static final int JOB_COUNT = 200;
    private static final ConcurrentHashMap<UUID, AtomicInteger> EXECUTIONS = new ConcurrentHashMap<>();

    @Autowired
    private JobRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingHandlerConfig {

        @Bean
        JobHandler recordingHandler() {
            return new JobHandler() {
                @Override
                public String type() {
                    return "record";
                }

                @Override
                public void handle(Job job) {
                    EXECUTIONS.computeIfAbsent(job.id(), id -> new AtomicInteger()).incrementAndGet();
                }
            };
        }
    }

    @Test
    void concurrentWorkersNeverProcessAJobTwice() throws Exception {
        for (int i = 0; i < JOB_COUNT; i++) {
            repository.insert(new NewJob("default", "record",
                    objectMapper.createObjectNode().put("n", i), 0, Instant.now(), 5, null));
        }

        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline && succeededCount() < JOB_COUNT) {
            Thread.sleep(200);
        }

        assertThat(succeededCount()).isEqualTo(JOB_COUNT);
        assertThat(EXECUTIONS).hasSize(JOB_COUNT);
        assertThat(EXECUTIONS.values())
                .allSatisfy(count -> assertThat(count.get()).isEqualTo(1));
    }

    @Test
    void unknownJobTypeIsDeadLetteredImmediately() throws Exception {
        var job = repository.insert(new NewJob("default", "no-such-type",
                objectMapper.createObjectNode(), 0, Instant.now(), 5, null)).orElseThrow();

        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && statusOf(job.id()).equals("PENDING")) {
            Thread.sleep(200);
        }
        // allow the CLAIMED -> DEAD transition to land
        Thread.sleep(500);

        assertThat(statusOf(job.id())).isEqualTo("DEAD");
        assertThat(repository.findById(job.id()).orElseThrow().lastError())
                .contains("no handler registered");
    }

    private int succeededCount() {
        return jdbc.sql("SELECT count(*) FROM jobs WHERE type = 'record' AND status = 'SUCCEEDED'")
                .query(Integer.class)
                .single();
    }

    private String statusOf(UUID id) {
        return jdbc.sql("SELECT status FROM jobs WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }
}
