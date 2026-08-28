package com.nimbus.api.user;

import java.time.Instant;

public record UserResponse(Long id, String username, Instant createdAt) {

    public static UserResponse of(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getCreatedAt());
    }
}
