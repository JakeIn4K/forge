package dev.jakesalvatore.forge.worker;

import dev.jakesalvatore.forge.jobs.Job;

/**
 * One implementation per job type. Handlers must be idempotent: delivery is
 * at-least-once, so the same job can run more than once after a crash.
 */
public interface JobHandler {

    String type();

    void handle(Job job) throws Exception;
}
