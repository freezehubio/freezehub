package com.freezhub.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.catalog.Environment;
import com.freezhub.catalog.EnvironmentRepository;
import com.freezhub.shared.security.TestTokens;
import com.freezhub.subscription.Subscription;
import com.freezhub.subscription.SubscriptionRepository;
import com.freezhub.subscription.SubscriptionStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Self-serve signup, end to end (FZ-082).
 *
 * <p>Addresses are unique per test. {@code LocalIdentityProvider} holds them for the life
 * of the application context, exactly as the Cognito pool holds them for the life of the
 * pool, so a fixed address would make one test's signup depend on whether another ran
 * first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class SignupTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private UserRepository users;

    @Autowired
    private SubscriptionRepository subscriptions;

    @Autowired
    private EnvironmentRepository environments;

    @Autowired
    private UnverifiedOrganizationPurge purge;

    private static String uniqueEmail() {
        return "founder-" + UUID.randomUUID() + "@northwind.test";
    }

    private ResultActions signUp(String company, String email) throws Exception {
        return mockMvc.perform(post("/api/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"company\":\"" + company + "\",\"email\":\"" + email + "\"}"));
    }

    private User userFor(String email) {
        List<User> found = users.findAllByEmail(email);
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    @Test
    void aNewCompanySignsUpAndLandsInAFourteenDayTrial() throws Exception {
        String email = uniqueEmail();
        Instant before = Instant.now();

        signUp("Northwind", email).andExpect(status().isAccepted());

        User admin = userFor(email);
        assertThat(admin.getRole()).isEqualTo(UserRole.ADMINISTRATOR);
        assertThat(admin.getExternalSubject()).isNotBlank();

        Organization organization = organizations.findById(admin.getOrganizationId()).orElseThrow();
        assertThat(organization.getName()).isEqualTo("Northwind");

        Subscription subscription =
                subscriptions.findByOrganizationId(organization.getId()).orElseThrow();
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(subscription.getTrialEndsAt())
                .isBetween(before.plus(Duration.ofDays(14)).minusSeconds(60),
                        Instant.now().plus(Duration.ofDays(14)).plusSeconds(60));
    }

    /**
     * The acceptance criterion, and the reason the endpoint is shaped the way it is: an
     * address already in use must be indistinguishable from a new one. Status and body are
     * both compared, because a difference in either is an enumeration oracle.
     */
    @Test
    void aDuplicateAddressAnswersIdenticallyAndCreatesNothing() throws Exception {
        String email = uniqueEmail();

        String first = signUp("Northwind", email)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String second = signUp("Somebody Else Ltd", email)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(users.findAllByEmail(email)).hasSize(1);
        assertThat(organizations.findAll())
                .extracting(Organization::getName)
                .doesNotContain("Somebody Else Ltd");
    }

    @Test
    void freeMailAddressesAreAccepted() throws Exception {
        String email = "two.person.startup-" + UUID.randomUUID() + "@gmail.com";

        signUp("Two Person Startup", email).andExpect(status().isAccepted());

        assertThat(users.findAllByEmail(email)).hasSize(1);
    }

    @Test
    void theOrganizationIsPendingVerificationUntilSomebodySignsIn() throws Exception {
        String email = uniqueEmail();
        signUp("Northwind", email).andExpect(status().isAccepted());

        User admin = userFor(email);
        assertThat(organizations.findById(admin.getOrganizationId()).orElseThrow().getStatus())
                .isEqualTo(OrganizationStatus.PENDING_VERIFICATION);

        String token = TestTokens.forSubject(jwtEncoder, admin.getExternalSubject());
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(organizations.findById(admin.getOrganizationId()).orElseThrow().getStatus())
                .isEqualTo(OrganizationStatus.ACTIVE);
    }

    @Test
    void anUnverifiedOrganizationIsPurgedAfterSevenDays() throws Exception {
        String email = uniqueEmail();
        signUp("Abandoned Ltd", email).andExpect(status().isAccepted());
        Long organizationId = userFor(email).getOrganizationId();

        // Six days in, nothing happens.
        assertThat(purge.purge(Instant.now().plus(Duration.ofDays(6)))).isZero();
        assertThat(organizations.findById(organizationId)).isPresent();

        assertThat(purge.purge(Instant.now().plus(Duration.ofDays(8)))).isPositive();
        assertThat(organizations.findById(organizationId)).isEmpty();
        assertThat(users.findAllByEmail(email)).isEmpty();
    }

    /**
     * The identity has to go with the organization, or the address is held against a
     * future signup forever. Asserted through behaviour rather than by inspecting the
     * provider: if the identity were still there, signing up again would be swallowed as a
     * duplicate and create nothing.
     */
    @Test
    void purgingReleasesTheAddressForAnotherSignup() throws Exception {
        String email = uniqueEmail();
        signUp("Abandoned Ltd", email).andExpect(status().isAccepted());

        purge.purge(Instant.now().plus(Duration.ofDays(8)));

        signUp("Second Attempt Ltd", email).andExpect(status().isAccepted());
        assertThat(users.findAllByEmail(email)).hasSize(1);
        assertThat(organizations.findById(userFor(email).getOrganizationId()).orElseThrow().getName())
                .isEqualTo("Second Attempt Ltd");
    }

    @Test
    void aVerifiedOrganizationIsNeverPurgedHoweverOldItIs() throws Exception {
        String email = uniqueEmail();
        signUp("Northwind", email).andExpect(status().isAccepted());
        User admin = userFor(email);

        String token = TestTokens.forSubject(jwtEncoder, admin.getExternalSubject());
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        purge.purge(Instant.now().plus(Duration.ofDays(3650)));

        assertThat(organizations.findById(admin.getOrganizationId())).isPresent();
    }

    /**
     * The foreign keys are the safety net, and this is the test that says so.
     *
     * <p>An organization that has never been signed in to should own nothing but a user, a
     * subscription and its audit trail — everything else is created through an
     * authenticated endpoint, and authenticating is what ends PENDING_VERIFICATION. If that
     * reasoning is ever wrong, the purge must not quietly erase a real tenant. It does not:
     * the delete is refused, the whole transaction rolls back, and the organization stays.
     */
    @Test
    void refusesToPurgeAnUnverifiedOrganizationThatOwnsRealData() throws Exception {
        String email = uniqueEmail();
        signUp("Busy But Unverified Ltd", email).andExpect(status().isAccepted());
        Long organizationId = userFor(email).getOrganizationId();

        environments.saveAndFlush(new Environment(organizationId, "production"));

        // The sweep is organization-wide, so what it returns counts whatever else this
        // suite has left lying around. This one is what the assertion is about.
        purge.purge(Instant.now().plus(Duration.ofDays(8)));

        assertThat(organizations.findById(organizationId)).isPresent();
        // Nothing was half-deleted on the way to being refused.
        assertThat(users.findAllByEmail(email)).hasSize(1);
        assertThat(subscriptions.findByOrganizationId(organizationId)).isPresent();
    }

    @Test
    void refusesAnAddressThatIsNotAnAddress() throws Exception {
        signUp("Northwind", "not-an-email").andExpect(status().isBadRequest());
    }

    @Test
    void refusesAnEmptyCompanyName() throws Exception {
        signUp("", uniqueEmail()).andExpect(status().isBadRequest());
    }

}
