package dev.jakesalvatore.forge.worker;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * One stable id per worker process (hostname:pid). Claims are stamped with it
 * and heartbeats are published under it, so "who owns this job" and "is that
 * owner alive" refer to the same thing: the process.
 */
@Component
@Profile("worker")
public class WorkerIdentity {

    private final String id;

    public WorkerIdentity() {
        this.id = resolve();
    }

    public String id() {
        return id;
    }

    private static String resolve() {
        try {
            return InetAddress.getLocalHost().getHostName() + ":" + ProcessHandle.current().pid();
        } catch (UnknownHostException e) {
            return "worker:" + ProcessHandle.current().pid();
        }
    }
}
