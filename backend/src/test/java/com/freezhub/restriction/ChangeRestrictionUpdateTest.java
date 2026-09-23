package com.freezhub.restriction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.ObjectMapper;
import com.freezhub.ContainersConfig;
import com.freezhub.catalog.Application;
import com.freezhub.catalog.ApplicationRepository;
import com.freezhub.catalog.Environment;
import com.freezhub.catalog.EnvironmentRepository;
import com.freezhub.catalog.Team;
import com.freezhub.catalog.TeamRepository;
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
class ChangeRestrictionUpdateTest {

    private static final Instant START = Instant.now().plus(10, ChronoUnit.DAYS);
    private static final Instant END = Instant.now().plus(15, ChronoUnit.DAYS);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private ChangeRestrictionRepository changeRestrictionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    private Long newEnvironment(Organization organization, String name) {
        return environmentRepository.saveAndFlush(new Environment(organization.getId(), name + System.nanoTime()))
                .getId();
    }

    private ChangeRestriction givenScheduled(Organization organization, Caller caller, Set<Long> environmentIds) {
        return changeRestrictionRepository.saveAndFlush(new ChangeRestriction(
                organization.getId(), "Original name", "Original description", "Original reason",
                RestrictionLevel.HARD_FREEZE, START, END, caller.userId(),
                Set.of(), Set.of(), environmentIds));
    }

    private void forceStatus(ChangeRestriction restriction, RestrictionStatus status) {
        jdbcTemplate.update("UPDATE change_restriction SET status = ? WHERE id = ?",
                status.name(), restriction.getId());
    }

