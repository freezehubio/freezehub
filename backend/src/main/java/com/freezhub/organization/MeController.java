package com.freezhub.organization;

import com.freezhub.shared.security.AuthenticatedUser;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    private static final Logger log = LoggerFactory.getLogger(MeController.class);

    private final OrganizationRepository organizations;

    public MeController(OrganizationRepository organizations) {
        this.organizations = organizations;
    }

    /**
     * Who the caller is — and, the first time, the act that verifies their organization.
     *
     * <p><strong>This is where {@code PENDING_VERIFICATION} ends (FZ-082).</strong> Signup
     * creates an organization from an address nobody has proven they can read; signing in
     * with the temporary password Cognito emailed there is the proof. The SPA calls this
     * immediately after authenticating, so the first successful call is the first
     * successful sign-in.
     *
     * <p>Not in {@code UserResolvingJwtAuthenticationConverter}, which would be the obvious
     * place: it runs inside the security filter chain on <em>every</em> request, outside a
     * transaction, and a write there would be a write on every call the product ever
     * serves. Here it is one conditional statement that matches no rows after the first
     * time.
     *
     * <p>The write is deliberately not allowed to break the read. An organization that
     * stays {@code PENDING_VERIFICATION} for longer than it should is purged after seven
     * days at worst; a sign-in that fails because a status update failed locks a paying
     * customer out of a working account.
     */
    @GetMapping("/api/me")
    @Transactional
    public AuthenticatedUser me(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        try {
            if (organizations.markVerified(authenticatedUser.organizationId(), Instant.now()) > 0) {
                log.info("Organization {} verified by first sign-in", authenticatedUser.organizationId());
            }
        } catch (RuntimeException e) {
            log.error("Could not mark organization {} verified", authenticatedUser.organizationId(), e);
        }
        return authenticatedUser;
    }

}
