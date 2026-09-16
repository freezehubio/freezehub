package com.freezhub.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import com.freezhub.subscription.Plan;
import com.freezhub.subscription.Subscription;
import com.freezhub.subscription.SubscriptionRepository;
import java.time.Instant;
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

/** What the billing screen reads, and who may read it (FZ-085). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class SubscriptionViewTest {

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

    private Long organizationId;
    private String adminToken;
    private String memberToken;

    @BeforeEach
    void setUp() {
        Organization organization =
                organizations.saveAndFlush(new Organization("Northwind " + System.nanoTime()));
        organizationId = organization.getId();
        adminToken = tokenFor(UserRole.ADMINISTRATOR, "admin");
        memberToken = tokenFor(UserRole.MEMBER, "member");
    }

    private String tokenFor(UserRole role, String prefix) {
        String subject = UUID.randomUUID().toString();
        users.saveAndFlush(new User(organizationId, subject,
                prefix + "-" + System.nanoTime() + "@northwind.test", role));
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwtClaimsSet.builder().subject(subject).issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600)).build())).getTokenValue();
    }

    private void onPlan(Plan plan) {
        subscriptions.saveAndFlush(Subscription.provisioned(organizationId, plan));
    }

    @Test
    void anyMemberCanSeeThePlanAndItsUsage() throws Exception {
        // Not administrator-only, deliberately: the trial banner has to reach everyone,
        // and a member who cannot see why a creation was refused files a bug instead of
        // asking their administrator to upgrade.
        onPlan(Plan.STARTER);

        mockMvc.perform(get("/api/billing/subscription").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("STARTER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.usage[0].resource").value("applications"))
                .andExpect(jsonPath("$.usage[0].limit").value(Plan.STARTER.applications()))
                .andExpect(jsonPath("$.usage[0].current").value(0));
    }

    @Test
    void usageIsVisibleBeforeALimitIsHit() throws Exception {
        onPlan(Plan.STARTER);
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/applications")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"app-" + i + "\"}"))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/billing/subscription").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage[0].current").value(4))
                .andExpect(jsonPath("$.usage[0].percentUsed").value(40))
                .andExpect(jsonPath("$.usage[0].atLimit").value(false));
    }

    @Test
    void anUnlimitedLimitHasNoPercentage() throws Exception {
        // Null limit means unlimited. A percentage of unlimited is meaningless, and a bar
        // rendered at 0% would read as "none used" rather than "no ceiling".
        onPlan(Plan.ENTERPRISE);

        mockMvc.perform(get("/api/billing/subscription").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage[0].limit").isEmpty())
                .andExpect(jsonPath("$.usage[0].percentUsed").isEmpty());
    }

    @Test
    void aTrialReportsWholeDaysRemaining() throws Exception {
        subscriptions.saveAndFlush(
                Subscription.startTrial(organizationId, Instant.now()));

        mockMvc.perform(get("/api/billing/subscription").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIALING"))
                .andExpect(jsonPath("$.trialDaysRemaining").value(Subscription.TRIAL_DAYS - 1));
    }

    @Test
    void anEstablishedPlanReportsNoTrialCountdown() throws Exception {
        onPlan(Plan.GROWTH);

        mockMvc.perform(get("/api/billing/subscription").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trialDaysRemaining").isEmpty());
    }

    @Test
    void enterpriseCannotBeBoughtSelfServe() throws Exception {
        onPlan(Plan.ENTERPRISE);

        mockMvc.perform(get("/api/billing/subscription").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canUpgradeSelfServe").value(false));
    }

    // --- OI-14: retention is priced per plan, and now actually enforced ---------------

    @Test
    void aPlanCapsHowLongChecksAreKept() throws Exception {
        onPlan(Plan.STARTER);

        mockMvc.perform(patch("/api/organization/settings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startingSoonLeadTimeMinutes\":1440,\"deploymentCheckRetentionDays\":365}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.limit").value(Plan.STARTER.deploymentCheckRetentionDays()))
                .andExpect(jsonPath("$.current").value(365));

        assertThat(organizations.findById(organizationId).orElseThrow()
                .getDeploymentCheckRetentionDays())
                .isEqualTo(Organization.DEFAULT_DEPLOYMENT_CHECK_RETENTION_DAYS);
    }

    @Test
    void retentionWithinThePlanIsAccepted() throws Exception {
        onPlan(Plan.STARTER);

        mockMvc.perform(patch("/api/organization/settings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startingSoonLeadTimeMinutes\":1440,\"deploymentCheckRetentionDays\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deploymentCheckRetentionDays").value(30));
    }

    @Test
    void aHigherPlanMayKeepMore() throws Exception {
        onPlan(Plan.ENTERPRISE);

        mockMvc.perform(patch("/api/organization/settings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startingSoonLeadTimeMinutes\":1440,\"deploymentCheckRetentionDays\":2555}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deploymentCheckRetentionDays").value(2555));
    }

    @Test
    void theViewSaysWhetherFreezesActuallyBlock() throws Exception {
        // FZ-146: the one capability that separates FREE from Starter has to come from the
        // backend. A UI deciding it from the plan's name would be the frontend deciding
        // entitlement, which is the thing every other limit on this endpoint avoids.
        onPlan(Plan.FREE);

        mockMvc.perform(get("/api/billing/subscription").header("Authorization", "Bearer " + tokenFor(UserRole.ADMINISTRATOR, "cap")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocksDeployments").value(false));
    }

    @Test
    void aPaidPlanSaysItsFreezesBlock() throws Exception {
        onPlan(Plan.STARTER);

        mockMvc.perform(get("/api/billing/subscription").header("Authorization", "Bearer " + tokenFor(UserRole.ADMINISTRATOR, "cap")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocksDeployments").value(true));
    }
}
