package dev.tkkr.tkchat.velocity.state;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationTrackerTest {
    @Test
    void outgoingConversationUpdatesOnlyTheSenderUntilTheMessageIsAccepted() {
        ConversationTracker tracker = new ConversationTracker();
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();

        tracker.recordOutgoing(sender, recipient);

        assertEquals(recipient, tracker.partner(sender).orElseThrow());
        assertTrue(tracker.partner(recipient).isEmpty());

        tracker.recordIncoming(recipient, sender);

        assertEquals(sender, tracker.partner(recipient).orElseThrow());
    }

    @Test
    void anOlderCompletionCannotOverwriteTheSendersNewerTarget() {
        ConversationTracker tracker = new ConversationTracker();
        UUID sender = UUID.randomUUID();
        UUID firstRecipient = UUID.randomUUID();
        UUID secondRecipient = UUID.randomUUID();

        tracker.recordOutgoing(sender, firstRecipient);
        tracker.recordOutgoing(sender, secondRecipient);
        tracker.recordIncoming(firstRecipient, sender);

        assertEquals(secondRecipient, tracker.partner(sender).orElseThrow());
    }
}