    private RestrictionRequest request(String name, String description, String reason, RestrictionLevel level,
                                       Instant startsAt, Instant endsAt, Set<Long> environmentIds) {
        return new RestrictionRequest(name, description, reason, level, startsAt, endsAt,
                new RestrictionRequest.ScopeRequest(null, null, environmentIds));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mockMvc.perform(put("/api/restrictions/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("n", null, "r", RestrictionLevel.ADVISORY, START, END, Set.of(1L)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void replacesEveryEditableField() throws Exception {
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        Long originalEnvironment = newEnvironment(organization, "production");
        Long replacementEnvironment = newEnvironment(organization, "staging");
        ChangeRestriction restriction = givenScheduled(organization, caller, Set.of(originalEnvironment));

        Instant newStart = Instant.now().plus(20, ChronoUnit.DAYS);
        Instant newEnd = Instant.now().plus(25, ChronoUnit.DAYS);

        mockMvc.perform(put("/api/restrictions/" + restriction.getId())
                        .header("Authorization", "Bearer " + caller.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("Updated name", "Updated description", "Updated reason",
                                RestrictionLevel.ADVISORY, newStart, newEnd, Set.of(replacementEnvironment)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(restriction.getId().intValue())))
                .andExpect(jsonPath("$.name", is("Updated name")))
                .andExpect(jsonPath("$.description", is("Updated description")))
                .andExpect(jsonPath("$.reason", is("Updated reason")))
                .andExpect(jsonPath("$.level", is("ADVISORY")))
                .andExpect(jsonPath("$.status", is("SCHEDULED")))
                .andExpect(jsonPath("$.type", is("DEPLOYMENT_FREEZE")))
                .andExpect(jsonPath("$.createdBy", is(caller.userId().intValue())))
                .andExpect(jsonPath("$.scope.environmentIds", contains(replacementEnvironment.intValue())));

        // Persisted, not merely echoed back.
        ChangeRestriction reloaded = changeRestrictionRepository.findById(restriction.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Updated name");
        assertThat(reloaded.getLevel()).isEqualTo(RestrictionLevel.ADVISORY);
    }

    @Test
    void clearsAnOptionalFieldWhenItIsOmitted() throws Exception {
        // The reason PUT was chosen over PATCH: a null description is unambiguous.
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        Long environmentId = newEnvironment(organization, "production");
        ChangeRestriction restriction = givenScheduled(organization, caller, Set.of(environmentId));

        mockMvc.perform(put("/api/restrictions/" + restriction.getId())
                        .header("Authorization", "Bearer " + caller.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("Still here", null, "Still a reason",
                                RestrictionLevel.HARD_FREEZE, START, END, Set.of(environmentId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").doesNotExist());
    }

    @Test
    void replacesScopeRatherThanAccumulatingIt() throws Exception {
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        Long environmentId = newEnvironment(organization, "production");
        Long teamId = teamRepository.saveAndFlush(new Team(organization.getId(), "Payments")).getId();
        Long applicationId = applicationRepository
                .saveAndFlush(new Application(organization.getId(), "payments-api")).getId();
        ChangeRestriction restriction = givenScheduled(organization, caller, Set.of(environmentId));

        // Swap the environment dimension out for team + application dimensions.
        RestrictionRequest swap = new RestrictionRequest("Scope swap", null, "Reason",
                RestrictionLevel.HARD_FREEZE, START, END,
                new RestrictionRequest.ScopeRequest(Set.of(teamId), Set.of(applicationId), Set.of()));

        mockMvc.perform(put("/api/restrictions/" + restriction.getId())
                        .header("Authorization", "Bearer " + caller.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(swap)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.teamIds", contains(teamId.intValue())))
                .andExpect(jsonPath("$.scope.applicationIds", contains(applicationId.intValue())))
                .andExpect(jsonPath("$.scope.environmentIds", is(empty())));
    }

    @Test
    void refusesToUpdateAnActiveRestriction() throws Exception {
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        Long environmentId = newEnvironment(organization, "production");
        ChangeRestriction restriction = givenScheduled(organization, caller, Set.of(environmentId));
        forceStatus(restriction, RestrictionStatus.ACTIVE);

        mockMvc.perform(put("/api/restrictions/" + restriction.getId())
                        .header("Authorization", "Bearer " + caller.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("Nope", null, "Reason", RestrictionLevel.ADVISORY,
                                START, END, Set.of(environmentId)))))
                .andExpect(status().isConflict());
    }

    @Test
    void refusesToUpdateACompletedRestriction() throws Exception {
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        Long environmentId = newEnvironment(organization, "production");
        ChangeRestriction restriction = givenScheduled(organization, caller, Set.of(environmentId));
        forceStatus(restriction, RestrictionStatus.COMPLETED);

        mockMvc.perform(put("/api/restrictions/" + restriction.getId())
                        .header("Authorization", "Bearer " + caller.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("Nope", null, "Reason", RestrictionLevel.ADVISORY,
                                START, END, Set.of(environmentId)))))
                .andExpect(status().isConflict());
    }

    @Test
    void refusesToUpdateACancelledRestriction() throws Exception {
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        Long environmentId = newEnvironment(organization, "production");
        ChangeRestriction restriction = givenScheduled(organization, caller, Set.of(environmentId));
        forceStatus(restriction, RestrictionStatus.CANCELLED);

        mockMvc.perform(put("/api/restrictions/" + restriction.getId())
                        .header("Authorization", "Bearer " + caller.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("Nope", null, "Reason", RestrictionLevel.ADVISORY,
                                START, END, Set.of(environmentId)))))
                .andExpect(status().isConflict());
    }

    @Test
    void leavesAnActiveRestrictionUntouchedWhenRefused() throws Exception {
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        Long environmentId = newEnvironment(organization, "production");
        ChangeRestriction restriction = givenScheduled(organization, caller, Set.of(environmentId));
        forceStatus(restriction, RestrictionStatus.ACTIVE);

        mockMvc.perform(put("/api/restrictions/" + restriction.getId())
                        .header("Authorization", "Bearer " + caller.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("Overwritten", null, "Reason", RestrictionLevel.ADVISORY,
                                START, END, Set.of(environmentId)))))
                .andExpect(status().isConflict());

        ChangeRestriction reloaded = changeRestrictionRepository.findById(restriction.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Original name");
        assertThat(reloaded.getLevel()).isEqualTo(RestrictionLevel.HARD_FREEZE);
    }

    @Test
    void returnsNotFoundForAnUnknownId() throws Exception {
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        Long environmentId = newEnvironment(organization, "production");

        mockMvc.perform(put("/api/restrictions/999999")
                        .header("Authorization", "Bearer " + caller.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("n", null, "r", RestrictionLevel.ADVISORY,
                                START, END, Set.of(environmentId)))))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsNotFoundForARestrictionOwnedByAnotherOrganization() throws Exception {
        Organization orgA = newOrganization();
        Organization orgB = newOrganization();
        Caller callerA = callerFor(orgA);
        Caller callerB = callerFor(orgB);
        Long environmentOfA = newEnvironment(orgA, "production");
        Long environmentOfB = newEnvironment(orgB, "production");
        ChangeRestriction restrictionOfA = givenScheduled(orgA, callerA, Set.of(environmentOfA));

        mockMvc.perform(put("/api/restrictions/" + restrictionOfA.getId())
                        .header("Authorization", "Bearer " + callerB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("Hijack", null, "Reason", RestrictionLevel.ADVISORY,
                                START, END, Set.of(environmentOfB)))))
                .andExpect(status().isNotFound());

        assertThat(changeRestrictionRepository.findById(restrictionOfA.getId()).orElseThrow().getName())
                .isEqualTo("Original name");
    }

    @Test
    void rejectsScopeResourcesOwnedByAnotherOrganization() throws Exception {
        Organization orgA = newOrganization();
        Organization orgB = newOrganization();
        Caller callerA = callerFor(orgA);
        Long environmentOfA = newEnvironment(orgA, "production");
        Long environmentOfB = newEnvironment(orgB, "production");
        ChangeRestriction restriction = givenScheduled(orgA, callerA, Set.of(environmentOfA));

        mockMvc.perform(put("/api/restrictions/" + restriction.getId())
                        .header("Authorization", "Bearer " + callerA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("Borrowed scope", null, "Reason", RestrictionLevel.ADVISORY,
                                START, END, Set.of(environmentOfB)))))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsStartsAtNotBeforeEndsAt() throws Exception {
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        Long environmentId = newEnvironment(organization, "production");
        ChangeRestriction restriction = givenScheduled(organization, caller, Set.of(environmentId));

        mockMvc.perform(put("/api/restrictions/" + restriction.getId())
                        .header("Authorization", "Bearer " + caller.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("Bad window", null, "Reason", RestrictionLevel.ADVISORY,
                                END, START, Set.of(environmentId)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAWindowEntirelyInThePast() throws Exception {
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        Long environmentId = newEnvironment(organization, "production");
        ChangeRestriction restriction = givenScheduled(organization, caller, Set.of(environmentId));

        mockMvc.perform(put("/api/restrictions/" + restriction.getId())
                        .header("Authorization", "Bearer " + caller.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("Past", null, "Reason", RestrictionLevel.ADVISORY,
                                Instant.now().minus(10, ChronoUnit.DAYS), Instant.now().minus(5, ChronoUnit.DAYS),
                                Set.of(environmentId)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnUpdateThatEmptiesTheScope() throws Exception {
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        Long environmentId = newEnvironment(organization, "production");
        ChangeRestriction restriction = givenScheduled(organization, caller, Set.of(environmentId));

        mockMvc.perform(put("/api/restrictions/" + restriction.getId())
                        .header("Authorization", "Bearer " + caller.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("No scope", null, "Reason", RestrictionLevel.ADVISORY,
                                START, END, Set.of()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsABlankReason() throws Exception {
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        Long environmentId = newEnvironment(organization, "production");
        ChangeRestriction restriction = givenScheduled(organization, caller, Set.of(environmentId));

        mockMvc.perform(put("/api/restrictions/" + restriction.getId())
                        .header("Authorization", "Bearer " + caller.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("Blank reason", null, "   ", RestrictionLevel.ADVISORY,
                                START, END, Set.of(environmentId)))))
                .andExpect(status().isBadRequest());
    }

}
