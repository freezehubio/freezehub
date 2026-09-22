package com.freezhub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.freezhub.ContainersConfig;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The local substitute refuses an address that already exists (FZ-195, closes `OI-49`).
 *
 * <p>An integration test rather than a unit one, because the whole point is that it reads
 * the database — a mocked repository would assert the mock, not the behaviour.
 */
@SpringBootTest
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class LocalIdentityProviderTest {

    @Autowired
    private LocalIdentityProvider identityProvider;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private UserRepository users;

    private static String uniqueEmail() {
        return "person-" + UUID.randomUUID() + "@acme.test";
    }

    @Test
    void issuesASubjectForAnAddressNobodyHasUsed() {
        assertThat(identityProvider.createUser(uniqueEmail())).startsWith("local-");
    }

    @Test
    void refusesTheSameAddressTwiceInOneProcess() {
        String email = uniqueEmail();
        identityProvider.createUser(email);

        assertThatThrownBy(() -> identityProvider.createUser(email))
                .isInstanceOf(IdentityAlreadyExistsException.class);
    }

    /**
     * `OI-49` exactly: a row written by a seed script, a provisioning run or an earlier
     * process. A persistent Cognito pool would still hold that address; before this, a
     * restarted fake did not, and signup produced a second organization for it.
     */
    @Test
    void refusesAnAddressThisProcessNeverIssuedButTheDatabaseHolds() {
        String email = uniqueEmail();
        Organization organization = organizations.saveAndFlush(new Organization("Seeded Ltd"));
        users.saveAndFlush(new User(organization.getId(), "some-earlier-subject", email,
                UserRole.ADMINISTRATOR));

        assertThatThrownBy(() -> identityProvider.createUser(email))
                .isInstanceOf(IdentityAlreadyExistsException.class);
    }

    /** Deleting releases the address, which is what makes the signup purge work. */
    @Test
    void releasesAnAddressWhenTheIdentityIsDeleted() {
        String email = uniqueEmail();
        String subject = identityProvider.createUser(email);

        identityProvider.deleteUser(subject);

        assertThatCode(() -> identityProvider.createUser(email)).doesNotThrowAnyException();
    }

    /** Both callers are compensating; a cleanup that throws turns one problem into two. */
    @Test
    void deletingAnIdentityThatIsAlreadyGoneIsSilent() {
        assertThatCode(() -> identityProvider.deleteUser("local-never-existed"))
                .doesNotThrowAnyException();
    }

}
