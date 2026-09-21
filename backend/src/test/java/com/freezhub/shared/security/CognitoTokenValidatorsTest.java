package com.freezhub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The rules from 06-security.md, one test each (FZ-128, OI-25).
 *
 * <p>Every case here is a token whose <em>signature is valid</em> — that is the whole point.
 * These are the checks the library does not make, so a test that fed them a forged token
 * would prove nothing.
 */
class CognitoTokenValidatorsTest {

    private static final String ISSUER = "https://cognito-idp.us-east-2.amazonaws.com/us-east-2_example";
    private static final String CLIENT = "1baicl02uro0in2mv6gsr88mfk";

    private final OAuth2TokenValidator<Jwt> validator = CognitoTokenValidators.forPool(ISSUER, CLIENT);

    private static Jwt jwt(Map<String, Object> claims) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("signature-is-valid-by-construction")
                .header("alg", "RS256")
                .claims(c -> c.putAll(claims))
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .build();
    }

    private static Map<String, Object> accessToken() {
        return Map.of(
                "iss", ISSUER,
                "sub", "9f1d4c62-0000-4000-8000-000000000001",
                "token_use", "access",
                "client_id", CLIENT);
    }

    @Test
    void acceptsAnAccessTokenFromThisPoolForThisClient() {
        assertThat(validator.validate(jwt(accessToken())).hasErrors()).isFalse();
    }

    @Test
    void refusesAnIdTokenFromTheSamePool() {
        // Same issuer, same signing keys, same everything the default validators look at.
        // `aud` is where Cognito puts the client on an ID token, and it is correct here —
        // which is exactly why validating `aud` would have let this through.
        Map<String, Object> idToken = Map.of(
                "iss", ISSUER,
                "sub", "9f1d4c62-0000-4000-8000-000000000001",
                "token_use", "id",
                "aud", CLIENT);

        assertThat(validator.validate(jwt(idToken)).hasErrors()).isTrue();
    }

    @Test
    void refusesATokenFromAnotherPool() {
        Map<String, Object> other = Map.of(
                "iss", "https://cognito-idp.us-east-2.amazonaws.com/us-east-2_somebodyelse",
                "sub", "9f1d4c62-0000-4000-8000-000000000002",
                "token_use", "access",
                "client_id", CLIENT);

        assertThat(validator.validate(jwt(other)).hasErrors()).isTrue();
    }

    @Test
    void refusesATokenForAnotherAppClient() {
        Map<String, Object> other = Map.of(
                "iss", ISSUER,
                "sub", "9f1d4c62-0000-4000-8000-000000000003",
                "token_use", "access",
                "client_id", "someotherappclient00000000");

        assertThat(validator.validate(jwt(other)).hasErrors()).isTrue();
    }

    @Test
    void refusesATokenCarryingNoTokenUseAtAll() {
        // The shape LocalTokenIssuer mints. Nothing should accept it outside `local`, and
        // the reason to assert it is that it is the token a developer is most likely to
        // have lying around.
        Map<String, Object> bare = Map.of(
                "iss", ISSUER,
                "sub", "9f1d4c62-0000-4000-8000-000000000004",
                "client_id", CLIENT);

        assertThat(validator.validate(jwt(bare)).hasErrors()).isTrue();
    }

    @Test
    void refusesAnAccessTokenWithTheClientInAudInsteadOfClientId() {
        // The shape that passes a validator configured on `aud` in the ordinary way.
        Map<String, Object> misplaced = Map.of(
                "iss", ISSUER,
                "sub", "9f1d4c62-0000-4000-8000-000000000005",
                "token_use", "access",
                "aud", CLIENT);

        assertThat(validator.validate(jwt(misplaced)).hasErrors()).isTrue();
    }

    @Test
    void reportsWhyRatherThanJustFailing() {
        Map<String, Object> idToken = Map.of(
                "iss", ISSUER, "sub", "s", "token_use", "id", "aud", CLIENT);

        assertThat(validator.validate(jwt(idToken)).getErrors())
                .extracting("description")
                .anySatisfy(description ->
                        assertThat(String.valueOf(description)).contains("token_use"));
    }

}
