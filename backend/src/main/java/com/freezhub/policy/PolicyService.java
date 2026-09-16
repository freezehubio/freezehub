package com.freezhub.policy;

import com.freezhub.audit.AuditAction;
import com.freezhub.audit.AuditActor;
import com.freezhub.audit.AuditDetails;
import com.freezhub.audit.AuditResourceType;
import com.freezhub.audit.AuditTrail;
import com.freezhub.catalog.Application;
import com.freezhub.deployment.BlockedReason;
import com.freezhub.deployment.DeploymentCheckRecorder;
import com.freezhub.deployment.DeploymentCheckRecorder.CheckMetadata;
import com.freezhub.catalog.ApplicationRepository;
import com.freezhub.catalog.Environment;
import com.freezhub.catalog.EnvironmentRepository;
import com.freezhub.catalog.TeamApplication;
import com.freezhub.catalog.TeamApplicationRepository;
import com.freezhub.policy.PolicyEvaluationResponse.MatchedRestriction;
import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.ChangeRestrictionRepository;
import com.freezhub.restriction.RestrictionLevel;
import com.freezhub.subscription.SubscriptionService;
import com.freezhub.restriction.RestrictionStatus;
import com.freezhub.shared.security.ApiKeyPrincipal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers whether a deployment is currently allowed (FZ-051).
 *
 * <p>This is the enforcement boundary — the reason FreezeHub is a service rather than a
 * wiki page — so two properties matter more than anything else here:
 *
 * <ul>
 *   <li><strong>"In force" comes from the timestamps, never from {@code status}</strong>,
 *       which a reconciler maintains on an interval and which therefore lags (FZ-025).
 *       The query enforces this; see {@code findInForce}.</li>
 *   <li><strong>An unrecognised name blocks.</strong> Anything else leaves a bypass: a
 *       misspelt environment matches no scope list, so evaluating it normally would tend
 *       toward {@code ALLOW} and hand back a legitimate-looking permission to deploy in
 *       the middle of a freeze.</li>
 * </ul>
 */
@Service
public class PolicyService {

    private final ChangeRestrictionRepository changeRestrictionRepository;
    private final ApplicationRepository applicationRepository;
    private final EnvironmentRepository environmentRepository;
    private final TeamApplicationRepository teamApplicationRepository;
    private final AuditTrail auditTrail;
    private final PolicyMetrics metrics;
    private final DeploymentCheckRecorder deploymentChecks;
    private final SubscriptionService subscriptions;

    public PolicyService(ChangeRestrictionRepository changeRestrictionRepository,
                         ApplicationRepository applicationRepository,
                         EnvironmentRepository environmentRepository,
                         TeamApplicationRepository teamApplicationRepository,
                         AuditTrail auditTrail,
                         PolicyMetrics metrics,
                         DeploymentCheckRecorder deploymentChecks,
                         SubscriptionService subscriptions) {
        this.changeRestrictionRepository = changeRestrictionRepository;
        this.applicationRepository = applicationRepository;
        this.environmentRepository = environmentRepository;
        this.teamApplicationRepository = teamApplicationRepository;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.deploymentChecks = deploymentChecks;
        this.subscriptions = subscriptions;
    }

    /**
     * The rules, and nothing else (`FZ-120`).
     *
     * <p>Read-only and side-effect free: no audit entry, no metric, no recorded check. Both
     * the machine endpoint and the person-facing preview call this, so there is one
     * implementation of what "blocked" means rather than two that drift.
     *
     * <p>{@code readOnly} applies when a controller calls this through the proxy. Called
     * from {@link #evaluate} it is a self-invocation, so that transaction's settings
     * stand — which is what the machine path needs, since it goes on to write. Do not
     * "fix" that by routing it through an injected self-reference: it would make the
     * audit entry and the recorded check unwritable.
     */
    @Transactional(readOnly = true)
    public PolicyOutcome decide(Long organizationId, String applicationName,
                                String environmentName, Instant now) {
        // Exact names. A near miss is a miss: the catalog's uniqueness is case-sensitive,
        // so treating "Prod" as "prod" here would make this disagree with the registry it
        // is reading from.
        Optional<Application> application =
                applicationRepository.findByOrganizationIdAndName(organizationId, applicationName);
        Optional<Environment> environment =
                environmentRepository.findByOrganizationIdAndName(organizationId, environmentName);

        List<ScopeDimension> unregistered = new ArrayList<>();
        if (application.isEmpty()) {
            unregistered.add(ScopeDimension.APPLICATION);
        }
        if (environment.isEmpty()) {
            unregistered.add(ScopeDimension.ENVIRONMENT);
        }
        if (!unregistered.isEmpty()) {
            return new PolicyOutcome(unregistered, List.of());
        }

        Set<Long> applicationTeamIds = teamApplicationRepository
                .findAllByApplicationId(application.get().getId()).stream()
                .map(TeamApplication::getTeamId)
                .collect(Collectors.toSet());

        return new PolicyOutcome(List.of(), matching(
                organizationId, now, application.get().getId(), environment.get().getId(),
                applicationTeamIds));
    }

