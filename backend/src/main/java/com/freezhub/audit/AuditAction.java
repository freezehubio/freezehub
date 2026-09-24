package com.freezhub.audit;

/**
 * What was done (FZ-060).
 *
 * <p>A closed set rather than free text, so the trail can be filtered and counted rather
 * than grepped. Named for what happened, in the past tense, because an audit event is a
 * record of something already done — never an intent.
 */
public enum AuditAction {

    RESTRICTION_CREATED,
    /** Carries the fields that changed, before and after (decision {@code D-1}). */
    RESTRICTION_UPDATED,
    RESTRICTION_CANCELLED,
    /** System actor: the lifecycle reconciler, not a person. */
    RESTRICTION_ACTIVATED,
    RESTRICTION_COMPLETED,

    /**
     * A team, application or environment was added, renamed or removed (FZ-072).
     *
     * <p>{@code CATALOG_RENAMED} is the one that earns its place. Because an unrecognised
     * name blocks (decision {@code D-14}), renaming an application turns every pipeline
     * still using the old name into a refusal — a wall of red in the console with, until
     * this existed, nothing anywhere explaining why it started.
     *
     * <p>Which kind of thing it was is {@code resourceType}, so three actions cover nine
     * cases without nine enum values that would only ever be read together.
     */
    CATALOG_CREATED,
    CATALOG_RENAMED,
    CATALOG_DELETED,

    /**
     * An application joined or left a team (FZ-072).
     *
     * <p>Audit-worthy because it silently changes what a team-scoped freeze covers,
     * without anybody touching the freeze.
     */
    APPLICATION_TEAM_ASSIGNED,
    APPLICATION_TEAM_UNASSIGNED,

    API_KEY_ISSUED,
    API_KEY_REVOKED,

    /**
     * Membership (FZ-212). {@code USER_INVITED} existed from the start and nothing recorded it
     * until this story, so the trail could not answer "who let this person in".
     */
    USER_INVITED,
    USER_ROLE_CHANGED,
    USER_DEACTIVATED,
    USER_REACTIVATED,

    ORGANIZATION_SETTINGS_CHANGED,

    /**
     * The organization's commercial state changed (FZ-081).
     *
     * <p>Audited because "why did we get suspended on the 3rd" must be answerable without
     * reading a payment provider's dashboard, and because a suspension changes what the
     * product will let a customer do — which is exactly the kind of thing an administrator
     * later swears nobody did.
     *
     * <p>{@code SUBSCRIPTION_SUSPENDED} carries a system actor: time did it, not a person.
     */
    SUBSCRIPTION_STARTED,
    SUBSCRIPTION_SUSPENDED,

    /**
     * Driven by the payment provider (FZ-084), with a system actor: Stripe did it, not a
     * person here.
     *
     * <p>Audited so that "why did we get downgraded on the 3rd" is answerable without
     * reading someone else's dashboard, and so a change to what a customer is allowed to
     * do has a record on our side of the boundary.
     */
    SUBSCRIPTION_PLAN_CHANGED,
    SUBSCRIPTION_PAYMENT_FAILED,
    SUBSCRIPTION_CANCELLED,

    /**
     * A deployment was refused because it named an application or environment this
     * organization has not registered (decision {@code D-14}).
     *
     * <p>Recorded because refusing it in the moment is only half the answer: a misspelt
     * environment is a way to attempt deploying through a freeze, and one occurrence is a
     * typo while twenty is a pattern. Only refusals are recorded — a normal evaluation
     * happens on every deployment and would drown the trail it belongs to.
     */
    POLICY_BLOCKED_UNREGISTERED
}
