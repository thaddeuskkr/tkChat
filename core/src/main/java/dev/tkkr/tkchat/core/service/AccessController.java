package dev.tkkr.tkchat.core.service;

import dev.tkkr.tkchat.core.model.AccessDecision;
import dev.tkkr.tkchat.core.model.SenderContext;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface AccessController {
    CompletionStage<AccessDecision> authorize(
            SenderContext sender,
            String permission,
            String bypassPermission,
            boolean containsLink
    );
}
