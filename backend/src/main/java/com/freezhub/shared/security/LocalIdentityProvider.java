package com.freezhub.shared.security;

import com.freezhub.organization.UserRepository;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local/test substitute for IdentityProvider: generates a fake subject instead of calling
 * AWS. {@link CognitoIdentityProvider} is the real implementation, active in every other
 * profile (FZ-046).
 *
 * <h2>It refuses an address that has been seen before, and looks in two places</h2>
 *
 * <p>Cognito is where global email uniqueness actually lives: {@code users.email} is unique
 * only within an organization, while the pool is shared across all of them. Signup depends
 * on that (FZ-082) — the duplicate case is {@code AdminCreateUser} refusing, and it is what
 * stops {@code POST /api/signup} being a customer-enumeration oracle.
 *
 * <p>A fake that only remembers this process gets that wrong, and {@code OI-49} is what
 * happened: an organization seeded by {@code seed-demo.sh} held an address, this provider
 * had never heard of it because it had restarted since, and signing up with that address
 * produced a <em>second</em> organization. A real pool persists; a real pool would have
 * refused.
 *
 * <p>So both are consulted:
 *
 * <ul>
 *   <li><strong>The database</strong>, because a row written by a seed script, a
 *       provisioning run or an earlier process is exactly what a persistent pool would
 *       still know about.
 *   <li><strong>An in-process map</strong>, because during signup the identity is created
 *       <em>before</em> the user row exists. Without it, two signups for one address in
 *       quick succession would both be allowed.
 * </ul>
 *
 * <p>Neither is a security boundary and neither needs to be: this bean exists only under
 * the {@code local} profile. The point is fidelity — the duplicate path is the one that
 * must not be wrong, and a substitute that cannot reproduce it is a substitute that hides
 * the bug.
 */
@Component
@Profile("local")
public class LocalIdentityProvider implements IdentityProvider {

    private final UserRepository users;
    private final Map<String, String> subjectsByEmail = new ConcurrentHashMap<>();

    public LocalIdentityProvider(UserRepository users) {
        this.users = users;
    }

    @Override
    public String createUser(String email) {
        if (!users.findAllByEmail(email).isEmpty()) {
            throw new IdentityAlreadyExistsException(
                    "An identity already exists for this email address", null);
        }

        String subject = "local-" + UUID.randomUUID();
        if (subjectsByEmail.putIfAbsent(email, subject) != null) {
            throw new IdentityAlreadyExistsException(
                    "An identity already exists for this email address", null);
        }
        return subject;
    }

    @Override
    public void deleteUser(String externalSubject) {
        subjectsByEmail.values().remove(externalSubject);
    }

}
