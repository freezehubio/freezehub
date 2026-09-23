package com.freezhub.restriction;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import com.freezhub.shared.security.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Who may change a freeze, and who may only read one (`FZ-190`).
 *
 * <p>`00-product.md` describes the Engineer as somebody who "checks active/upcoming
 * restrictions and determines whether their application/environment is affected" — read-only,
 * in the document, since before the role model existed. The schema had two roles and let
 * either do anything, so the document and the code disagreed and the code won.
 *
 * <p><b>Asserted both ways on purpose.</b> A test that only proves a member is refused would
 * still pass if the endpoint were broken for everyone, which is the more likely mistake when
 * annotations are added in bulk.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class RestrictionRoleBoundaryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    /**
     * A fresh organization per test, with subjects unique to it.
     *
     * <p>`users.external_subject` is globally unique — deliberately, since the Cognito pool is
     * shared across every organization — so a fixture reusing one subject passes the first
     * test and fails every test after it.
     */
    private record Actors(String admin, String member) {}

    private Actors actors() {
        long n = System.nanoTime();
        Organization organization = organizationRepository.saveAndFlush(new Organization("Acme " + n));
        userRepository.saveAndFlush(new User(
                organization.getId(), "admin-" + n, "admin-" + n + "@acme.test", UserRole.ADMINISTRATOR));
        userRepository.saveAndFlush(new User(
                organization.getId(), "member-" + n, "member-" + n + "@acme.test", UserRole.MEMBER));
        return new Actors(
                TestTokens.forSubject(jwtEncoder, "admin-" + n),
                TestTokens.forSubject(jwtEncoder, "member-" + n));
    }

    @Test
    void aMemberMayReadRestrictions() throws Exception {
        // The half that matters for the product: an engineer's whole use of FreezeHub is
        // finding out whether they can deploy. Breaking that would defeat the point.
        mockMvc.perform(get("/api/restrictions").header("Authorization", "Bearer " + actors().member()))
                .andExpect(status().isOk());
    }

    @Test
    void aMemberMayNotCreateARestriction() throws Exception {
        // A VALID body, deliberately. Spring binds and validates the request body during
        // argument resolution, which happens before @PreAuthorize runs on the method — so an
        // empty body answers 400 and proves nothing about the guard. Found by writing the
        // test with "{}" and getting 400 where 403 was expected.
        String body = """
                {"name":"Peak trading","reason":"Revenue-critical period",
"level":"HARD_FREEZE","startsAt":"2027-01-01T00:00:00Z","endsAt":"2027-01-05T00:00:00Z"}
                """;

        mockMvc.perform(post("/api/restrictions")
                        .header("Authorization", "Bearer " + actors().member())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void aMemberMayNotCancelARestriction() throws Exception {
        // 403 before 404: the refusal must not depend on the restriction existing, or the
        // endpoint becomes a way to ask which ids are real.
        mockMvc.perform(post("/api/restrictions/1/cancel")
                        .header("Authorization", "Bearer " + actors().member()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aMemberMayNotChangeTheCatalog() throws Exception {
        mockMvc.perform(post("/api/applications")
                        .header("Authorization", "Bearer " + actors().member())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"payments-api\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aMemberMayStillReadTheCatalog() throws Exception {
        mockMvc.perform(get("/api/applications").header("Authorization", "Bearer " + actors().member()))
                .andExpect(status().isOk());
    }

    @Test
    void anAdministratorIsNotRefused() throws Exception {
        // The other direction. Adding @PreAuthorize in bulk is exactly how a guard lands on
        // the wrong method, and a one-sided test would not notice.
        mockMvc.perform(post("/api/applications")
                        .header("Authorization", "Bearer " + actors().admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"checkout-api\"}"))
                .andExpect(status().isCreated());
    }
}
