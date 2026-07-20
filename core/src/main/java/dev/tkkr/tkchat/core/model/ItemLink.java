package dev.tkkr.tkchat.core.model;

import java.util.Objects;

public record ItemLink(String itemId, int amount, String displayName) {
    public ItemLink {
        itemId = Objects.requireNonNull(itemId, "itemId");
        displayName = Objects.requireNonNull(displayName, "displayName");
        if (itemId.isBlank() || amount < 1) {
            throw new IllegalArgumentException("Item links require an item identifier and positive amount");
        }
    }
}
