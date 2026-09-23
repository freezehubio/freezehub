package com.freezhub.restriction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class ChangeRestrictionControllerTest {

    private static final Instant FUTURE_START = Instant.now().plus(10, ChronoUnit.DAYS);
    private static final Instant FUTURE_END = Instant.now().plus(15, ChronoUnit.DAYS);

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Organization newOrganization() {
        return organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
    }

    private String tokenFor(Organization organization) {
        String subject = "subject-" + System.nanoTime();
        userRepository.saveAndFlush(
                new User(organization.getId(), subject, subject + "@acme.test", UserRole.ADMINISTRATOR));
        return TestTokens.forSubject(jwtEncoder, subject);
    }

    private Long userIdOf(String email) {
        return userRepository.findAll().stream()
                .filter(u -> u.getEmail().equals(email))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private RestrictionRequest validRequest(RestrictionRequest.ScopeRequest scope) {
        return new RestrictionRequest("Black Friday Freeze", "No production deploys",
                "Revenue-critical period", RestrictionLevel.HARD_FREEZE, FUTURE_START, FUTURE_END, scope);
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mockMvc.perform(post("/api/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest(
                                new RestrictionRequest.ScopeRequest(null, null, Set.of(1L))))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createsAScheduledRestrictionWithFullScope() throws Exception {
        Organization organization = newOrganization();
        String subject = "creator-" + System.nanoTime();
        User creator = userRepository.saveAndFlush(
                new User(organization.getId(), subject, subject + "@acme.test", UserRole.ADMINISTRATOR));
        String token = TestTokens.forSubject(jwtEncoder, subject);

        Long teamId = teamRepository.saveAndFlush(new Team(organization.getId(), "Payments")).getId();
        Long applicationId =
                applicationRepository.saveAndFlush(new Application(organization.getId(), "payments-api")).getId();
        Long environmentId =
                environmentRepository.saveAndFlush(new Environment(organization.getId(), "production")).getId();

        mockMvc.perform(post("/api/restrictions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest(new RestrictionRequest.ScopeRequest(
                                Set.of(teamId), Set.of(applicationId), Set.of(environmentId))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("Black Friday Freeze")))
                .andExpect(jsonPath("$.reason", is("Revenue-critical period")))
                .andExpect(jsonPath("$.type", is("DEPLOYMENT_FREEZE")))
                .andExpect(jsonPath("$.level", is("HARD_FREEZE")))
                .andExpect(jsonPath("$.status", is("SCHEDULED")))
                .andExpect(jsonPath("$.createdBy", is(creator.getId().intValue())))
                .andExpect(jsonPath("$.scope.teamIds", contains(teamId.intValue())))
                .andExpect(jsonPath("$.scope.applicationIds", contains(applicationId.intValue())))
                .andExpect(jsonPath("$.scope.environmentIds", contains(environmentId.intValue())));
    }

    @Test
    void createsARestrictionScopedOnlyToAnEnvironment() throws Exception {
        // "Freeze every deployment to production": the other dimensions are wildcards.
        Organization organization = newOrganization();
        String token = tokenFor(organization);
        Long environmentId =
                environmentRepository.saveAndFlush(new Environment(organization.getId(), "production")).getId();

        mockMvc.perform(post("/api/restrictions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest(new RestrictionRequest.ScopeRequest(
                                null, null, Set.of(environmentId))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope.teamIds", is(empty())))
                .andExpect(jsonPath("$.scope.applicationIds", is(empty())))
                .andExpect(jsonPath("$.scope.environmentIds", hasSize(1)));
    }

    @Test
    void acceptsAdvisoryLevel() throws Exception {
        Organization organization = newOrganization();
        String token = tokenFor(organization);
        Long environmentId =
                environmentRepository.saveAndFlush(new Environment(organization.getId(), "staging")).getId();

        RestrictionRequest request = new RestrictionRequest("Migration window", null,
                "Database migration in progress", RestrictionLevel.ADVISORY, FUTURE_START, FUTURE_END,
                new RestrictionRequest.ScopeRequest(null, null, Set.of(environmentId)));

        mockMvc.perform(post("/api/restrictions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.level", is("ADVISORY")))
                .andExpect(jsonPath("$.description").doesNotExist());
    }

    @Test
    void rejectsStartsAtNotBeforeEndsAt() throws Exception {
        Organization organization = newOrganization();
        String token = tokenFor(organization);
        Long environmentId =
                environmentRepository.saveAndFlush(new Environment(organization.getId(), "production")).getId();

        RestrictionRequest request = new RestrictionRequest("Bad window", null, "Reason",
                RestrictionLevel.HARD_FREEZE, FUTURE_END, FUTURE_START,
                new RestrictionRequest.ScopeRequest(null, null, Set.of(environmentId)));

        mockMvc.perform(post("/api/restrictions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAWindowEntirelyInThePast() throws Exception {
        Organization organization = newOrganization();
        String token = tokenFor(organization);
        Long environmentId =
                environmentRepository.saveAndFlush(new Environment(organization.getId(), "production")).getId();

        RestrictionRequest request = new RestrictionRequest("Past freeze", null, "Reason",
                RestrictionLevel.HARD_FREEZE,
                Instant.now().minus(10, ChronoUnit.DAYS), Instant.now().minus(5, ChronoUnit.DAYS),
                new RestrictionRequest.ScopeRequest(null, null, Set.of(environmentId)));

        mockMvc.perform(post("/api/restrictions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnEmptyScope() throws Exception {
        Organization organization = newOrganization();
        String token = tokenFor(organization);

        mockMvc.perform(post("/api/restrictions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest(
                                new RestrictionRequest.ScopeRequest(Set.of(), Set.of(), Set.of())))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnAbsentScope() throws Exception {
        Organization organization = newOrganization();
        String token = tokenFor(organization);

        mockMvc.perform(post("/api/restrictions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest(null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAMissingReason() throws Exception {
        Organization organization = newOrganization();
        String token = tokenFor(organization);
        Long environmentId =
                environmentRepository.saveAndFlush(new Environment(organization.getId(), "production")).getId();

        RestrictionRequest request = new RestrictionRequest("No reason", null, null,
                RestrictionLevel.HARD_FREEZE, FUTURE_START, FUTURE_END,
                new RestrictionRequest.ScopeRequest(null, null, Set.of(environmentId)));

        mockMvc.perform(post("/api/restrictions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsABlankReason() throws Exception {
        Organization organization = newOrganization();
        String token = tokenFor(organization);
        Long environmentId =
                environmentRepository.saveAndFlush(new Environment(organization.getId(), "production")).getId();

        RestrictionRequest request = new RestrictionRequest("Blank reason", null, "   ",
                RestrictionLevel.HARD_FREEZE, FUTURE_START, FUTURE_END,
                new RestrictionRequest.ScopeRequest(null, null, Set.of(environmentId)));

        mockMvc.perform(post("/api/restrictions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAMissingName() throws Exception {
        Organization organization = newOrganization();
        String token = tokenFor(organization);
        Long environmentId =
                environmentRepository.saveAndFlush(new Environment(organization.getId(), "production")).getId();

        RestrictionRequest request = new RestrictionRequest(null, null, "Reason",
                RestrictionLevel.HARD_FREEZE, FUTURE_START, FUTURE_END,
                new RestrictionRequest.ScopeRequest(null, null, Set.of(environmentId)));

        mockMvc.perform(post("/api/restrictions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAMissingLevel() throws Exception {
        Organization organization = newOrganization();
        String token = tokenFor(organization);
        Long environmentId =
                environmentRepository.saveAndFlush(new Environment(organization.getId(), "production")).getId();

        RestrictionRequest request = new RestrictionRequest("No level", null, "Reason",
                null, FUTURE_START, FUTURE_END,
                new RestrictionRequest.ScopeRequest(null, null, Set.of(environmentId)));

        mockMvc.perform(post("/api/restrictions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnUnknownScopeResource() throws Exception {
        Organization organization = newOrganization();
        String token = tokenFor(organization);

        mockMvc.perform(post("/api/restrictions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest(
                                new RestrictionRequest.ScopeRequest(null, null, Set.of(999_999L))))))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsScopeResourcesOwnedByAnotherOrganizationAndPersistsNothing() throws Exception {
        Organization orgA = newOrganization();
        Organization orgB = newOrganization();
        String tokenA = tokenFor(orgA);

        Long ownEnvironmentId =
                environmentRepository.saveAndFlush(new Environment(orgA.getId(), "production")).getId();
        Long foreignTeamId = teamRepository.saveAndFlush(new Team(orgB.getId(), "Org B Team")).getId();

        long before = changeRestrictionRepository.count();

        mockMvc.perform(post("/api/restrictions")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest(new RestrictionRequest.ScopeRequest(
                                Set.of(foreignTeamId), null, Set.of(ownEnvironmentId))))))
                .andExpect(status().isNotFound());

        // The whole creation must roll back - no orphan restriction row.
        assertThat(changeRestrictionRepository.count()).isEqualTo(before);
    }

    @Test
    void deduplicatesRepeatedScopeIds() throws Exception {
        Organization organization = newOrganization();
        String token = tokenFor(organization);
        Long environmentId =
                environmentRepository.saveAndFlush(new Environment(organization.getId(), "production")).getId();

        // Built as raw JSON rather than from a Set, so the duplicate genuinely reaches
        // the endpoint: a Set would collapse it before serialisation and the test would
        // pass without exercising anything.
        String body = """
                {
                  "name": "Duplicate scope ids",
                  "reason": "Revenue-critical period",
                  "level": "HARD_FREEZE",
                  "startsAt": "%s",
                  "endsAt": "%s",
                  "scope": { "environmentIds": [%d, %d] }
                }
                """.formatted(FUTURE_START, FUTURE_END, environmentId, environmentId);

        mockMvc.perform(post("/api/restrictions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope.environmentIds", hasSize(1)))
                .andExpect(jsonPath("$.scope.environmentIds", contains(environmentId.intValue())));
    }

}
