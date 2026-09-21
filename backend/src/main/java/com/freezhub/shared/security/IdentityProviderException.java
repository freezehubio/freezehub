package com.freezhub.shared.security;

/**
 * The identity could not be created. Thrown instead of letting an SDK exception escape, so
 * callers depend on the port rather than on which provider is behind it (FZ-046).
 *
 * <p>Unchecked and not caught by {@code InviteService}: the transaction must roll back, so a
 * failed invitation leaves no user row rather than one whose {@code external_subject} points
 * at nothing.
 */
public class IdentityProviderException extends RuntimeException {

    public IdentityProviderException(String message, Throwable cause) {
        super(message, cause);
    }

}
