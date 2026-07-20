package dev.tkkr.tkchat.core.model;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record GroupLeaveResult(Group group, Set<UUID> affectedMembers, boolean deleted) {
    public GroupLeaveResult {
        group = Objects.requireNonNull(group, "group");
        affectedMembers = Set.copyOf(affectedMembers);
    }
}
