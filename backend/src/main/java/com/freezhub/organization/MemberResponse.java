package com.freezhub.organization;

import java.time.Instant;

/**
 * One member, as an administrator sees them (FZ-212).
 *
 * <p>No external subject: it is Cognito's identifier for the person and nothing the screen
 * needs. {@code active} is carried explicitly rather than left for the client to infer from a
 * null, because it is the thing the screen is about.
 */
public record MemberResponse(
        Long id,
        String email,
        UserRole role,
        boolean active,
        Instant deactivatedAt,
        Instant createdAt) {

    static MemberResponse from(User user) {
        return new MemberResponse(user.getId(), user.getEmail(), user.getRole(), user.isActive(),
                user.getDeactivatedAt(), user.getCreatedAt());
    }
}
