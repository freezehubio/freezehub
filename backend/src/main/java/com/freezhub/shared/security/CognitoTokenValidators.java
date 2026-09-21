package com.freezhub.shared.security;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

/**
 * The token validation rules 06-security.md specifies, built where both the production
 * decoder and its tests can use the same object (FZ-128, OI-25).
 *
 * <p><b>Why any of this exists.</b> Cognito issues ID tokens and access tokens from one
 * issuer, signed by one JWKS. Signature and expiry — the two checks a resource server makes
 * by default — therefore accept both, and an ID token presented where an access token is
 * meant is a valid token used outside its purpose. Two further checks are needed, and
 * neither is made by the default configuration:
 *
 * <ul>
 *   <li>{@code token_use} must be {@code access}. This is the only claim that distinguishes
 *       the two token types.
 *   <li>The app client is in {@code client_id}, not {@code aud}. Cognito populates
 *       {@code aud} on the <em>ID</em> token only, so the ordinary
 *       {@code JwtClaimValidator<List<String>>("aud")} configured the usual way validates
 *       a claim that is absent — passing everything while reporting that it checked
 *       something, which is worse than not checking at all.
 * </ul>
 *
 * <p>Expiry, signature and issuer come from {@link JwtValidators#createDefaultWithIssuer},
 * which is what the library already does well.
 */
public final class CognitoTokenValidators {

    /** Cognito's discriminator between an access token and an ID token. */
    static final String TOKEN_USE = "token_use";

    /** Cognito's app-client claim on an access token. Not {@code aud}. */
    static final String CLIENT_ID = "client_id";

    private CognitoTokenValidators() {
    }

    /**
     * @param issuer   the pool's issuer URI, configured per environment and never defaulted
     * @param clientId the app client this API accepts tokens for
     */
    public static OAuth2TokenValidator<Jwt> forPool(String issuer, String clientId) {
        return new DelegatingOAuth2TokenValidatorOf(List.of(
                JwtValidators.createDefaultWithIssuer(issuer),
                requiredClaim(TOKEN_USE, "access",
                        "token_use must be 'access'; an ID token is not an access token"),
                requiredClaim(CLIENT_ID, clientId,
                        "client_id does not match the app client this API accepts")));
    }

    private static OAuth2TokenValidator<Jwt> requiredClaim(String claim, String expected, String description) {
        return jwt -> expected.equals(jwt.getClaimAsString(claim))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token", description, "https://tools.ietf.org/html/rfc6750#section-3.1"));
    }

    /**
     * Spring's {@code DelegatingOAuth2TokenValidator} stops at the first failure in some
     * versions and collects in others. Explicit here so the behaviour does not depend on
     * which: every validator runs, and any failure fails the token.
     */
    private record DelegatingOAuth2TokenValidatorOf(List<OAuth2TokenValidator<Jwt>> delegates)
            implements OAuth2TokenValidator<Jwt> {

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            for (OAuth2TokenValidator<Jwt> delegate : delegates) {
                OAuth2TokenValidatorResult result = delegate.validate(token);
                if (result.hasErrors()) {
                    return result;
                }
            }
            return OAuth2TokenValidatorResult.success();
        }
    }

}
