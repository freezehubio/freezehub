package com.freezhub.organization;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Start free trial" (FZ-082, 11-commercial.md §4).
 *
 * <p>Unauthenticated by necessity — the person filling it in has no account, which is the
 * point — and rate limited by {@code FZ-087}, which already names {@code /api/signup} in
 * its default path list.
 *
 * <p>Free-mail addresses are accepted. Blocking {@code gmail.com} is standard B2B practice
 * and would be wrong here: a two-person startup evaluating a freeze tool is exactly the
 * customer self-serve exists for, and they have not set up a domain yet. The cost is more
 * junk signups, which the seven-day purge bounds.
 */
@RestController
@RequestMapping("/api/signup")
public class SignupController {

    private final SignupService signups;

    public SignupController(SignupService signups) {
        this.signups = signups;
    }

    /**
     * Signs a company up, or does nothing, and answers identically either way.
     *
     * <p><strong>The response never varies.</strong> Same status, same body, whether the
     * organization was created or the address was already in use. Anything else makes this
     * a customer-enumeration oracle — try a company's domain, read the difference, and you
     * know whether they use FreezeHub. It is the reasoning that makes a cross-tenant
     * resource {@code 404} rather than {@code 403} ({@code 04-api.md}): existence is never
     * revealed to someone not entitled to know it.
     *
     * <p>{@code 202} rather than {@code 201} for the same reason. {@code 201} would have to
     * carry a {@code Location}, and a resource the caller cannot fetch — and whose very
     * existence is the secret — has no honest URL to name.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Acknowledgement signUp(@Valid @RequestBody SignupBody body) {
        signups.signUp(body.company().trim(), body.email().trim(), Instant.now());
        return new Acknowledgement("Check your email for a temporary password.");
    }

    /**
     * Lengths are capped on every field, because this is reachable without a credential.
     *
     * <p>No name field: the first Administrator's display name is not stored anywhere today
     * ({@code users} has an email and a role), so asking for one would be collecting
     * personal data with no purpose — which {@code 17-data-protection.md} treats as the
     * thing to avoid, not a nicety.
     */
    public record SignupBody(
            @NotBlank @Size(max = 255) String company,
            @NotBlank @Email @Size(max = 320) String email
    ) {
    }

    /** Identical for every outcome. It describes what the caller should do, not what happened. */
    public record Acknowledgement(String message) {
    }
}
