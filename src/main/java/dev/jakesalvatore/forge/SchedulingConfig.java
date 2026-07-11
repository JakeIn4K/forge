package dev.jakesalvatore.forge;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is app-wide: workers schedule heartbeats and the reaper, and
 * every process schedules the queue-depth metrics refresh. Worker-only tasks
 * stay worker-only via @Profile on their own beans.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {
}
