package dev.jakesalvatore.forge.worker;

import dev.jakesalvatore.forge.jobs.Job;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * POSTs the job's "body" field to the URL in its "url" field. The demo
 * handler for jobs with side effects on other systems.
 */
@Component
public class HttpCallbackJobHandler implements JobHandler {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public String type() {
        return "http-callback";
    }

    @Override
    public void handle(Job job) throws Exception {
        String url = job.payload().path("url").asText(null);
        if (url == null) {
            throw new IllegalArgumentException("payload.url is required");
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(job.payload().path("body").toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("callback returned HTTP " + response.statusCode());
        }
    }
}
