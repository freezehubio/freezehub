package com.freezhub.restriction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.catalog.Environment;
import com.freezhub.catalog.EnvironmentRepository;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import com.freezhub.shared.security.TestTokens;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class ChangeRestrictionCancelTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private ChangeRestrictionRepository changeRestrictionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Organization newOrganization() {
        return organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
    }

    private record Caller(String token, Long userId) {
    }

    private Caller callerFor(Organization organization) {
        String subject = "subject-" + System.nanoTime();
        User user = userRepository.saveAndFlush(
                new User(organization.getId(), subject, subject + "@acme.test", UserRole.ADMINISTRATOR));
        return new Caller(TestTokens.forSubject(jwtEncoder, subject), user.getId());
    }

    private ChangeRestriction givenRestriction(Organization organization, Caller caller, RestrictionStatus status) {
        Long environmentId = environmentRepository
                .saveAndFlush(new Environment(organization.getId(), "production" + System.nanoTime())).getId();
        Instant startsAt = Instant.now().plus(5, ChronoUnit.DAYS);
        ChangeRestriction restriction = changeRestrictionRepository.saveAndFlush(new ChangeRestriction(
                organization.getId(), "Black Friday Freeze", null, "Revenue-critical period",
                RestrictionLevel.HARD_FREEZE, startsAt, startsAt.plus(2, ChronoUnit.DAYS), caller.userId(),
                Set.of(), Set.of(), Set.of(environmentId)));

        if (status != RestrictionStatus.SCHEDULED) {
            jdbcTemplate.update("UPDATE change_restriction SET status = ? WHERE id = ?",
                    status.name(), restriction.getId());
        }
        return restriction;
    }

    private RestrictionStatus persistedStatusOf(ChangeRestriction restriction) {
        return RestrictionStatus.valueOf(jdbcTemplate.queryForObject(
                "SELECT status FROM change_restriction WHERE id = ?", String.class, restriction.getId()));
    }

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mockMvc.perform(post("/api/restrictions/1/cancel")).andExpect(status().isUnauthorized());
    }

    @Test
    void cancelsAScheduledRestriction() throws Exception {
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        ChangeRestriction restriction = givenRestriction(organization, caller, RestrictionStatus.SCHEDULED);

        mockMvc.perform(post("/api/restrictions/" + restriction.getId() + "/cancel")
                        .header("Authorization", "Bearer " + caller.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(restriction.getId().intValue())))
                .andExpect(jsonPath("$.status", is("CANCELLED")));

        assertThat(persistedStatusOf(restriction)).isEqualTo(RestrictionStatus.CANCELLED);
    }

    @Test
    void cancelsAnActiveRestriction() throws Exception {
        // The lifecycle allows cancelling mid-flight, not only before it starts.
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        ChangeRestriction restriction = givenRestriction(organization, caller, RestrictionStatus.ACTIVE);

        mockMvc.perform(post("/api/restrictions/" + restriction.getId() + "/cancel")
                        .header("Authorization", "Bearer " + caller.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")));

        assertThat(persistedStatusOf(restriction)).isEqualTo(RestrictionStatus.CANCELLED);
    }

    @Test
    void preservesTheRecordRatherThanDeletingIt() throws Exception {
        // Cancelling keeps the restriction: what was communicated to engineers happened.
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        ChangeRestriction restriction = givenRestriction(organization, caller, RestrictionStatus.SCHEDULED);

        mockMvc.perform(post("/api/restrictions/" + restriction.getId() + "/cancel")
                        .header("Authorization", "Bearer " + caller.token()))
                .andExpect(status().isOk());

        assertThat(changeRestrictionRepository.findById(restriction.getId())).isPresent();
    }

    @Test
    void returnsTheScopeAlongsideTheCancelledRestriction() throws Exception {
        // Regression guard for LazyInitializationException: cancel does not touch the scope
        // collections, so this only passes because the service initialises them.
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        ChangeRestriction restriction = givenRestriction(organization, caller, RestrictionStatus.SCHEDULED);
        Long environmentId = restriction.getEnvironmentIds().iterator().next();

        mockMvc.perform(post("/api/restrictions/" + restriction.getId() + "/cancel")
                        .header("Authorization", "Bearer " + caller.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.environmentIds", contains(environmentId.intValue())));
    }

    @Test
    void refusesToCancelACompletedRestriction() throws Exception {
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        ChangeRestriction restriction = givenRestriction(organization, caller, RestrictionStatus.COMPLETED);

        mockMvc.perform(post("/api/restrictions/" + restriction.getId() + "/cancel")
                        .header("Authorization", "Bearer " + caller.token()))
                .andExpect(status().isConflict());

        assertThat(persistedStatusOf(restriction)).isEqualTo(RestrictionStatus.COMPLETED);
    }

    @Test
    void refusesToCancelAnAlreadyCancelledRestriction() throws Exception {
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        ChangeRestriction restriction = givenRestriction(organization, caller, RestrictionStatus.CANCELLED);

        mockMvc.perform(post("/api/restrictions/" + restriction.getId() + "/cancel")
                        .header("Authorization", "Bearer " + caller.token()))
                .andExpect(status().isConflict());
    }

    @Test
    void returnsNotFoundForAnUnknownId() throws Exception {
        Caller caller = callerFor(newOrganization());

        mockMvc.perform(post("/api/restrictions/999999/cancel")
                        .header("Authorization", "Bearer " + caller.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void refusesToCancelAnotherOrganizationsRestriction() throws Exception {
        Organization orgA = newOrganization();
        Organization orgB = newOrganization();
        Caller callerA = callerFor(orgA);
        Caller callerB = callerFor(orgB);
        ChangeRestriction restrictionOfA = givenRestriction(orgA, callerA, RestrictionStatus.SCHEDULED);

        mockMvc.perform(post("/api/restrictions/" + restrictionOfA.getId() + "/cancel")
                        .header("Authorization", "Bearer " + callerB.token()))
                .andExpect(status().isNotFound());

        // Still live for its owner - a neighbouring tenant cannot lift someone else's freeze.
        assertThat(persistedStatusOf(restrictionOfA)).isEqualTo(RestrictionStatus.SCHEDULED);
    }

    @Test
    void aCancelledRestrictionCanNoLongerBeUpdated() throws Exception {
        // Domain invariant 5: cancellation is terminal.
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        ChangeRestriction restriction = givenRestriction(organization, caller, RestrictionStatus.SCHEDULED);

        mockMvc.perform(post("/api/restrictions/" + restriction.getId() + "/cancel")
                        .header("Authorization", "Bearer " + caller.token()))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/restrictions/" + restriction.getId())
                        .header("Authorization", "Bearer " + caller.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Revived",
                                  "reason": "Trying to edit a cancelled restriction",
                                  "level": "ADVISORY",
                                  "startsAt": "%s",
                                  "endsAt": "%s",
                                  "scope": { "environmentIds": [%d] }
                                }
                                """.formatted(
                                Instant.now().plus(5, ChronoUnit.DAYS),
                                Instant.now().plus(6, ChronoUnit.DAYS),
                                restriction.getEnvironmentIds().iterator().next())))
                .andExpect(status().isConflict());
    }

}
