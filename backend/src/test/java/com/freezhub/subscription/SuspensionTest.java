package com.freezhub.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.freezhub.ContainersConfig;
import com.freezhub.apikey.ApiKeyService;
import com.freezhub.audit.AuditAction;
import com.freezhub.audit.AuditActor;
import com.freezhub.audit.AuditRepository;
import com.freezhub.catalog.Application;
import com.freezhub.catalog.ApplicationRepository;
import com.freezhub.catalog.Environment;
import com.freezhub.catalog.EnvironmentRepository;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.ChangeRestrictionRepository;
import com.freezhub.restriction.RestrictionLevel;
import com.freezhub.shared.security.AuthenticatedUser;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What suspension does, and — more importantly — what it must never do (FZ-081, D-21).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class SuspensionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private ChangeRestrictionRepository restrictionRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Long organizationId;
    private String token;
    private String rawApiKey;

    @BeforeEach
    void setUp() {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Northwind " + System.nanoTime()));
        organizationId = organization.getId();

        String subject = UUID.randomUUID().toString();
        User admin = userRepository.saveAndFlush(
                new User(organizationId, subject, "admin@northwind.test", UserRole.ADMINISTRATOR));
        token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwtClaimsSet.builder().subject(subject).issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600)).build())).getTokenValue();

        rawApiKey = apiKeyService.create(organizationId, AuditActor.of(new AuthenticatedUser(
                admin.getId(), organizationId, "admin@northwind.test", UserRole.ADMINISTRATOR)),
                "ci").rawKey();

        Application application =
                applicationRepository.saveAndFlush(new Application(organizationId, "payments-api"));
        Environment environment =
                environmentRepository.saveAndFlush(new Environment(organizationId, "production"));

        Instant now = Instant.now();
        ChangeRestriction freeze = new ChangeRestriction(organizationId, "Black Friday Freeze", null,
                "Revenue-critical period", RestrictionLevel.HARD_FREEZE,
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(2)), admin.getId(),
                Set.of(), Set.of(application.getId()), Set.of(environment.getId()));
        restrictionRepository.saveAndFlush(freeze);
    }

    private void suspend() {
        Subscription subscription = Subscription.provisioned(organizationId, Plan.STARTER);
        subscription.suspend();
        subscriptionRepository.saveAndFlush(subscription);
    }

    /**
     * The policy answer with {@code evaluatedAt} removed.
     *
     * <p>That one field is a timestamp of the call and must differ between two calls; the
     * assertion is that nothing else does. Comparing the raw body would fail for a reason
     * that has nothing to do with what is being tested, and a test that fails for the
     * wrong reason gets weakened rather than read.
     */
    private String answerIgnoringTimestamp() throws Exception {
        ObjectNode answer = (ObjectNode) objectMapper.readTree(evaluate());
        answer.remove("evaluatedAt");
        return answer.toString();
    }

    private String evaluate() throws Exception {
        return mockMvc.perform(post("/api/policy/evaluate")
                        .header("X-API-Key", rawApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"DEPLOY","application":"payments-api","environment":"production"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void suspensionDoesNotChangeAPolicyAnswer() throws Exception {
        // The rule most likely to be broken by a later change, so it is asserted on the
        // whole response body rather than on a decision field.
        //
        // Failing this endpoint would take every one of the customer's pipelines down over
        // an invoice — freeze-check.sh defaults to blocking on error, so a 401 here stops
        // their deployments. Allowing through it would silently lift every freeze at the
        // exact moment of a commercial dispute. Both are worse than not collecting.
        String before = answerIgnoringTimestamp();
        suspend();
        String after = answerIgnoringTimestamp();

        assertThat(after).isEqualTo(before);
        assertThat(after).contains("\"decision\":\"BLOCK\"");
    }

    @Test
    void suspensionMakesTheHumanApiReadOnly() throws Exception {
        suspend();

        mockMvc.perform(post("/api/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"checkout-web\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.subscriptionStatus").value("SUSPENDED"));
    }

    @Test
    void suspensionLeavesReadsOpen() throws Exception {
        // Locking a customer out of their own record is not leverage. They must still be
        // able to see what is frozen and read their audit trail.
        suspend();

        mockMvc.perform(get("/api/restrictions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/audit").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void anExpiredTrialIsNoLongerSuspended() {
        // Moved here from an assertion that a trial expires to SUSPENDED. That was the
        // behaviour until FZ-144; D-33 changed it, and the full transition is covered by
        // TrialExpiryTest. What this class still owns is the boundary: a trial ending must
        // not produce a suspended organization.
        Instant now = Instant.now();
        subscriptionRepository.saveAndFlush(
                Subscription.startTrial(organizationId, now.minus(Duration.ofDays(20))));

        subscriptionService.expireTrials(now);

        assertThat(subscriptionRepository.findByOrganizationId(organizationId))
                .get().extracting(Subscription::getStatus)
                .isNotEqualTo(SubscriptionStatus.SUSPENDED);
    }

    @Test
    void aTrialThatHasNotExpiredIsLeftAlone() {
        Instant now = Instant.now();
        subscriptionRepository.saveAndFlush(Subscription.startTrial(organizationId, now));

        subscriptionService.expireTrials(now);

        assertThat(subscriptionRepository.findByOrganizationId(organizationId))
                .get().extracting(Subscription::getStatus)
                .isEqualTo(SubscriptionStatus.TRIALING);
    }
}
