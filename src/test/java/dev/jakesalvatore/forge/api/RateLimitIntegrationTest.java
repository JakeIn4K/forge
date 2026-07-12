package dev.jakesalvatore.forge.api;

import dev.jakesalvatore.forge.TestcontainersConfiguration;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "forge.api.keys=burst-key,calm-key",
        "forge.rate-limit.capacity=3",
        "forge.rate-limit.refill-per-second=1"
})
@Import(TestcontainersConfiguration.class)
class RateLimitIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void burstBeyondCapacityIs429AndOtherKeysAreUnaffected() {
        int created = 0;
        int limited = 0;
        ResponseEntity<String> lastLimited = null;
        for (int i = 0; i < 10; i++) {
            var response = submit("burst-key");
            if (response.getStatusCode() == HttpStatus.CREATED) {
                created++;
            } else if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                limited++;
                lastLimited = response;
            }
        }

        // capacity 3 at ~1 token/s refill: the burst empties the bucket fast
        assertThat(created).isBetween(3, 6);
        assertThat(limited).isEqualTo(10 - created);
        assertThat(lastLimited.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(lastLimited.getHeaders().getFirst("Retry-After")).isEqualTo("1");

        // buckets are per key: a different key still has tokens
        assertThat(submit("calm-key").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<String> submit(String apiKey) {
        return restTemplate.exchange(
                RequestEntity.post(URI.create("/api/v1/jobs"))
                        .header(ApiKeyFilter.HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("type", "sleep", "payload", Map.of("millis", 0))),
                String.class);
    }
}
