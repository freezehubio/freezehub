package com.freezhub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidParameterException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

/**
 * FZ-046. The interesting half is the failures: a subject this application invented, or an
 * SDK exception escaping the port, both produce a user row that looks correct and an account
 * nobody can sign in to.
 */
class CognitoIdentityProviderTest {

    private static final String POOL = "us-east-2_example";

    private final CognitoIdentityProviderClient cognito = mock(CognitoIdentityProviderClient.class);
    private final CognitoIdentityProvider provider = new CognitoIdentityProvider(cognito, POOL);

    private static AdminCreateUserResponse responseWith(AttributeType... attributes) {
        return AdminCreateUserResponse.builder()
                .user(UserType.builder().attributes(attributes).build())
                .build();
    }

    private static AttributeType attribute(String name, String value) {
        return AttributeType.builder().name(name).value(value).build();
    }

    @Test
    void returnsTheSubCognitoIssued() {
        when(cognito.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenReturn(responseWith(
                        attribute("email", "ops@contoso.test"),
                        attribute("sub", "9f1d4c62-0000-4000-8000-000000000001")));

        assertThat(provider.createUser("ops@contoso.test"))
                .isEqualTo("9f1d4c62-0000-4000-8000-000000000001");
    }

    @Test
    void createsTheUserInTheConfiguredPoolWithAVerifiedEmail() {
        when(cognito.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenReturn(responseWith(attribute("sub", "any")));

        provider.createUser("ops@contoso.test");

        ArgumentCaptor<AdminCreateUserRequest> sent =
                ArgumentCaptor.forClass(AdminCreateUserRequest.class);
        Mockito.verify(cognito).adminCreateUser(sent.capture());

        assertThat(sent.getValue().userPoolId()).isEqualTo(POOL);
        assertThat(sent.getValue().username()).isEqualTo("ops@contoso.test");
        assertThat(sent.getValue().userAttributes())
                .extracting(AttributeType::name, AttributeType::value)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("email", "ops@contoso.test"),
                        org.assertj.core.groups.Tuple.tuple("email_verified", "true"));
    }

    @Test
    void failsRatherThanInventASubjectWhenCognitoReturnsNone() {
        when(cognito.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenReturn(responseWith(attribute("email", "ops@contoso.test")));

        assertThatThrownBy(() -> provider.createUser("ops@contoso.test"))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("no sub");
    }

    @Test
    void failsWhenTheSubIsPresentButBlank() {
        when(cognito.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenReturn(responseWith(attribute("sub", "   ")));

        assertThatThrownBy(() -> provider.createUser("ops@contoso.test"))
                .isInstanceOf(IdentityProviderException.class);
    }

    @Test
    void reportsAnIdentityThatAlreadyExists() {
        when(cognito.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenThrow(UsernameExistsException.builder().message("already").build());

        assertThatThrownBy(() -> provider.createUser("ops@contoso.test"))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void doesNotLetAnSdkExceptionEscapeThePort() {
        when(cognito.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenThrow(InvalidParameterException.builder().message("bad").build());

        assertThatThrownBy(() -> provider.createUser("ops@contoso.test"))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("Could not create the identity");
    }

    @Test
    void doesNotPutTheEmailAddressInTheFailureMessage() {
        when(cognito.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenThrow(InvalidParameterException.builder().message("bad").build());

        assertThatThrownBy(() -> provider.createUser("ops@contoso.test"))
                .hasMessageNotContaining("ops@contoso.test");
    }

}
