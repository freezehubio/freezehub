package com.freezhub.organization;

import com.freezhub.audit.AuditRepository;
import com.freezhub.shared.security.IdentityProvider;
import com.freezhub.subscription.SubscriptionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes organizations that signed up and never signed in (FZ-082).
 *
 * <p>Seven days, then the organization, its user and their Cognito identity are gone.
 * {@code POST /api/signup} is unauthenticated, so without this every abandoned signup and
 * every abusive one is permanent — and the identity matters most, because a Cognito user
 * holds the email address against anyone ever signing up with it again.
 *
 * <p><strong>Only ever {@code PENDING_VERIFICATION}.</strong> One sign-in makes an
 * organization {@code ACTIVE} and puts it outside this job's reach forever, whatever
 * happens to its subscription afterwards. Expiry, downgrade and non-payment belong to
 * {@code SubscriptionService} and none of them deletes anything — a trial that runs out
 * becomes {@code FREE} and keeps working ({@code D-33}).
 *
 * <h2>Three tables, and the foreign keys guard the rest</h2>
 *
 * <p>Eleven tables carry an {@code organization_id}. An organization that has never been
 * signed in to can only have rows in three of them — {@code users}, {@code subscription}
 * and {@code audit_event} — because everything else is created through an authenticated
 * endpoint, and authenticating is what ends {@code PENDING_VERIFICATION}.
 *
 * <p>That argument is load-bearing, so it is not left as an argument. This deletes those
 * three and then the organization, in one transaction. If the organization turns out to
 * own anything else, the foreign key refuses the delete, the transaction rolls back
 * whole, and the error is logged. <strong>The database is the safety net</strong>: an
 * unattended job that erases tenants should fail closed when the world does not look the
 * way it expected, and here it does so without anyone having had to predict which table
 * would be the surprise.
 *
 * <p>Deleting {@code audit_event} rows is the one thing here in tension with audit being
 * append-only. It is deliberate and narrow: the trail of an organization being erased goes
 * with it, and nothing else in the product removes audit rows. The general question of
 * erasure is {@code OI-36}.
 */
@Service
public class UnverifiedOrganizationPurge {

    private static final Logger log = LoggerFactory.getLogger(UnverifiedOrganizationPurge.class);

    /** 11-commercial.md §4. Long enough for somebody on holiday, short enough to bound abuse. */
    public static final Duration UNVERIFIED_LIFETIME = Duration.ofDays(7);

    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final SubscriptionRepository subscriptions;
    private final AuditRepository auditEvents;
    private final IdentityProvider identityProvider;
    private final TransactionTemplate transactions;

    public UnverifiedOrganizationPurge(OrganizationRepository organizations, UserRepository users,
                                       SubscriptionRepository subscriptions, AuditRepository auditEvents,
                                       IdentityProvider identityProvider,
                                       PlatformTransactionManager transactionManager) {
        this.organizations = organizations;
        this.users = users;
        this.subscriptions = subscriptions;
        this.auditEvents = auditEvents;
        this.identityProvider = identityProvider;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * @return how many organizations were removed
     */
    public int purge(Instant now) {
        List<Organization> expired =
                organizations.findUnverifiedCreatedBefore(now.minus(UNVERIFIED_LIFETIME));

        int purged = 0;
        for (Organization organization : expired) {
            // One transaction each, rather than one for the sweep. An organization that
            // refuses to be deleted should not roll back the nine that went cleanly, and
            // this job runs unattended — an all-or-nothing sweep that fails on the same
            // row every hour never makes progress, and nobody is watching it not make any.
            try {
                purgeOne(organization);
                purged++;
            } catch (RuntimeException e) {
                log.error("Could not purge unverified organization {}; leaving it in place",
                        organization.getId(), e);
            }
        }

        if (purged > 0) {
            log.info("Purged {} organization(s) unverified for more than {} days",
                    purged, UNVERIFIED_LIFETIME.toDays());
        }
        return purged;
    }

    /**
     * <p><strong>Rows first, identities after the commit.</strong> The identity delete is a
     * remote call that no transaction can undo, so doing it first means a failed delete of
     * the rows leaves users who exist and can never sign in — and the purge then retries
     * that same broken state every hour. This way the worst case is an identity with no
     * user row, which the JWT converter already rejects, and which is visible in the log.
     *
     * <p>A {@link TransactionTemplate} rather than {@code @Transactional}, because
     * {@link #purge} calls this from the same class. Spring applies {@code @Transactional}
     * through a proxy and a self-invocation never reaches one, so the annotation would be
     * silently ignored and every delete would commit on its own.
     */
    private void purgeOne(Organization organization) {
        List<String> identities = new ArrayList<>();

        transactions.executeWithoutResult(status -> {
            List<User> members = users.findAllByOrganizationId(organization.getId());
            members.forEach(user -> identities.add(user.getExternalSubject()));

            auditEvents.deleteByOrganizationId(organization.getId());
            subscriptions.deleteByOrganizationId(organization.getId());
            users.deleteAll(members);
            organizations.delete(organization);
        });

        // Past this line the rows are gone and committed. deleteUser swallows and logs its
        // own failures, so a pool that is having a bad day cannot undo a completed purge.
        identities.forEach(identityProvider::deleteUser);
    }

}
