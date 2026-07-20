package dev.tkkr.tkchat.core.model;

import java.util.Objects;
import java.util.UUID;

public record GroupMembership(Group group, UUID memberId, GroupRole role) {
    public GroupMembership {
        group = Objects.requireNonNull(group, "group");
        memberId = Objects.requireNonNull(memberId, "memberId");
        role = Objects.requireNonNull(role, "role");
    }
}