    /**
     * Not {@code readOnly}: a refusal caused by an unregistered name writes an audit
     * entry (FZ-060), and it has to commit with the decision that produced it.
     */
    @Transactional
    public PolicyEvaluationResponse evaluate(Long organizationId, ApiKeyPrincipal caller,
                                             PolicyEvaluationRequest request, Instant now) {
        PolicyOutcome outcome =
                decide(organizationId, request.application(), request.environment(), now);

        if (outcome.isUnregistered()) {
            auditTrail.record(organizationId, AuditActor.of(caller),
                    AuditAction.POLICY_BLOCKED_UNREGISTERED, AuditResourceType.POLICY, null,
                    AuditDetails.builder()
                            .with("application", request.application())
                            .with("environment", request.environment())
                            .with("unregistered", outcome.unregistered().toString())
                            .toJson());

            metrics.blockedUnregistered();
            deploymentChecks.record(caller, request.application(), request.environment(),
                    PolicyDecision.BLOCK, BlockedReason.UNREGISTERED, List.of(), metadataOf(request));
            return blockUnregistered(request, now, outcome);
        }

        List<ChangeRestriction> matched = outcome.matched();
        boolean blocked = outcome.blocked();

        if (blocked) {
            metrics.blockedByRestriction();
        } else {
            metrics.allowed();
        }

        // Every evaluation, not only the refusals FZ-060 records: a console showing only
        // what was refused cannot answer "did my deployment get through?", which is the
        // question the team asking has (FZ-070).
        deploymentChecks.record(caller, request.application(), request.environment(),
                blocked ? PolicyDecision.BLOCK : PolicyDecision.ALLOW,
                blocked ? BlockedReason.RESTRICTION : null,
                matched.stream()
                        // Qualified: PolicyEvaluationResponse has its own MatchedRestriction,
                        // and the two are different things — one is the answer, one is the record.
                        .map(restriction -> new DeploymentCheckRecorder.MatchedRestriction(
                                restriction.getId(), restriction.getName(), restriction.getLevel().name()))
                        .toList(),
                metadataOf(request));

        return new PolicyEvaluationResponse(
                blocked ? PolicyDecision.BLOCK : PolicyDecision.ALLOW,
                request.action(),
                request.application(),
                request.environment(),
                now,
                explain(outcome, request.application(), request.environment(),
                        advisoryOnFreePlan(organizationId, outcome)),
                List.of(),
                matched.stream().map(MatchedRestriction::from).toList());
    }

    /**
     * The same question, asked by a person from inside the product (`FZ-120`).
     *
     * <p><strong>Records nothing.</strong> No {@code deployment_check}, no audit entry, no
     * metric. The checks console says it lists every time a pipeline asked, and the
     * per-restriction refusal counts on the dashboard and on a restriction's page are read
     * from those same rows — filling them with people trying the form would make that
     * sentence false and every one of those figures wrong. Somebody asking is not a
     * deployment.
     *
     * <p>The decision comes from {@link #decide}, which is also where the machine
     * endpoint's comes from: one implementation of the matching rules, not two that drift.
     */
    @Transactional(readOnly = true)
    public PolicyPreviewResponse preview(Long organizationId, String applicationName,
                                         String environmentName, Instant now) {
        PolicyOutcome outcome = decide(organizationId, applicationName, environmentName, now);

        return new PolicyPreviewResponse(
                outcome.blocked() ? PolicyDecision.BLOCK : PolicyDecision.ALLOW,
                applicationName,
                environmentName,
                now,
                explain(outcome, applicationName, environmentName,
                        advisoryOnFreePlan(organizationId, outcome)),
                outcome.unregistered(),
                outcome.matched().stream().map(MatchedRestriction::from).toList());
    }

