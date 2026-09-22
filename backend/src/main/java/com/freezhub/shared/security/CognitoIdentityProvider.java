package com.freezhub.shared.security;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

/**
 * The real {@code IdentityProvider}: creates a Cognito user and returns the {@code sub}
 * it issues, which is what {@code users.external_subject} stores (FZ-046, OI-2).
 *
 * <p>Active in every profile except {@code local}, which keeps {@link LocalIdentityProvider}.
 * The fail-fast that FZ-016 gave up by defining a bean here is not lost, only moved:
 * {@code freezehub.cognito.user-pool-id} has no default, so a deployed environment that
 * forgets it fails to start rather than failing at the first invitation.
 *
 * <p><b>The subject is read from the response, never derived.</b> Cognito's {@code sub} is
 * assigned by Cognito and is not the username, the email, or anything this application can
 * compute. If the response does not carry one, that is an error and not something to paper
 * over with a placeholder — a wrong {@code external_subject} is a user who can never sign in
 * and whose row looks correct.
 */
@Component
@Profile("!local")
public class CognitoIdentityProvider implements IdentityProvider {

    private static final Logger log = LoggerFactory.getLogger(CognitoIdentityProvider.class);

    private final CognitoIdentityProviderClient cognito;
    private final String userPoolId;

    public CognitoIdentityProvider(
            CognitoIdentityProviderClient cognito,
            @Value("${freezehub.cognito.user-pool-id}") String userPoolId) {
        this.cognito = cognito;
        this.userPoolId = userPoolId;
    }

    @Override
    public String createUser(String email) {
        AdminCreateUserResponse response;
        try {
            response = cognito.adminCreateUser(AdminCreateUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    // The pool is configured with username_attributes = ["email"], so the
                    // username is the address; the attribute is still required for Cognito to
                    // address its invitation.
                    .userAttributes(
                            AttributeType.builder().name("email").value(email).build(),
                            // Admin-created users have already been vouched for by whoever
                            // invited them. Without this Cognito holds the address unverified
                            // and password recovery has nowhere to go.
                            AttributeType.builder().name("email_verified").value("true").build())
                    .build());
        } catch (UsernameExistsException e) {
            // Reachable despite InviteService's own check: that one is scoped to an
            // organization, and the pool is shared across all of them.
            throw new IdentityAlreadyExistsException(
                    "An identity already exists for this email address", e);
        } catch (CognitoIdentityProviderException e) {
            // The message carries the pool and the operation but never the address.
            log.error("AdminCreateUser failed for pool {}", userPoolId, e);
            throw new IdentityProviderException("Could not create the identity", e);
        }

        return subjectOf(response);
    }

    private String subjectOf(AdminCreateUserResponse response) {
        return Optional.ofNullable(response.user())
                .map(user -> user.attributes())
                .flatMap(attributes -> attributes.stream()
                        .filter(attribute -> "sub".equals(attribute.name()))
                        .map(AttributeType::value)
                        .filter(value -> value != null && !value.isBlank())
                        .findFirst())
                .orElseThrow(() -> new IdentityProviderException(
                        "Cognito created the user but returned no sub", null));
    }

    @Override
    public void deleteUser(String externalSubject) {
        try {
            cognito.adminDeleteUser(AdminDeleteUserRequest.builder()
                    .userPoolId(userPoolId)
                    // The pool has username_attributes = ["email"], so the username is the
                    // address -- but the caller holds the sub, not the address. AdminDeleteUser
                    // accepts either, and the sub is the identifier that cannot go stale.
                    .username(externalSubject)
                    .build());
        } catch (UserNotFoundException e) {
            // Already gone. Both callers are compensating for something that failed, and a
            // cleanup that throws because there was nothing to clean up turns one problem
            // into two.
            log.debug("Identity {} was already absent", externalSubject);
        } catch (CognitoIdentityProviderException e) {
            // Deliberately not rethrown. The caller is already handling a failure, or
            // deleting an organization; losing the compensating delete must not lose that
            // too. It is logged loudly because the residue is real: an identity that can
            // authenticate and resolves to no user row, which the converter rejects with a
            // 401 -- inert, but it holds the address against a future signup.
            log.error("Could not delete identity {} from pool {}; it may be orphaned",
                    externalSubject, userPoolId, e);
        }
    }

}
