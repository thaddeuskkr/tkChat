package dev.tkkr.tkchat.velocity;

import dev.tkkr.tkchat.velocity.config.ResponseKey;
import dev.tkkr.tkchat.velocity.config.ResponseMessages;
import dev.tkkr.tkchat.velocity.service.ResponseService;

import java.util.Arrays;
import java.util.stream.Collectors;

public final class ResponseTestFixtures {
    private ResponseTestFixtures() {
    }

    public static ResponseService responses() {
        return new ResponseService("", new ResponseMessages(Arrays.stream(ResponseKey.values())
                .collect(Collectors.toMap(ResponseKey::path, ResponseKey::path))));
    }
}
