package dev.jakesalvatore.forge.worker;

import dev.jakesalvatore.forge.jobs.Job;
import org.springframework.stereotype.Component;

@Component
public class SleepJobHandler implements JobHandler {

    private static final long MAX_SLEEP_MILLIS = 60_000;

    @Override
    public String type() {
        return "sleep";
    }

    @Override
    public void handle(Job job) throws InterruptedException {
        long millis = job.payload().path("millis").asLong(0);
        if (millis < 0 || millis > MAX_SLEEP_MILLIS) {
            throw new IllegalArgumentException("millis must be between 0 and " + MAX_SLEEP_MILLIS);
        }
        Thread.sleep(millis);
    }
}
