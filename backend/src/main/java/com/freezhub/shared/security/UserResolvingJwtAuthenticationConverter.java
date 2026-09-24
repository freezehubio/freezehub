package com.freezhub.shared.security;

import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/**
 * Resolves a validated Cognito JWT's `sub` claim to an existing users row, per
 * 06-security.md: organization_id is always resolved server-side, never trusted
 * from client input. A JWT with no matching users row is rejected (401) rather
 * than treated as authenticated with no organization.
 */
@Component
public class UserResolvingJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRepository userRepository;

    public UserResolvingJwtAuthenticationConverter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        User user = userRepository.findByExternalSubject(jwt.getSubject())
                .orElseThrow(() -> new InvalidBearerTokenException(
                        new OAuth2Error("invalid_token", "No user provisioned for this identity", null).toString()));

        // Deactivated (FZ-212). Checked here, on every request, rather than at sign-in only:
        // a Cognito access token stays valid for up to an hour, and "removed" has to mean
        // removed now, not when their token happens to expire. The identity itself is left
        // alone — deactivation is reversible, and the person may be reinstated.
        if (!user.isActive()) {
            throw new InvalidBearerTokenException(
                    new OAuth2Error("invalid_token", "This account has been deactivated", null).toString());
        }

        AuthenticatedUser authenticatedUser =
                new AuthenticatedUser(user.getId(), user.getOrganizationId(), user.getEmail(), user.getRole());

        return new FreezeHubAuthenticationToken(authenticatedUser, jwt);
    }

}
