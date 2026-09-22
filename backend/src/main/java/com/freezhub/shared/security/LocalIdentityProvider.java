package com.freezhub.shared.security;

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
 * <p><strong>It remembers addresses, and refuses one it has seen (FZ-082).</strong> Cognito
 * is where global email uniqueness actually lives — {@code users.email} is unique only
 * within an organization, while the pool is shared across all of them — so a fake that
 * happily issues a second subject for the same address would make signup's duplicate path
 * behave differently here than in production. That path is the one that must not be got
 * wrong: it is what stops {@code POST /api/signup} being a customer-enumeration oracle, and
 * a substitute that cannot reproduce it is a substitute that hides the bug.
 *
 * <p>In memory, so it resets with the process. That is correct for a profile whose database
 * is thrown away just as often, and each test gets a clean provider.
 */
@Component
@Profile("local")
public class LocalIdentityProvider implements IdentityProvider {

    private final Map<String, String> subjectsByEmail = new ConcurrentHashMap<>();

    @Override
    public String createUser(String email) {
        String subject = "local-" + UUID.randomUUID();
        String existing = subjectsByEmail.putIfAbsent(email, subject);
        if (existing != null) {
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
