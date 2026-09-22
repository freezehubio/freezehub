package com.freezhub.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.freezhub.ContainersConfig;
import com.freezhub.subscription.SubscriptionService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A signup that fails part-way leaves nothing behind (FZ-082).
 *
 * <p>Its own class because it replaces a bean, which means its own application context.
 *
 * <p>The failure is injected at the last step of the transaction — starting the trial —
 * because that is the worst case: the organization and the user have already been written
 * and the Cognito identity already exists. If anything survives this, it survives every
 * earlier failure too.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class SignupRollbackTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private UserRepository users;

    @MockitoBean
    private SubscriptionService subscriptions;

    @Test
    void leavesNoOrphanOrganizationNoOrphanUserAndNoOrphanIdentity() throws Exception {
        doThrow(new IllegalStateException("the trial could not be started"))
                .when(subscriptions).startTrial(anyLong(), any(), any());

        String email = "founder-" + UUID.randomUUID() + "@northwind.test";
        String company = "Half Written " + UUID.randomUUID();

        mockMvc.perform(post("/api/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"company\":\"" + company + "\",\"email\":\"" + email + "\"}"));

        // The rows rolled back with the transaction.
        assertThat(users.findAllByEmail(email)).isEmpty();
        assertThat(organizations.findAll())
                .extracting(Organization::getName)
                .doesNotContain(company);
    }

    /**
     * The identity is the part that cannot roll back on its own, so it gets its own
     * assertion — and one made through behaviour rather than by reaching into the
     * provider. If the compensating delete had not run, the address would still be taken
     * and this second signup would be swallowed as a duplicate, creating nothing.
     */
    @Test
    void releasesTheAddressSoTheSamePersonCanTryAgain() throws Exception {
        doThrow(new IllegalStateException("the trial could not be started"))
                .when(subscriptions).startTrial(anyLong(), any(), any());

        String email = "founder-" + UUID.randomUUID() + "@northwind.test";
        String company = "Half Written " + UUID.randomUUID();
        String body = "{\"company\":\"" + company + "\",\"email\":\"" + email + "\"}";

        mockMvc.perform(post("/api/signup").contentType(MediaType.APPLICATION_JSON).content(body));

        // Whatever went wrong is now fixed.
        org.mockito.Mockito.reset(subscriptions);

        mockMvc.perform(post("/api/signup").contentType(MediaType.APPLICATION_JSON).content(body));

        assertThat(users.findAllByEmail(email)).hasSize(1);
    }

}
