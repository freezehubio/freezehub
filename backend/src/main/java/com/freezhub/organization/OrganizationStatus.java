package com.freezhub.organization;

/**
 * Whether an organization has been proven to belong to somebody (FZ-082).
 *
 * <p>Not a billing state and not a lifecycle state — {@code SubscriptionStatus} owns both,
 * and {@code SUSPENDED} lives there. This answers one narrower question: did a real person
 * reach the address that was typed into the public signup form?
 */
public enum OrganizationStatus {

    /**
     * Created by {@code POST /api/signup}, never signed in to. Purged after seven days.
     *
     * <p>It does not restrict anything. An unverified organization cannot be used at all,
     * because using it requires a token, and getting a token requires signing in — which is
     * the act that verifies it. There is no permission to check.
     */
    PENDING_VERIFICATION,

    /** Somebody signed in, or an operator provisioned it after a demo call. */
    ACTIVE

}
