package com.freezhub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The security property behind FZ-035, asserted rather than assumed: the development
 * sign-in endpoint must not exist outside the {@code local} profile.
 *
 * <p>Note the deliberate absence of {@code @ActiveProfiles("local")}. Every property below
 * is therefore one a deployed environment must also supply, and the context failing to
 * start without them is the fail-fast 06-security.md describes rather than an inconvenience.
 *
 * <p>Since FZ-046 the real {@link CognitoIdentityProvider} boots here instead of a stub,
 * which makes this context genuinely deployed-shaped and asserts something worth asserting:
 * the adapter and its SDK client construct with no AWS credentials present. That is the
 * situation in CI, and the situation at container start before the instance profile is
 * first used. Only {@code JwtDecoder} is still stubbed, because that one needs an issuer
 * URI a real pool serves.
 */
@SpringBootTest(properties = {
        "freezehub.lifecycle.enabled=false",
        // Supplied here for the same reason a deployed environment must supply it: outside
        // the local profile there is no default key, and the context will not start
        // without one (FZ-049).
        "freezehub.secrets.encryption-key=ZGV2ZWxvcG1lbnQtb25seS1rZXktbm90LXNlY3JldCE=",
        // Likewise, and for the same reason (FZ-046): neither has a default.
        "freezehub.cognito.user-pool-id=us-east-2_example",
        "freezehub.cognito.region=us-east-2"
})
@AutoConfigureMockMvc
@Import({ContainersConfig.class, DevSignInAbsentOutsideLocalProfileTest.DeployedShapedStubs.class})
class DevSignInAbsentOutsideLocalProfileTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void theDevSignInControllerIsNotRegistered() {
        assertThat(applicationContext.getBeanNamesForType(DevSignInController.class)).isEmpty();
    }

    @Test
    void theDevSignInSecurityChainIsNotRegistered() {
        assertThat(applicationContext.getBeanNamesForType(DevSignInSecurityConfig.class)).isEmpty();
    }

    @Test
    void theDevSignInPathIsNotPubliclyReachable() throws Exception {
        // Without the profile-scoped chain the path falls through to the main chain, which
        // demands authentication - so it is never an open door in a deployed environment.
        mockMvc.perform(post("/api/dev/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"anyone@acme.test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    static class DeployedShapedStubs {

        /** Stands in for the real Cognito decoder a deployed environment configures. */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new InvalidBearerTokenException("stub decoder");
            };
        }

        // No IdentityProvider stub since FZ-046: the real CognitoIdentityProvider is the
        // bean here, and that it constructs without credentials is part of what this
        // context now proves.
    }

}