    /** Blank is the same as absent: an unset CI variable arrives as an empty string. */
    private CheckMetadata metadataOf(PolicyEvaluationRequest request) {
        return new CheckMetadata(
                blankToNull(request.actor()),
                blankToNull(request.reference()),
                blankToNull(request.source()));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private List<ChangeRestriction> matching(Long organizationId, Instant now, Long applicationId,
                                             Long environmentId, Set<Long> applicationTeamIds) {
        List<ChangeRestriction> inForce = changeRestrictionRepository.findInForce(
                organizationId, now, RestrictionStatus.CANCELLED);

        // Explicit rather than left to lazy loading inside the transaction, so the fetch
        // stays deliberate. The collections are batch-fetched, so this is three queries
        // for the whole candidate set rather than three per restriction.
        inForce.forEach(restriction -> {
            Hibernate.initialize(restriction.getTeamIds());
            Hibernate.initialize(restriction.getApplicationIds());
            Hibernate.initialize(restriction.getEnvironmentIds());
        });

        return inForce.stream()
                .filter(restriction -> restriction.covers(applicationId, environmentId, applicationTeamIds))
                .toList();
    }

    /**
     * The decision for a deployment naming something FreezeHub does not know about.
     *
     * <p>Blocked outright, and it says which name it did not recognise — a refusal a
     * pipeline cannot act on is barely better than no refusal. The accepted cost is that
     * FreezeHub becomes a gate on catalog completeness: an application nobody has
     * registered cannot deploy at all, including when no freeze exists.
     *
     * <p>Returned as a {@code 200} carrying {@code BLOCK}, deliberately, rather than as a
     * {@code 4xx}. An error status lands in the pipeline's error branch, which is exactly
     * where `04-api.md` tells clients to choose fail-open or fail-closed for themselves —
     * so a fail-open pipeline would quietly convert this block back into a deployment.
     * A decision cannot be configured away.
     */
    private PolicyEvaluationResponse blockUnregistered(PolicyEvaluationRequest request, Instant now,
                                                       PolicyOutcome outcome) {
        return new PolicyEvaluationResponse(
                PolicyDecision.BLOCK,
                request.action(),
                request.application(),
                request.environment(),
                now,
                explain(outcome, request.application(), request.environment(), false),
                outcome.unregistered(),
                List.of());
    }

    /**
     * Whether this answer is an advisory that a paid plan would have blocked (FZ-145, D-33).
     *
     * <p><strong>Short-circuits before the query.</strong> The usual answer is that nothing
     * matched, and the Policy API is the one endpoint that must not get slower — so the
     * subscription is read only when a restriction actually matched and the deployment was
     * allowed anyway. A free organization that is blocked has a hard freeze surviving from
     * its trial ({@code D-22}) and wants no upsell.
     */
    private boolean advisoryOnFreePlan(Long organizationId, PolicyOutcome outcome) {
        if (outcome.matched().isEmpty() || outcome.blocked()) {
            return false;
        }
        return !subscriptions.of(organizationId).getPlan().hardFreeze();
    }

    /**
     * The line worth printing in a build log — and the line the product shows a person.
     *
     * <p>One implementation, for the same reason the decision has one: a preview that
     * agreed with the pipeline on ALLOW or BLOCK but described it differently would still
     * be two answers to the same question.
     */
    private String explain(PolicyOutcome outcome, String applicationName, String environmentName,
                           boolean advisoryOnFreePlan) {
        if (outcome.isUnregistered()) {
            String detail = outcome.unregistered().stream()
                    .map(dimension -> dimension == ScopeDimension.APPLICATION
                            ? "application '" + applicationName + "'"
                            : "environment '" + environmentName + "'")
                    .collect(Collectors.joining(" and "));

            return "Blocked: no " + detail + " is registered in this organization, so this deployment "
                    + "cannot be evaluated against the restrictions that may apply to it.";
        }

        List<ChangeRestriction> matched = outcome.matched();

        if (matched.isEmpty()) {
            return "Allowed: no restriction is in force for this deployment.";
        }

        if (outcome.blocked()) {
            String names = matched.stream()
                    .filter(restriction -> restriction.getLevel() == RestrictionLevel.HARD_FREEZE)
                    .map(ChangeRestriction::getName)
                    .collect(Collectors.joining(", "));
            return "Blocked by a change restriction in force: " + names + ".";
        }

        String advisory = "Allowed, but " + matched.size() + " advisory restriction"
                + (matched.size() == 1 ? " is" : "s are") + " in force for this deployment.";

        // The whole free-to-paid pitch, delivered by freeze-check.sh printing this verbatim
        // in the engineer's own build log (D-33). Nothing above it is dressed up to make the
        // point: the decision is ALLOW and the advisory is still an advisory.
        return advisoryOnFreePlan
                ? advisory + " Your FREE plan announces freezes; it does not block them."
                        + " This deployment would be refused on a paid plan."
                : advisory;
    }

}
