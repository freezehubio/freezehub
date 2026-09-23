package com.freezhub.catalog;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class TeamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String tokenForNewUser(String subjectSuffix, Organization organization) {
        userRepository.saveAndFlush(new User(
                organization.getId(), "subject-" + subjectSuffix, subjectSuffix + "@acme.test", UserRole.ADMINISTRATOR));
        return TestTokens.forSubject(jwtEncoder, "subject-" + subjectSuffix);
    }

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get("/api/teams")).andExpect(status().isUnauthorized());
    }

    @Test
    void fullLifecycle_createListGetRenameDelete() throws Exception {
        Organization organization = organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        String token = tokenForNewUser("lifecycle-" + System.nanoTime(), organization);

        String createBody = mockMvc.perform(post("/api/teams")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TeamRequest("Payments"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("Payments")))
                .andReturn().getResponse().getContentAsString();
        Long teamId = objectMapper.readTree(createBody).get("id").asLong();

        mockMvc.perform(get("/api/teams").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("Payments")));

        mockMvc.perform(get("/api/teams/" + teamId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Payments")));

        mockMvc.perform(patch("/api/teams/" + teamId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TeamRequest("Payments Platform"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Payments Platform")));

        mockMvc.perform(delete("/api/teams/" + teamId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/teams/" + teamId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsDuplicateNameWithinTheSameOrganization() throws Exception {
        Organization organization = organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        String token = tokenForNewUser("dup-" + System.nanoTime(), organization);

        mockMvc.perform(post("/api/teams")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TeamRequest("Checkout"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/teams")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TeamRequest("Checkout"))))
                .andExpect(status().isConflict());
    }

    @Test
    void aTeamIsNotVisibleFromAnotherOrganization() throws Exception {
        Organization orgA = organizationRepository.saveAndFlush(new Organization("Org A " + System.nanoTime()));
        Organization orgB = organizationRepository.saveAndFlush(new Organization("Org B " + System.nanoTime()));
        String tokenA = tokenForNewUser("orgA-" + System.nanoTime(), orgA);
        String tokenB = tokenForNewUser("orgB-" + System.nanoTime(), orgB);

        String createBody = mockMvc.perform(post("/api/teams")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TeamRequest("Identity"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long teamId = objectMapper.readTree(createBody).get("id").asLong();

        mockMvc.perform(get("/api/teams/" + teamId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/teams").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(delete("/api/teams/" + teamId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // Rename was the one verb this had never checked (`FZ-065`). It is also the one
        // with teeth: because an unrecognised name blocks (`D-14`), renaming another
        // organization's application would turn every pipeline still sending the old name
        // into a refusal — a denial of service written as an edit.
        mockMvc.perform(patch("/api/teams/" + teamId)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TeamRequest("Renamed"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/teams/" + teamId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Identity")));
    }

}
