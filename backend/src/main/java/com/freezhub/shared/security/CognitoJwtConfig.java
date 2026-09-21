package com.freezhub.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The deployed-environment {@link JwtDecoder}, replacing the resource server's default one
 * so that the rules in 06-security.md are actually enforced (FZ-128, OI-25).
 *
 * <p>The default configuration validates signature, expiry and issuer, and would accept a
 * Cognito <em>ID</em> token for the same pool — same issuer, same keys. {@link
 * CognitoTokenValidators} adds the two checks that distinguish an access token for this app
 * client from anything else the pool signs.
 *
 * <p><b>Both values are configuration with no default</b>, the same fail-fast as the
 * encryption key and the pool id: an environment that omits either does not start. The
 * issuer in particular must never be defaulted — a decoder that falls back to some issuer is
 * a decoder that trusts a pool nobody chose.
 *
 * <p><b>The JWKS URI is derived rather than discovered, deliberately.</b>
 * {@code withIssuerLocation} would be the obvious choice and is the wrong one: it fetches
 * the pool's OpenID configuration <em>at bean creation</em>, so the application cannot start
 * unless Cognito answers. A context that cannot boot during a Cognito outage cannot serve
 * the Policy API during one either — and that endpoint is on the path of every customer's
 * deployment, answers from the database, and needs no human's token at all. Cognito's JWKS
 * path is a documented constant, so {@code withJwkSetUri} gets the same keys and fetches
 * them on first use.
 *
 * <p>Nothing is lost by skipping discovery: the issuer is still validated, by
 * {@link CognitoTokenValidators}, against the same configured value used to build the URI.
 */
@Configuration
@Profile("!local")
public class CognitoJwtConfig {

    /** Cognito serves its keys here, for every pool, in every region. */
    static String jwkSetUri(String issuerUri) {
        return issuerUri.replaceAll("/+$", "") + "/.well-known/jwks.json";
    }

    @Bean
    JwtDecoder cognitoJwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${freezehub.cognito.client-id}") String clientId) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri(issuerUri)).build();
        decoder.setJwtValidator(CognitoTokenValidators.forPool(issuerUri, clientId));
        return decoder;
    }

}
