package com.freezhub.shared.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 06-security.md: "A test must present each rejected shape and assert a 401" (FZ-128,
 * OI-25). The unit test next door proves the validators reject; this proves the
 * application does, through the real filter chain, with a real signed token.
 *
 * <p>Every token below is <b>correctly signed and unexpired</b>. Signature and expiry are
 * what the library already checks, so a test using a forged or stale token would exercise
 * the wrong thing entirely — it would pass with none of this story's code present.
 *
 * <p>The decoder here verifies a locally-generated key instead of fetching Cognito's JWKS,
 * but carries {@link CognitoTokenValidators#forPool} — the same object the deployed decoder
 * uses. Substituting the key substitutes the one part that needs a network; substituting
 * the validators would be testing a different application.
 */
@SpringBootTest(properties = {
        "freezehub.lifecycle.enabled=false",
        "freezehub.secrets.encryption-key=ZGV2ZWxvcG1lbnQtb25seS1rZXktbm90LXNlY3JldCE=",
        "freezehub.cognito.user-pool-id=us-east-2_example",
        "freezehub.cognito.region=us-east-2",
        "freezehub.cognito.client-id=" + CognitoTokenRejectionTest.CLIENT,
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + CognitoTokenRejectionTest.ISSUER
})
@AutoConfigureMockMvc
@Import({ContainersConfig.class, CognitoTokenRejectionTest.LocallySignedCognitoDecoder.class})
class CognitoTokenRejectionTest {

    static final String ISSUER = "https://cognito-idp.us-east-2.amazonaws.com/us-east-2_example";
    static final String CLIENT = "1baicl02uro0in2mv6gsr88mfk";

    /**
     * How far a valid token gets: past every validator, refused only because this subject
     * has no user row. Its presence is what proves the token itself was accepted.
     */
    private static final String NOT_PROVISIONED = "No user provisioned for this identity";

    /** Any endpoint the main chain protects; this one exists and needs no fixtures. */
    private static final String PROTECTED = "/api/me";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    private String sign(Map<String, Object> claims) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS));
        claims.forEach(builder::claim);
        return jwtEncoder
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(SignatureAlgorithm.RS256).build(), builder.build()))
                .getTokenValue();
    }

    /**
     * A 401 is too weak an assertion here, and so is {@code invalid_token}: this endpoint
     * answers both when the token is <em>perfectly valid</em> but its subject matches no
     * user row, because the application reports that as {@code invalid_token} too. Every
     * test here would pass with none of this story's code present.
     *
     * <p>What discriminates is how far the request got. A token stopped by a validator
     * never reaches user provisioning, so {@link #NOT_PROVISIONED} must be absent from its
     * response — and present in the accepted case.
     */
    private void expectStoppedByAValidator(Map<String, Object> claims) throws Exception {
        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + sign(claims)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("invalid_token")))
                .andExpect(header().string("WWW-Authenticate", not(containsString(NOT_PROVISIONED))));
    }

    @Test
    void aGenuineAccessTokenIsNotRefused() throws Exception {
        // Without this the five below prove nothing: a decoder that rejected everything
        // would pass all of them. The token is accepted; what happens afterwards — this
        // subject has no user row — is another test's subject, so the assertion is only
        // that no validator refused it.
        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + sign(Map.of(
                        "iss", ISSUER,
                        "sub", "9f1d4c62-0000-4000-8000-000000000000",
                        "token_use", "access",
                        "client_id", CLIENT))))
                .andExpect(header().string("WWW-Authenticate", containsString(NOT_PROVISIONED)));
    }

    @Test
    void anIdTokenFromTheSamePoolIsRefused() throws Exception {
        expectStoppedByAValidator(Map.of(
                "iss", ISSUER,
                "sub", "9f1d4c62-0000-4000-8000-000000000001",
                "token_use", "id",
                "aud", CLIENT));
    }

    @Test
    void aTokenFromAnotherPoolIsRefused() throws Exception {
        expectStoppedByAValidator(Map.of(
                "iss", "https://cognito-idp.us-east-2.amazonaws.com/us-east-2_somebodyelse",
                "sub", "9f1d4c62-0000-4000-8000-000000000002",
                "token_use", "access",
                "client_id", CLIENT));
    }

    @Test
    void aTokenForAnotherAppClientIsRefused() throws Exception {
        expectStoppedByAValidator(Map.of(
                "iss", ISSUER,
                "sub", "9f1d4c62-0000-4000-8000-000000000003",
                "token_use", "access",
                "client_id", "someotherappclient00000000"));
    }

    @Test
    void aTokenWithTheClientInAudInsteadOfClientIdIsRefused() throws Exception {
        // The shape an `aud`-configured validator accepts while appearing to check.
        expectStoppedByAValidator(Map.of(
                "iss", ISSUER,
                "sub", "9f1d4c62-0000-4000-8000-000000000004",
                "token_use", "access",
                "aud", CLIENT));
    }

    @Test
    void aLocalDevelopmentTokenIsRefused() throws Exception {
        // What LocalTokenIssuer mints: no token_use, no client_id. The token a developer is
        // most likely to have to hand.
        expectStoppedByAValidator(Map.of(
                "iss", LocalTokenIssuer.ISSUER,
                "sub", "9f1d4c62-0000-4000-8000-000000000005"));
    }

    @TestConfiguration
    static class LocallySignedCognitoDecoder {

        @Bean
        KeyPair testKeyPair() throws NoSuchAlgorithmException {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        }

        /**
         * The deployed decoder with its key source swapped: same validators, local key
         * instead of Cognito's JWKS.
         *
         * <p>A distinct bean name, because Boot refuses to override one bean definition
         * with another, and {@code @Primary} so the filter chain picks this one rather than
         * reporting an ambiguity. The production decoder is still constructed alongside it,
         * which is worth having: it proves construction reaches no network.
         */
        @Bean
        @Primary
        JwtDecoder locallySignedJwtDecoder(KeyPair testKeyPair) {
            NimbusJwtDecoder decoder =
                    NimbusJwtDecoder.withPublicKey((RSAPublicKey) testKeyPair.getPublic()).build();
            decoder.setJwtValidator(CognitoTokenValidators.forPool(ISSUER, CLIENT));
            return decoder;
        }

        @Bean
        JwtEncoder jwtEncoder(KeyPair testKeyPair) {
            RSAKey key = new RSAKey.Builder((RSAPublicKey) testKeyPair.getPublic())
                    .privateKey((RSAPrivateKey) testKeyPair.getPrivate())
                    .keyID("test")
                    .build();
            JWKSource<SecurityContext> source = new ImmutableJWKSet<>(new JWKSet(key));
            return new NimbusJwtEncoder(source);
        }
    }

}
