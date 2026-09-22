package com.freezhub.shared.security;

/**
 * Creates the external identity (Cognito) for an invited user and returns its subject
 * identifier, to be stored as users.external_subject. See 06-security.md / FZ-016.
 */
public interface IdentityProvider {

    String createUser(String email);

    /**
     * Removes an identity, by the subject {@link #createUser} returned.
     *
     * <p>Exists for the two places an identity outlives its reason to exist (FZ-082).
     * Creating a Cognito user is not part of the database transaction, so a signup that
     * fails after that call has already happened would otherwise leave an identity behind
     * that can sign in and resolve to no user row. And an organization purged for never
     * being verified must take its identity with it, or the address can never sign up
     * again.
     *
     * <p><strong>Idempotent.</strong> Deleting an identity that is already gone succeeds
     * silently: both callers are cleaning up, and a compensating action that throws because
     * there was nothing to compensate turns a recoverable failure into two.
     */
    void deleteUser(String externalSubject);

}
