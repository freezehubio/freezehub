package com.freezhub.subscription;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The free plan announces freezes; it cannot create one that blocks (FZ-143, D-33).
 *
 * <p>The gate is on the <em>level at creation</em>, not on the answer: a FREE organization
 * never has a HARD_FREEZE row, so domain rule 3 stays unconditionally true and
 * PolicyService consults no subscription. {@link #aFreeOrganizationsAdvisoryAnswerIsAnOrdinaryAllow}
 * is what proves the second half of that.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class FreePlanTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Long organizationId;
    private String token;

    @BeforeEach
    void setUp() {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Northwind " + System.nanoTime()));
        organizationId = organization.getId();

        String subject = UUID.randomUUID().toString();
        userRepository.saveAndFlush(
                new User(organizationId, subject, "admin@northwind.test", UserRole.ADMINISTRATOR));
        token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwtClaimsSet.builder().subject(subject).issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600)).build())).getTokenValue();
    }

    private void onPlan(Plan plan) {
        subscriptionRepository.findByOrganizationId(organizationId)
                .ifPresent(subscriptionRepository::delete);
        subscriptionRepository.saveAndFlush(Subscription.provisioned(organizationId, plan));
    }

    private Long createEnvironment(String name) throws Exception {
        String body = mockMvc.perform(post("/api/environments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private String restrictionBody(String level, Long environmentId) {
        Instant starts = Instant.now().plus(1, ChronoUnit.DAYS);
        Instant ends = starts.plus(1, ChronoUnit.DAYS);
        return """
                {"name":"Q4 Release Freeze","reason":"peak trading period","type":"DEPLOYMENT_FREEZE",
                 "level":"%s","startsAt":"%s","endsAt":"%s",
                 "scope":{"teamIds":[],"applicationIds":[],"environmentIds":[%d]}}
                """.formatted(level, starts, ends, environmentId);
    }

    private ResultActions createRestriction(String level, Long environmentId) throws Exception {
        return mockMvc.perform(post("/api/restrictions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(restrictionBody(level, environmentId)));
    }

    @Test
    void aFreePlanRefusesAHardFreezeWith402() throws Exception {
        onPlan(Plan.FREE);
        Long environmentId = createEnvironment("production");

        // 402, like every other plan refusal: the request is well-formed and the caller is
        // permitted. It is the plan that refused.
        createRestriction("HARD_FREEZE", environmentId)
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.feature").value("blocking freezes"));
    }

    @Test
    void aFreePlanRefusalNamesACapabilityAndCarriesNoCount() throws Exception {
        // A capability is not a count. Reusing the limit exception would render
        // "your FREE plan allows 0 blocking freezes, and you are using 0", which is worse
        // than the truth.
        onPlan(Plan.FREE);
        Long environmentId = createEnvironment("production");

        createRestriction("HARD_FREEZE", environmentId)
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.limit").doesNotExist())
                .andExpect(jsonPath("$.current").doesNotExist());
    }

    @Test
    void aFreePlanMayCreateAnAdvisoryRestriction() throws Exception {
        onPlan(Plan.FREE);
        Long environmentId = createEnvironment("production");

        createRestriction("ADVISORY", environmentId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.level").value("ADVISORY"));
    }

    @Test
    void aPaidPlanMayCreateAHardFreeze() throws Exception {
        onPlan(Plan.STARTER);
        Long environmentId = createEnvironment("production");

        createRestriction("HARD_FREEZE", environmentId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.level").value("HARD_FREEZE"));
    }

    @Test
    void aFreePlanCannotRaiseAnExistingRestrictionToAHardFreeze() throws Exception {
        // The guard belongs on the write path, not only on create -- otherwise the rule is
        // one PUT away from being untrue.
        onPlan(Plan.FREE);
        Long environmentId = createEnvironment("production");

        String created = createRestriction("ADVISORY", environmentId)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long restrictionId = objectMapper.readTree(created).get("id").asLong();

        mockMvc.perform(put("/api/restrictions/" + restrictionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(restrictionBody("HARD_FREEZE", environmentId)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.plan").value("FREE"));
    }

    @Test
    void aFreeOrganizationsAdvisoryAnswerIsAnOrdinaryAllow() throws Exception {
        // The second half of D-33: because the row never exists, nothing downstream had to
        // learn about billing. The response for an advisory is what it has always been.
        onPlan(Plan.FREE);
        Long environmentId = createEnvironment("production");
        createRestriction("ADVISORY", environmentId).andExpect(status().isCreated());

        String body = mockMvc.perform(post("/api/restrictions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(restrictionBody("ADVISORY", environmentId)))
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(body);
        org.assertj.core.api.Assertions.assertThat(node.get("level").asText()).isEqualTo("ADVISORY");
    }
}
