package dev.jakesalvatore.forge.metrics;

import dev.jakesalvatore.forge.jobs.JobRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes jobs-per-queue-per-status as the forge_queue_depth gauge — the
 * first thing an operator (or a Grafana panel) looks at. Refreshed on a
 * schedule because a gauge needs a value to sample, and counting rows on
 * every scrape would put the cost on the metrics path.
 */
@Component
public class QueueDepthMetrics {

    private static final Logger log = LoggerFactory.getLogger(QueueDepthMetrics.class);

    private final JobRepository repository;
    private final MultiGauge depths;

    public QueueDepthMetrics(JobRepository repository, MeterRegistry registry) {
        this.repository = repository;
        this.depths = MultiGauge.builder("forge.queue.depth")
                .description("jobs per queue per status")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${forge.metrics.depth-refresh:5s}")
    public void refresh() {
        try {
            depths.register(repository.countByQueueAndStatus().stream()
                            .map(row -> MultiGauge.Row.of(
                                    Tags.of("queue", row.queue(), "status", row.status().name()),
                                    row.depth()))
                            .toList(),
                    true);
        } catch (RuntimeException e) {
            log.warn("queue depth refresh failed", e);
        }
    }
}
