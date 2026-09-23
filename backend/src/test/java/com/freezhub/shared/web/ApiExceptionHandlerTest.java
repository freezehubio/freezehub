package com.freezhub.shared.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.catalog.Team;
import com.freezhub.catalog.TeamRepository;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import com.freezhub.shared.security.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The error contract (FZ-061): RFC 9457 Problem Details, one shape for the whole API.
 *
 * <p>Two properties are load-bearing and pull in opposite directions — a deliberate
 * rejection must explain itself, and an unexpected failure must not. Both are asserted.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import({ContainersConfig.class, ApiExceptionHandlerTest.ExplodingEndpoint.class})
class ApiExceptionHandlerTest {

    private static final String SECRET_INTERNAL_DETAIL = "connection to shard-7 refused";

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

    private Long organizationId;
    private String memberToken;
    private String adminToken;

    @BeforeEach
    void givenAnOrganization() {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        organizationId = organization.getId();

        String member = "member-" + System.nanoTime();
        userRepository.saveAndFlush(
                new User(organizationId, member, member + "@acme.test", UserRole.MEMBER));
        memberToken = TestTokens.forSubject(jwtEncoder, member);

        String admin = "admin-" + System.nanoTime();
        userRepository.saveAndFlush(
                new User(organizationId, admin, admin + "@acme.test", UserRole.ADMINISTRATOR));
        adminToken = TestTokens.forSubject(jwtEncoder, admin);
    }

    @Test
    void carriesADeliberateRejectionsOwnReason() throws Exception {
        // The API states plenty of specific reasons; Spring discarded every one of them
        // before the client saw it, which is what this contract fixes.
        teamRepository.saveAndFlush(new Team(organizationId, "Payments"));

        mockMvc.perform(post("/api/teams")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Payments\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("already exists")))
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.title", is("Conflict")))
                .andExpect(jsonPath("$.instance", is("/api/teams")))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void namesTheFieldThatFailedValidation() throws Exception {
        // "The request is invalid" is not something a form can act on, and not something
        // a person can fix without guessing.
        mockMvc.perform(post("/api/teams")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field", is("name")))
                .andExpect(jsonPath("$.errors[0].message", containsString("blank")))
                // Readable on its own, for a client that ignores the extension.
                .andExpect(jsonPath("$.detail", containsString("name")));
    }

    @Test
    void reportsEveryInvalidFieldRatherThanTheFirst() throws Exception {
        mockMvc.perform(post("/api/restrictions")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()", org.hamcrest.Matchers.greaterThan(1)))
                .andExpect(jsonPath("$.detail", containsString("invalid fields")));
    }

    @Test
    void rejectsAnUnreadableBodyWithoutQuotingIt() throws Exception {
        // Jackson's own message quotes the offending JSON and names the Java types it
        // tried to bind. Neither belongs in a response.
        mockMvc.perform(post("/api/teams")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": not json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("malformed")))
                .andExpect(jsonPath("$.detail", not(containsString("com.freezhub"))))
                .andExpect(jsonPath("$.detail", not(containsString("Jackson"))));
    }

    @Test
    void rejectsAnUnsupportedQueryValue() throws Exception {
        mockMvc.perform(get("/api/restrictions?status=NOT_A_STATUS")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("status")))
                .andExpect(jsonPath("$.detail", not(containsString("java.lang"))));
    }

    @Test
    void explainsAForbiddenActionInTheSameShape() throws Exception {
        mockMvc.perform(get("/api/audit").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.detail", containsString("administrator")));
    }

    @Test
    void usesTheSameShapeForNotFound() throws Exception {
        mockMvc.perform(get("/api/restrictions/999999").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.title", is("Not Found")))
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void neverLetsAnUnexpectedFailureExplainItself() throws Exception {
        // The counterweight to every test above. A deliberate rejection carries its
        // reason; an unexpected exception carries none, because its message is internal
        // detail — hostnames, table names, occasionally values.
        mockMvc.perform(get("/api/test-only/explode").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status", is(500)))
                .andExpect(jsonPath("$.detail", is("The request could not be completed.")))
                .andExpect(content().string(not(containsString(SECRET_INTERNAL_DETAIL))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    /** An endpoint that fails the way real code fails, so the 500 path can be asserted. */
    @TestConfiguration
    static class ExplodingEndpoint {

        @Bean
        BrokenController brokenController() {
            return new BrokenController();
        }
    }

    @RestController
    static class BrokenController {

        @GetMapping("/api/test-only/explode")
        String explode() {
            throw new IllegalStateException(SECRET_INTERNAL_DETAIL);
        }
    }

}
