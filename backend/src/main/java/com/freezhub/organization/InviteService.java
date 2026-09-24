package com.freezhub.organization;

import com.freezhub.audit.AuditAction;
import com.freezhub.audit.AuditActor;
import com.freezhub.audit.AuditDetails;
import com.freezhub.audit.AuditResourceType;
import com.freezhub.audit.AuditTrail;
import com.freezhub.shared.security.IdentityAlreadyExistsException;
import com.freezhub.shared.security.IdentityProvider;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Adds a person to an organization (FZ-016), audited and explained since FZ-212.
 *
 * <p>Creates their Cognito login, which emails them a temporary password, then their row.
 * Cognito is not in the transaction, so a failure after the login exists deletes it again —
 * otherwise the address would be held against the next invitation for good, the defect
 * FZ-082 fixed for signup and FZ-211 made possible in production.
 */
@Service
public class InviteService {

    private final UserRepository userRepository;
    private final IdentityProvider identityProvider;
    private final AuditTrail auditTrail;

    public InviteService(UserRepository userRepository, IdentityProvider identityProvider,
                         AuditTrail auditTrail) {
        this.userRepository = userRepository;
        this.identityProvider = identityProvider;
        this.auditTrail = auditTrail;
    }

    @Transactional
    public User invite(Long organizationId, AuditActor actor, String email, UserRole requestedRole) {
        Optional<User> existing = userRepository.findAllByEmail(email).stream()
                .filter(user -> user.getOrganizationId().equals(organizationId))
                .findFirst();
        if (existing.isPresent()) {
            // Said differently for a removed member, because the answer is different: they
            // are not lost, they are one click away.
            throw new ResponseStatusException(HttpStatus.CONFLICT, existing.get().isActive()
                    ? "This person is already a member of the organization."
                    : "This person was removed from the organization. Reactivate them instead.");
        }

        UserRole role = requestedRole != null ? requestedRole : UserRole.MEMBER;

        String externalSubject;
        try {
            externalSubject = identityProvider.createUser(email);
        } catch (IdentityAlreadyExistsException taken) {
            // Cognito holds one login per address across every organization, and one login
            // resolves to one user row. So an address already in use elsewhere cannot join a
            // second organization — a 409 that says so, not the 500 this used to be.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This address already has a FreezeHub account in another organization. "
                            + "A person can belong to one organization.");
        }

        User invited;
        try {
            // Flushed here, not at commit, so a constraint violation surfaces inside this
            // method where the login can still be deleted.
            invited = userRepository.saveAndFlush(new User(organizationId, externalSubject, email, role));
        } catch (RuntimeException failed) {
            identityProvider.deleteUser(externalSubject);
            throw failed;
        }

        // Recorded since FZ-212. The action existed from the start and nothing wrote it, so
        // the trail could not answer "who let this person in, and as what".
        auditTrail.record(organizationId, actor, AuditAction.USER_INVITED, AuditResourceType.USER,
                invited.getId(), AuditDetails.builder()
                        .with("email", email)
                        .with("role", role)
                        .toJson());
        return invited;
    }

}
