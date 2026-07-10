package dev.jakesalvatore.forge.jobs;

import java.util.UUID;

public class JobNotFoundException extends RuntimeException {

    public JobNotFoundException(UUID id) {
        super("no job with id " + id);
    }
}
