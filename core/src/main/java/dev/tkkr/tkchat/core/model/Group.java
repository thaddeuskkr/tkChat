package dev.tkkr.tkchat.core.model;

import java.util.Objects;
import java.util.UUID;

public record Group(
        UUID id,
        String name,
        String normalizedName,
        UUID ownerId,
        GroupVisibility visibility,
        boolean passwordProtected
) {
    public Group {
        id = Objects.requireNonNull(id, "id");
        name = Objects.requireNonNull(name, "name");
        normalizedName = Objects.requireNonNull(normalizedName, "normalizedName");
        ownerId = Objects.requireNonNull(ownerId, "ownerId");
        visibility = Objects.requireNonNull(visibility, "visibility");
    }
}
