package com.freezhub.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.audit.AuditAction;
import com.freezhub.audit.AuditActor;
import com.freezhub.audit.AuditEvent;
import com.freezhub.audit.AuditRepository;
import com.freezhub.shared.security.TestTokens;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * Managing who belongs to an organization (FZ-212).
 *
 * <p>Every address and subject is unique per test. The container is shared and nothing is
 * cleaned between tests, and since FZ-195 the local identity provider refuses any address
 * already in the database — a fixed address would make these depend on test order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class MemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private UserRepository users;

    @Autowired
    private AuditRepository auditEvents;

    @Autowired
    private MemberService memberService;

    private record Person(User user, String token) {
    }

    private Organization organization() {
        return organizations.saveAndFlush(new Organization("Acme " + UUID.randomUUID()));
    }

    private Person person(Organization organization, UserRole role) {
        String unique = UUID.randomUUID().toString();
        User user = users.saveAndFlush(new User(organization.getId(), "subject-" + unique,
                role.name().toLowerCase() + "-" + unique + "@acme.test", role));
        return new Person(user, TestTokens.forSubject(jwtEncoder, user.getExternalSubject()));
    }

    private ResultActions as(Person caller, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + caller.token()));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder role(Long id, UserRole role) {
        return patch("/api/members/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"" + role + "\"}");
    }

    private List<AuditEvent> auditFor(Organization organization) {
        return auditEvents.findAll().stream()
                .filter(event -> event.getOrganizationId().equals(organization.getId()))
                .toList();
    }

    // ---- who may see and do what ----------------------------------------------------------

    @Test
    void anAdministratorSeesEveryoneInTheirOrganizationAndNobodyElse() throws Exception {
        Organization acme = organization();
        Person admin = person(acme, UserRole.ADMINISTRATOR);
        Person member = person(acme, UserRole.MEMBER);
        Person elsewhere = person(organization(), UserRole.MEMBER);

        as(admin, get("/api/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].email").value(org.hamcrest.Matchers.containsInAnyOrder(
                        admin.user().getEmail(), member.user().getEmail())))
                .andExpect(jsonPath("$[*].email").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(elsewhere.user().getEmail()))));
    }

    /** The list is personal data and nothing a member does needs it. */
    @Test
    void aMemberCannotListOrChangeAnyone() throws Exception {
        Organization acme = organization();
        person(acme, UserRole.ADMINISTRATOR);
        Person member = person(acme, UserRole.MEMBER);

        as(member, get("/api/members")).andExpect(status().isForbidden());
        as(member, role(member.user().getId(), UserRole.ADMINISTRATOR)).andExpect(status().isForbidden());
        as(member, post("/api/members/" + member.user().getId() + "/deactivate")).andExpect(status().isForbidden());
    }

    /** Existence is never revealed across tenants: another organization's member is a 404. */
    @Test
    void anotherOrganizationsMemberIsNotFoundRatherThanForbidden() throws Exception {
        Person admin = person(organization(), UserRole.ADMINISTRATOR);
        Person stranger = person(organization(), UserRole.MEMBER);

        as(admin, role(stranger.user().getId(), UserRole.ADMINISTRATOR)).andExpect(status().isNotFound());
        as(admin, post("/api/members/" + stranger.user().getId() + "/deactivate")).andExpect(status().isNotFound());
        assertThat(users.findById(stranger.user().getId()).orElseThrow().getRole()).isEqualTo(UserRole.MEMBER);
    }

    // ---- roles -----------------------------------------------------------------------------

    @Test
    void promotingAMemberIsAuditedWithWhatChanged() throws Exception {
        Organization acme = organization();
        Person admin = person(acme, UserRole.ADMINISTRATOR);
        Person member = person(acme, UserRole.MEMBER);

        as(admin, role(member.user().getId(), UserRole.ADMINISTRATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMINISTRATOR"));

        AuditEvent event = auditFor(acme).stream()
                .filter(e -> e.getAction() == AuditAction.USER_ROLE_CHANGED).findFirst().orElseThrow();
        assertThat(event.getResourceId()).isEqualTo(member.user().getId());
        assertThat(event.getDetails()).contains("\"from\":\"MEMBER\"").contains("\"to\":\"ADMINISTRATOR\"");
    }

    /** Nothing in the product could recover from an organization with no administrator. */
    @Test
    void theOnlyActiveAdministratorCannotBeDemoted() throws Exception {
        Person admin = person(organization(), UserRole.ADMINISTRATOR);

        as(admin, role(admin.user().getId(), UserRole.MEMBER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("only active administrator")));
        assertThat(users.findById(admin.user().getId()).orElseThrow().getRole()).isEqualTo(UserRole.ADMINISTRATOR);
    }

    @Test
    void anAdministratorCanBeDemotedWhileAnotherRemains() throws Exception {
        Organization acme = organization();
        Person first = person(acme, UserRole.ADMINISTRATOR);
        Person second = person(acme, UserRole.ADMINISTRATOR);

        as(first, role(second.user().getId(), UserRole.MEMBER)).andExpect(status().isOk());
    }

    /** A deactivated administrator cannot administer anything, so they do not count. */
    @Test
    void aDeactivatedAdministratorDoesNotCountAsTheOtherOne() throws Exception {
        Organization acme = organization();
        Person active = person(acme, UserRole.ADMINISTRATOR);
        Person gone = person(acme, UserRole.ADMINISTRATOR);
        User goneUser = gone.user();
        goneUser.deactivate(java.time.Instant.now());
        users.saveAndFlush(goneUser);

        as(active, role(active.user().getId(), UserRole.MEMBER)).andExpect(status().isConflict());
    }

    // ---- removal ---------------------------------------------------------------------------

    /**
     * The point of removal: it takes effect on the next request, not when the person's token
     * expires. An access token stays valid for up to an hour.
     */
    @Test
    void aDeactivatedMemberIsRefusedOnTheirNextRequestAndReinstatedWhenReactivated() throws Exception {
        Organization acme = organization();
        Person admin = person(acme, UserRole.ADMINISTRATOR);
        Person member = person(acme, UserRole.MEMBER);

        as(member, get("/api/me")).andExpect(status().isOk());

        as(admin, post("/api/members/" + member.user().getId() + "/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        as(member, get("/api/me")).andExpect(status().isUnauthorized());

        as(admin, post("/api/members/" + member.user().getId() + "/reactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        as(member, get("/api/me")).andExpect(status().isOk());

        assertThat(auditFor(acme)).extracting(AuditEvent::getAction)
                .contains(AuditAction.USER_DEACTIVATED, AuditAction.USER_REACTIVATED);
    }

    /** Removal keeps the row, so what the person did stays attributed to them. */
    @Test
    void deactivationKeepsThePersonRatherThanDeletingThem() throws Exception {
        Organization acme = organization();
        Person admin = person(acme, UserRole.ADMINISTRATOR);
        Person member = person(acme, UserRole.MEMBER);

        as(admin, post("/api/members/" + member.user().getId() + "/deactivate")).andExpect(status().isOk());

        User kept = users.findById(member.user().getId()).orElseThrow();
        assertThat(kept.isActive()).isFalse();
        assertThat(kept.getDeactivatedAt()).isNotNull();
        as(admin, get("/api/members")).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void theOnlyActiveAdministratorCannotBeDeactivated() throws Exception {
        Person admin = person(organization(), UserRole.ADMINISTRATOR);

        as(admin, post("/api/members/" + admin.user().getId() + "/deactivate")).andExpect(status().isConflict());
        as(admin, get("/api/me")).andExpect(status().isOk());
    }

    /**
     * Why every change locks the organization first. Two administrators demoting each other
     * at the same moment would otherwise each count two, each succeed, and leave none.
     */
    @Test
    void twoAdministratorsDemotingEachOtherAtOnceLeaveOneStanding() throws Exception {
        Organization acme = organization();
        Person first = person(acme, UserRole.ADMINISTRATOR);
        Person second = person(acme, UserRole.ADMINISTRATOR);

        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> firstDemotesSecond = () -> {
            start.await();
            return attempt(acme, first, second);
        };
        Callable<Boolean> secondDemotesFirst = () -> {
            start.await();
            return attempt(acme, second, first);
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(firstDemotesSecond);
            Future<Boolean> b = pool.submit(secondDemotesFirst);
            start.countDown();

            // Exactly one wins, whichever it is.
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        assertThat(users.countByOrganizationIdAndRoleAndDeactivatedAtIsNull(acme.getId(), UserRole.ADMINISTRATOR))
                .isEqualTo(1);
    }

    private boolean attempt(Organization organization, Person caller, Person target) {
        try {
            memberService.changeRole(organization.getId(), AuditActor.system(), target.user().getId(), UserRole.MEMBER);
            return true;
        } catch (ResponseStatusException refused) {
            return false;
        }
    }

    // ---- invitations -----------------------------------------------------------------------

    /** USER_INVITED existed from the start and nothing recorded it. */
    @Test
    void anInvitationIsAudited() throws Exception {
        Organization acme = organization();
        Person admin = person(acme, UserRole.ADMINISTRATOR);
        String email = "new-" + UUID.randomUUID() + "@acme.test";

        as(admin, post("/api/invites").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isCreated());

        AuditEvent event = auditFor(acme).stream()
                .filter(e -> e.getAction() == AuditAction.USER_INVITED).findFirst().orElseThrow();
        assertThat(event.getDetails()).contains(email).contains("\"role\":\"MEMBER\"");
    }

    @Test
    void invitingSomeoneAlreadyInTheOrganizationSaysSo() throws Exception {
        Organization acme = organization();
        Person admin = person(acme, UserRole.ADMINISTRATOR);
        Person member = person(acme, UserRole.MEMBER);

        as(admin, post("/api/invites").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + member.user().getEmail() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("already a member")));
    }

    /** A removed member is one click away, and the refusal says which click. */
    @Test
    void invitingSomeoneWhoWasRemovedPointsAtReactivation() throws Exception {
        Organization acme = organization();
        Person admin = person(acme, UserRole.ADMINISTRATOR);
        Person member = person(acme, UserRole.MEMBER);
        as(admin, post("/api/members/" + member.user().getId() + "/deactivate")).andExpect(status().isOk());

        as(admin, post("/api/invites").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + member.user().getEmail() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Reactivate")));
    }

    /**
     * Cognito holds one login per address across every organization. This used to reach the
     * caller as a 500.
     */
    @Test
    void invitingSomeoneFromAnotherOrganizationIsAClearConflict() throws Exception {
        Person admin = person(organization(), UserRole.ADMINISTRATOR);
        Person elsewhere = person(organization(), UserRole.MEMBER);

        as(admin, post("/api/invites").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + elsewhere.user().getEmail() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("another organization")));
    }

    // ---- knock-on ---------------------------------------------------------------------------

    /** A former administrator is not who a failed payment should be explained to. */
    @Test
    void billingIsToldOnlyToActiveAdministrators() {
        Organization acme = organization();
        Person active = person(acme, UserRole.ADMINISTRATOR);
        Person gone = person(acme, UserRole.ADMINISTRATOR);
        User goneUser = gone.user();
        goneUser.deactivate(java.time.Instant.now());
        users.saveAndFlush(goneUser);

        assertThat(users.findAllByOrganizationIdAndRoleAndDeactivatedAtIsNull(acme.getId(), UserRole.ADMINISTRATOR))
                .extracting(User::getEmail)
                .containsExactly(active.user().getEmail());
    }
}
