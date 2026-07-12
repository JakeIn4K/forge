package dev.jakesalvatore.forge.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

@ConfigurationProperties(prefix = "forge.api")
public record ApiProperties(
        Set<String> keys
) {
}
