package dev.tkkr.tkchat.velocity.config;

import java.util.List;

public record ConfigReloadResult(List<String> restartRequired) {
    public ConfigReloadResult {
        restartRequired = List.copyOf(restartRequired == null ? List.of() : restartRequired);
    }
}
