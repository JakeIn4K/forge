package dev.jakesalvatore.forge.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jakesalvatore.forge.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Priority and delay semantics of the claim query, proven deterministically:
 * no worker profile is active, so claims only happen when this test calls
 * claimNext directly.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ClaimOrderingTest {

    @Autowired
    private JobRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void higherPriorityIsClaimedFirst() {
        var low = insert("prio", 1, Instant.now());
        var high = insert("prio", 5, Instant.now());
        var mid = insert("prio", 3, Instant.now());

        assertThat(repository.claimNext("prio", "t").orElseThrow().id()).isEqualTo(high.id());
        assertThat(repository.claimNext("prio", "t").orElseThrow().id()).isEqualTo(mid.id());
        assertThat(repository.claimNext("prio", "t").orElseThrow().id()).isEqualTo(low.id());
    }

    @Test
    void equalPriorityIsClaimedOldestRunAtFirst() {
        var newer = insert("fifo", 0, Instant.now());
        var older = insert("fifo", 0, Instant.now().minus(Duration.ofMinutes(5)));

        assertThat(repository.claimNext("fifo", "t").orElseThrow().id()).isEqualTo(older.id());
        assertThat(repository.claimNext("fifo", "t").orElseThrow().id()).isEqualTo(newer.id());
    }

    @Test
    void delayedJobIsInvisibleUntilItsRunAtArrives() {
        var delayed = insert("delayed", 0, Instant.now().plus(Duration.ofHours(1)));

        assertThat(repository.claimNext("delayed", "t")).isEmpty();

        backdateRunAt(delayed.id(), Duration.ofHours(2));
        assertThat(repository.claimNext("delayed", "t").orElseThrow().id()).isEqualTo(delayed.id());
    }

    private Job insert(String queue, int priority, Instant runAt) {
        return repository.insert(new NewJob(queue, "sleep",
                objectMapper.createObjectNode(), priority, runAt, 5, null)).orElseThrow();
    }

    /** Rewrites run_at into the past — faster and steadier than sleeping. */
    private void backdateRunAt(UUID id, Duration by) {
        jdbc.sql("UPDATE jobs SET run_at = now() - :seconds * interval '1 second' WHERE id = :id")
                .param("seconds", by.toSeconds())
                .param("id", id)
                .update();
    }
}
