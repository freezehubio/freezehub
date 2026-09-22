package com.freezhub.shared.security;

/**
 * An identity already exists for this email address (FZ-082).
 *
 * <p>A subtype rather than a flag because signup and invitation want opposite things from
 * it. Invitation treats it as a failure and lets it escape. Signup must treat it as a
 * <em>silent</em> outcome: {@code POST /api/signup} answers {@code 202} whether or not the
 * address was already in use, since anything else turns the endpoint into a
 * customer-enumeration oracle.
 *
 * <p>Extending {@link IdentityProviderException} keeps every existing catch site correct.
 *
 * <p>This is also the only global uniqueness check in the product: {@code users.email} is
 * unique per organization ({@code uq_users_organization_email}), while the Cognito pool is
 * shared across all of them. The pool is therefore what knows whether an address has been
 * seen before, and asking it is cheaper and more honest than a second index that could
 * disagree with it.
 */
public class IdentityAlreadyExistsException extends IdentityProviderException {

    public IdentityAlreadyExistsException(String message, Throwable cause) {
        super(message, cause);
    }

}
