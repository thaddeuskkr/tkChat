package dev.tkkr.tkchat.core.model;

import java.util.Objects;

public sealed interface RouteDecision permits RouteDecision.Approved, RouteDecision.Denied {
    record Approved(ApprovedMessage message) implements RouteDecision {
        public Approved {
            message = Objects.requireNonNull(message, "message");
        }
    }

    record Denied(DenialReason reason, String detail) implements RouteDecision {
        public Denied {
            reason = Objects.requireNonNull(reason, "reason");
            detail = detail == null ? "" : detail;
        }
    }
}
