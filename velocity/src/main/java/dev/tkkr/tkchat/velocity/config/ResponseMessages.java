package dev.tkkr.tkchat.velocity.config;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class ResponseMessages {
    private final Map<String, String> templates;

    public ResponseMessages(Map<String, String> templates) {
        this.templates = Map.copyOf(templates);
        Set<String> required = Arrays.stream(ResponseKey.values())
                .map(ResponseKey::path)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> missing = required.stream()
                .filter(path -> !this.templates.containsKey(path))
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "messages.yml is missing responses: " + String.join(", ", missing));
        }
        Set<String> unknown = this.templates.keySet().stream()
                .filter(path -> !required.contains(path))
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "messages.yml contains unknown responses: " + String.join(", ", unknown));
        }
    }

    public String template(ResponseKey key) {
        return templates.get(key.path());
    }
}
