package com.freezhub.catalog;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.ObjectMapper;
import com.freezhub.ContainersConfig;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import com.freezhub.shared.security.TestTokens;
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
class ApplicationControllerTest {

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String tokenForNewUser(String subjectSuffix, Organization organization) {
        userRepository.saveAndFlush(new User(
                organization.getId(), "subject-" + subjectSuffix, subjectSuffix + "@acme.test", UserRole.ADMINISTRATOR));
        return TestTokens.forSubject(jwtEncoder, "subject-" + subjectSuffix);
    }

    private Long createApplication(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApplicationRequest(name))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get("/api/applications")).andExpect(status().isUnauthorized());
    }

    @Test
    void fullLifecycle_createListGetRenameDelete() throws Exception {
        Organization organization = organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        String token = tokenForNewUser("lifecycle-" + System.nanoTime(), organization);

        Long appId = createApplication(token, "payments-api");

        mockMvc.perform(get("/api/applications").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].teamIds", is(empty())));

        mockMvc.perform(get("/api/applications/" + appId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("payments-api")));

        mockMvc.perform(patch("/api/applications/" + appId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApplicationRequest("payments-service"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("payments-service")));

        mockMvc.perform(delete("/api/applications/" + appId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/applications/" + appId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsDuplicateNameWithinTheSameOrganization() throws Exception {
        Organization organization = organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        String token = tokenForNewUser("dup-" + System.nanoTime(), organization);

        createApplication(token, "checkout-web");

        mockMvc.perform(post("/api/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApplicationRequest("checkout-web"))))
                .andExpect(status().isConflict());
    }

    @Test
    void anApplicationIsNotVisibleFromAnotherOrganization() throws Exception {
        Organization orgA = organizationRepository.saveAndFlush(new Organization("Org A " + System.nanoTime()));
        Organization orgB = organizationRepository.saveAndFlush(new Organization("Org B " + System.nanoTime()));
        String tokenA = tokenForNewUser("orgA-" + System.nanoTime(), orgA);
        String tokenB = tokenForNewUser("orgB-" + System.nanoTime(), orgB);

        Long appId = createApplication(tokenA, "identity-service");

        mockMvc.perform(get("/api/applications/" + appId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void associatesAndDisassociatesATeam_idempotently() throws Exception {
        Organization organization = organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        String token = tokenForNewUser("assoc-" + System.nanoTime(), organization);
        Long appId = createApplication(token, "checkout-api");
        Long teamId = teamRepository.saveAndFlush(new Team(organization.getId(), "Checkout Team")).getId();

        mockMvc.perform(put("/api/applications/" + appId + "/teams/" + teamId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // idempotent: associating again does not error or duplicate
        mockMvc.perform(put("/api/applications/" + appId + "/teams/" + teamId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/applications/" + appId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamIds", contains(teamId.intValue())));

        mockMvc.perform(delete("/api/applications/" + appId + "/teams/" + teamId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // idempotent: disassociating again does not error
        mockMvc.perform(delete("/api/applications/" + appId + "/teams/" + teamId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/applications/" + appId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamIds", is(empty())));
    }

    @Test
    void rejectsAssociatingATeamFromAnotherOrganization() throws Exception {
        Organization orgA = organizationRepository.saveAndFlush(new Organization("Org A " + System.nanoTime()));
        Organization orgB = organizationRepository.saveAndFlush(new Organization("Org B " + System.nanoTime()));
        String tokenA = tokenForNewUser("orgA2-" + System.nanoTime(), orgA);
        Long appId = createApplication(tokenA, "fraud-service");
        Long foreignTeamId = teamRepository.saveAndFlush(new Team(orgB.getId(), "Org B Team")).getId();

        mockMvc.perform(put("/api/applications/" + appId + "/teams/" + foreignTeamId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingAnApplicationWithAnAssociatedTeamSucceeds() throws Exception {
        Organization organization = organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        String token = tokenForNewUser("cascade-" + System.nanoTime(), organization);
        Long appId = createApplication(token, "billing-api");
        Long teamId = teamRepository.saveAndFlush(new Team(organization.getId(), "Billing Team")).getId();

        mockMvc.perform(put("/api/applications/" + appId + "/teams/" + teamId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/applications/" + appId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

}
