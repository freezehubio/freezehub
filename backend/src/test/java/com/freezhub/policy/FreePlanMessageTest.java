package com.freezhub.policy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.apikey.ApiKeyService;
import com.freezhub.audit.AuditActor;
import com.freezhub.catalog.Application;
import com.freezhub.catalog.ApplicationRepository;
import com.freezhub.catalog.Environment;
import com.freezhub.catalog.EnvironmentRepository;
import com.freezhub.shared.security.AuthenticatedUser;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.ChangeRestrictionRepository;
import com.freezhub.restriction.RestrictionLevel;
import com.freezhub.subscription.Plan;
import com.freezhub.subscription.Subscription;
import com.freezhub.subscription.SubscriptionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * What a free organization's build log says (FZ-145, D-33).
 *
 * <p>The conversion mechanism is one server-composed string. {@code freeze-check.sh} prints
 * {@code message} verbatim on ALLOW, so no connector changes — and nothing is misreported to
 * make the point: the decision is still ALLOW and the advisory is still an advisory.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class FreePlanMessageTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private ChangeRestrictionRepository changeRestrictionRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    private Long organizationId;
    private Long userId;
    private String apiKey;
    private Environment production;

    @BeforeEach
    void setUp() {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        organizationId = organization.getId();

        String subject = "subject-" + System.nanoTime();
        User admin = userRepository.saveAndFlush(
                new User(organizationId, subject, subject + "@acme.test", UserRole.ADMINISTRATOR));
        userId = admin.getId();

        applicationRepository.saveAndFlush(new Application(organizationId, "payments-api"));
        production = environmentRepository.saveAndFlush(new Environment(organizationId, "production"));

        apiKey = apiKeyService.create(organizationId,
                AuditActor.of(new AuthenticatedUser(userId, organizationId, admin.getEmail(), admin.getRole())),
                "gitlab-ci").rawKey();
    }

    private void onPlan(Plan plan) {
        subscriptionRepository.findByOrganizationId(organizationId)
                .ifPresent(subscriptionRepository::delete);
        subscriptionRepository.saveAndFlush(Subscription.provisioned(organizationId, plan));
    }

    private ChangeRestriction givenAdvisoryInForce() {
        Instant now = Instant.now();
        return changeRestrictionRepository.saveAndFlush(new ChangeRestriction(
                organizationId, "Q4 Release Freeze", null, "peak trading period",
                RestrictionLevel.ADVISORY, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)),
                userId, Set.of(), Set.of(), Set.of(production.getId())));
    }

    private ResultActions evaluate() throws Exception {
        return mockMvc.perform(post("/api/policy/evaluate")
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"DEPLOY\",\"application\":\"payments-api\",\"environment\":\"production\"}"));
    }

    @Test
    void aFreeOrganisationIsToldWhatAPaidPlanWouldHaveDone() throws Exception {
        onPlan(Plan.FREE);
        givenAdvisoryInForce();

        evaluate()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("ALLOW"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("would be refused on a paid plan")));
    }

    @Test
    void nothingIsMisreportedToMakeThePoint() throws Exception {
        // The decision is still ALLOW and the advisory is still an advisory. Dressing up
        // either would make the product lie to sell itself.
        onPlan(Plan.FREE);
        givenAdvisoryInForce();

        evaluate()
                .andExpect(jsonPath("$.decision").value("ALLOW"))
                .andExpect(jsonPath("$.restrictions[0].level").value("ADVISORY"));
    }

    @Test
    void aPaidPlanIsToldNothingExtra() throws Exception {
        onPlan(Plan.STARTER);
        givenAdvisoryInForce();

        evaluate()
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("paid plan"))));
    }

    @Test
    void aFreeOrganisationWithNothingInForceGetsTheOrdinaryMessage() throws Exception {
        // The common path. No restriction matched, so no clause -- and, in the
        // implementation, no subscription query either.
        onPlan(Plan.FREE);

        evaluate()
                .andExpect(jsonPath("$.decision").value("ALLOW"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("paid plan"))));
    }
}
