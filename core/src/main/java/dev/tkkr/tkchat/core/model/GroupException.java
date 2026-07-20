package dev.tkkr.tkchat.core.model;

import java.util.Objects;

public final class GroupException extends RuntimeException {
    private final GroupFailure failure;

    public GroupException(GroupFailure failure, String message) {
        super(message);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public GroupException(GroupFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public GroupFailure failure() {
        return failure;
    }
}
