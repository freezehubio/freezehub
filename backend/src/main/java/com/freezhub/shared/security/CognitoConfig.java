package com.freezhub.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

/**
 * The Cognito client {@link CognitoIdentityProvider} calls (FZ-046).
 *
 * <p><b>No credentials are configured here.</b> The SDK's default provider chain finds them:
 * on the deployed box that is the instance profile, and the role is scoped to
 * {@code AdminCreateUser} on one pool. An access key would be a long-lived credential where
 * none is needed — the same argument {@code 16-accounts.md} makes about the operator's.
 *
 * <p>The region is separate configuration from the pool id even though the pool id contains
 * it, because parsing an identifier for a value that is also available as configuration is
 * the kind of cleverness that breaks when the format changes.
 */
@Configuration
@Profile("!local")
public class CognitoConfig {

    @Bean
    @ConditionalOnMissingBean
    CognitoIdentityProviderClient cognitoIdentityProviderClient(
            @Value("${freezehub.cognito.region}") String region) {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

}
