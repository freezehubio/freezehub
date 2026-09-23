package com.freezhub.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.ChangeRestrictionRepository;
import com.freezhub.restriction.RestrictionLevel;
import com.freezhub.shared.security.TestTokens;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Closes the known gap left open by FZ-020: the database already refused to delete a
 * catalog entry a restriction referenced, but the refusal surfaced as an unhandled 500.
 * FZ-036 puts delete buttons in front of users, so it has to be a 409 they can act on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class CatalogDeletionInUseTest {

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

    private record Fixture(Organization organization, String token, Long userId) {
    }

    private Fixture given() {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        String subject = "subject-" + System.nanoTime();
        User user = userRepository.saveAndFlush(
                new User(organization.getId(), subject, subject + "@acme.test", UserRole.ADMINISTRATOR));
        return new Fixture(organization, TestTokens.forSubject(jwtEncoder, subject), user.getId());
    }

    private void givenRestrictionScopedTo(Fixture fixture, Set<Long> teamIds,
                                          Set<Long> applicationIds, Set<Long> environmentIds) {
        Instant startsAt = Instant.now().plus(5, ChronoUnit.DAYS);
        changeRestrictionRepository.saveAndFlush(new ChangeRestriction(
                fixture.organization().getId(), "Freeze " + System.nanoTime(), null, "Reason",
                RestrictionLevel.HARD_FREEZE, startsAt, startsAt.plus(1, ChronoUnit.DAYS),
                fixture.userId(), teamIds, applicationIds, environmentIds));
    }

    @Test
    void refusesToDeleteATeamThatARestrictionReferences() throws Exception {
        Fixture fixture = given();
        Long teamId = teamRepository
                .saveAndFlush(new Team(fixture.organization().getId(), "Payments " + System.nanoTime())).getId();
        givenRestrictionScopedTo(fixture, Set.of(teamId), Set.of(), Set.of());

        mockMvc.perform(delete("/api/teams/" + teamId)
                        .header("Authorization", "Bearer " + fixture.token()))
                .andExpect(status().isConflict());

        // Refused, not partially applied: the restriction's scope is still truthful.
        assertThat(teamRepository.findById(teamId)).isPresent();
    }

    @Test
    void refusesToDeleteAnApplicationThatARestrictionReferences() throws Exception {
        Fixture fixture = given();
        Long applicationId = applicationRepository
                .saveAndFlush(new Application(fixture.organization().getId(), "api-" + System.nanoTime())).getId();
        givenRestrictionScopedTo(fixture, Set.of(), Set.of(applicationId), Set.of());

        mockMvc.perform(delete("/api/applications/" + applicationId)
                        .header("Authorization", "Bearer " + fixture.token()))
                .andExpect(status().isConflict());

        assertThat(applicationRepository.findById(applicationId)).isPresent();
    }

    @Test
    void refusesToDeleteAnEnvironmentThatARestrictionReferences() throws Exception {
        Fixture fixture = given();
        Long environmentId = environmentRepository
                .saveAndFlush(new Environment(fixture.organization().getId(), "prod-" + System.nanoTime())).getId();
        givenRestrictionScopedTo(fixture, Set.of(), Set.of(), Set.of(environmentId));

        mockMvc.perform(delete("/api/environments/" + environmentId)
                        .header("Authorization", "Bearer " + fixture.token()))
                .andExpect(status().isConflict());

        assertThat(environmentRepository.findById(environmentId)).isPresent();
    }

    @Test
    void stillDeletesAnUnreferencedTeam() throws Exception {
        // The guard must not have turned delete into something that never works.
        Fixture fixture = given();
        Long teamId = teamRepository
                .saveAndFlush(new Team(fixture.organization().getId(), "Unused " + System.nanoTime())).getId();

        mockMvc.perform(delete("/api/teams/" + teamId)
                        .header("Authorization", "Bearer " + fixture.token()))
                .andExpect(status().isNoContent());

        assertThat(teamRepository.findById(teamId)).isEmpty();
    }

}
