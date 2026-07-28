package dev.tkkr.tkchat.core.model;

import java.util.Objects;

public record Coordinates(int x, int y, int z, String world) {
    public Coordinates {
        world = Objects.requireNonNull(world, "world");
        if (world.isBlank()) {
            throw new IllegalArgumentException("Coordinate world cannot be blank");
        }
    }
}
