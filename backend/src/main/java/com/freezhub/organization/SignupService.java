package com.freezhub.organization;

import com.freezhub.audit.AuditActor;
import com.freezhub.shared.security.IdentityAlreadyExistsException;
import com.freezhub.shared.security.IdentityProvider;
import com.freezhub.subscription.SubscriptionService;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates an organization from the public signup form (FZ-082, 11-commercial.md §4).
 *
 * <p>Four things come into existence together: the organization, its first Administrator,
 * that person's Cognito identity, and a fourteen-day trial. Three of them are rows and one
 * of them is not, which is the whole difficulty here.
 *
 * <h2>Nothing is ever revealed</h2>
 *
 * <p>Every call returns normally. An address already in use produces the same silence as a
 * successful signup, because the controller answers {@code 202} either way. Anything else
 * turns the endpoint into a customer-enumeration oracle: try a company's domain, read the
 * difference in the response, and you know whether they use FreezeHub. It is the same
 * reasoning that makes a cross-tenant resource {@code 404} rather than {@code 403}.
 *
 * <p>This is why the duplicate case is neither an exception nor a distinguishable return
 * value. The caller cannot accidentally tell the two apart, because it is handed nothing
 * to tell them apart with.
 *
 * <h2>Cognito is not in the transaction</h2>
 *
 * <p>{@code AdminCreateUser} is a remote call, so it cannot be rolled back with the rows.
 * The order is deliberate:
 *
 * <ol>
 *   <li>create the identity — which is also the global uniqueness check, since
 *       {@code users.email} is unique only within an organization while the pool is shared;
 *   <li>write the rows in one transaction;
 *   <li>if that fails, delete the identity again.
 * </ol>
 *
 * <p>The reverse order would be worse: rows referencing an {@code external_subject} that
 * was never issued, belonging to a user who can never sign in. This way the residue of a
 * failure is nothing, and the residue of a failure <em>during</em> compensation is an
 * identity with no user row — which the JWT converter already rejects with a 401.
 */
@Service
public class SignupService {

    private static final Logger log = LoggerFactory.getLogger(SignupService.class);

    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final SubscriptionService subscriptions;
    private final IdentityProvider identityProvider;
    private final TransactionTemplate transactions;

    public SignupService(OrganizationRepository organizations, UserRepository users,
                         SubscriptionService subscriptions, IdentityProvider identityProvider,
                         PlatformTransactionManager transactionManager) {
        this.organizations = organizations;
        this.users = users;
        this.subscriptions = subscriptions;
        this.identityProvider = identityProvider;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Signs a new company up, or does nothing at all, and says which only to the log.
     *
     * @param companyName what to call the organization
     * @param email the first Administrator's address
     */
    public void signUp(String companyName, String email, Instant now) {
        String externalSubject;
        try {
            externalSubject = identityProvider.createUser(email);
        } catch (IdentityAlreadyExistsException e) {
            // The only outcome that is deliberately indistinguishable from success. Not
            // even a warning: a log filling with these is a signal about the world, not
            // about this application.
            log.info("Signup ignored: an identity already exists for the address given");
            return;
        }

        try {
            create(companyName, email, externalSubject, now);
        } catch (RuntimeException e) {
            // Compensate. deleteUser is idempotent and swallows its own failures, so this
            // cannot turn one problem into two -- see IdentityProvider#deleteUser.
            identityProvider.deleteUser(externalSubject);
            throw e;
        }
    }

    /**
     * The rows, in one transaction.
     *
     * <p>A {@link TransactionTemplate} rather than {@code @Transactional}, because this is
     * called from {@link #signUp} in the same class. Spring applies {@code @Transactional}
     * through a proxy and a self-invocation never passes through one, so the annotation
     * would have been silently ignored and each save would have committed on its own. A
     * half-created organization is the exact thing the compensating delete exists to
     * prevent, so the boundary is written where it cannot be lost.
     */
    private void create(String companyName, String email, String externalSubject, Instant now) {
        transactions.executeWithoutResult(status -> {
            Organization organization =
                    organizations.save(Organization.pendingVerification(companyName));

            users.save(new User(organization.getId(), externalSubject, email, UserRole.ADMINISTRATOR));

            // Every feature for fourteen days (11-commercial.md §4). A trial that hides
            // the audit trail or the deployment console hides the two things worth paying
            // for.
            subscriptions.startTrial(organization.getId(), AuditActor.system(), now);
        });
    }

}
