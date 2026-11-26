package jp.co.bytex.keycloak.imapauth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;

/**
 * IMAP 認証器のユーザー関連付け、自動作成、権限保持と失敗処理を検証する。
 *
 * <p>
 * IMAP 接続と Keycloak を Mock 化し、実際のメール資格情報は使用しない。 このテストは実環境での TLS や認証 Flow の統合検証を代替しない。
 */
class ImapTest {

    AuthenticationFlowContext context;

    ImapAuthenticationService service;

    ImapAuthenticator auth;

    UserModel user;

    @BeforeEach
    void setup() {
        context = mock(AuthenticationFlowContext.class, RETURNS_DEEP_STUBS);
        service = mock(ImapAuthenticationService.class);
        auth = new ImapAuthenticator(service);
        var profile = new org.keycloak.representations.userprofile.config.UPConfig();
        for (String name : List.of(ImapAuthenticator.BINDING, ImapAuthenticator.IDENTITY)) {
            profile.addOrReplaceAttribute(new org.keycloak.representations.userprofile.config.UPAttribute(name,
                    new org.keycloak.representations.userprofile.config.UPAttributePermissions(Set.of("admin"),
                            Set.of("admin"))));
        }
        var profileProvider = mock(org.keycloak.userprofile.UserProfileProvider.class);
        when(profileProvider.getConfiguration()).thenReturn(profile);
        var session = context.getSession();
        doReturn(profileProvider).when(session).getProvider(org.keycloak.userprofile.UserProfileProvider.class);
        when(context.getAuthenticatorConfig().getConfig())
                .thenReturn(Map.of("host", "mail.example.invalid", "allowedEmailDomains", "example.invalid"));
        var formParameters = new jakarta.ws.rs.core.MultivaluedHashMap<String, String>();
        formParameters.putSingle("username", "USER@example.invalid");
        formParameters.putSingle("password", "synthetic-test-password");
        when(context.getHttpRequest().getDecodedFormParameters()).thenReturn(formParameters);
        when(context.getRealm().isDuplicateEmailsAllowed()).thenReturn(false);
        when(context.getRealm().getDefaultRole()).thenReturn(null);
        user = mock(UserModel.class);
        when(user.getEmail()).thenReturn("user@example.invalid");
        when(user.isEnabled()).thenReturn(true);
        when(user.getFirstAttribute(ImapAuthenticator.BINDING)).thenReturn("mail.example.invalid:993:SSL");
        when(user.getFirstAttribute(ImapAuthenticator.IDENTITY)).thenReturn("user@example.invalid");
        when(context.getSession().users().getUserByEmail(any(), anyString())).thenReturn(user);
        when(service.authenticate(any(), anyString(), anyString())).thenReturn(true);
    }

    @Test
    void validManagedUser() {
        auth.action(context);
        verify(context).success(PasswordCredentialModel.TYPE);
    }

    @Test
    void existingBoundMailboxBecomesVerifiedOnlyAfterImapSuccess() {
        auth.action(context);
        var order = inOrder(service, user, context);
        order.verify(service).authenticate(any(), eq("user@example.invalid"), anyString());
        order.verify(user).setEmailVerified(true);
        order.verify(context).success(PasswordCredentialModel.TYPE);
        unchangedPermissions();
    }

    @Test
    void alreadyVerifiedMailboxIsNotWrittenAgain() {
        when(user.isEmailVerified()).thenReturn(true);
        auth.action(context);
        verify(context).success(PasswordCredentialModel.TYPE);
        verify(user, never()).setEmailVerified(anyBoolean());
    }

    @Test
    void wrongPasswordDoesNotVerifyEmail() {
        when(service.authenticate(any(), anyString(), anyString())).thenReturn(false);
        auth.action(context);
        verify(user, never()).setEmailVerified(anyBoolean());
        verify(context, never()).success();
        verify(context, never()).success(any());
    }

    @Test
    void bindingMismatchDoesNotVerifyEmail() {
        when(user.getFirstAttribute(ImapAuthenticator.BINDING)).thenReturn("other");
        auth.action(context);
        verify(user, never()).setEmailVerified(anyBoolean());
        verifyNoInteractions(service);
    }

    @Test
    void mismatchedStoredEmailDoesNotVerifyAlias() {
        when(user.getEmail()).thenReturn("alias@example.invalid");
        auth.action(context);
        verify(user, never()).setEmailVerified(anyBoolean());
        verifyNoInteractions(service);
        verify(context, never()).success();
        verify(context, never()).success(any());
    }

    @Test
    void disabledUserDoesNotBecomeVerified() {
        when(user.isEnabled()).thenReturn(false);
        auth.action(context);
        verify(user, never()).setEmailVerified(anyBoolean());
        verify(context, never()).success();
        verify(context, never()).success(any());
    }

    @Test
    void permissionFailureAfterCreateDoesNotVerifyEmail() {
        creating();
        when(user.getGroupsStream()).thenAnswer(i -> java.util.stream.Stream.of(mock(GroupModel.class)));
        auth.action(context);
        verify(user, never()).setEmailVerified(anyBoolean());
        verify(context.getSession().getTransactionManager()).setRollbackOnly();
    }

    @Test
    void postAuthenticationEmailChangeFailsClosed() {
        when(service.authenticate(any(), anyString(), anyString())).thenAnswer(invocation -> {
            when(user.getEmail()).thenReturn("different@example.invalid");
            return true;
        });
        auth.action(context);
        verify(user, never()).setEmailVerified(anyBoolean());
        verify(context, never()).success();
        verify(context, never()).success(any());
        verify(context.getSession().getTransactionManager()).setRollbackOnly();
    }

    @Test
    void localAccountConflictIsNotVerified() {
        when(user.getFirstAttribute(ImapAuthenticator.IDENTITY)).thenReturn(null);
        auth.action(context);
        verify(user, never()).setEmailVerified(anyBoolean());
        verifyNoInteractions(service);
    }

    @Test
    void normalizedFullEmailAndUnmodifiedPasswordReachService() {
        context.getHttpRequest().getDecodedFormParameters().putSingle("password", " synthetic<&>-password ");
        auth.action(context);
        verify(service).authenticate(any(), eq("user@example.invalid"), eq(" synthetic<&>-password "));
        verify(context).success(PasswordCredentialModel.TYPE);
    }

    @Test
    void formFieldNamesMatchAuthenticator() throws Exception {
        try (var template = getClass().getResourceAsStream("/theme-resources/templates/imap-login.ftl")) {
            assertNotNull(template);
            String source = new String(template.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(source.contains("name=\"username\""));
            assertTrue(source.contains("name=\"password\""));
            assertTrue(source.contains("method=\"post\""));
            assertTrue(source.contains("${url.loginAction}"));
        }
    }

    @Test
    void userProfileJsonUsesNativeAdminOnlySingleValueAttributes() throws Exception {
        String json = java.nio.file.Files.readString(java.nio.file.Path.of("config/user-profile-imap-attributes.json"));
        var profile = org.keycloak.util.JsonSerialization.readValue(json,
                org.keycloak.representations.userprofile.config.UPConfig.class);
        assertEquals(2, profile.getAttributes().size());
        for (String name : List.of(ImapAuthenticator.BINDING, ImapAuthenticator.IDENTITY)) {
            var attribute = profile.getAttribute(name);
            assertNotNull(attribute);
            assertFalse(attribute.isMultivalued());
            assertEquals(Set.of("admin"), attribute.getPermissions().getEdit());
            assertEquals(Set.of("admin"), attribute.getPermissions().getView());
            assertNull(attribute.getRequired());
            assertNull(attribute.getSelector());
        }
    }

    @Test
    void authenticateOnlyChallenges() {
        auth.authenticate(context);
        verify(context).challenge(any());
        verify(context, never()).success();
        verify(context, never()).success(any());
        verifyNoInteractions(service);
    }

    @Test
    void successBindsVerifiedUser() {
        auth.action(context);
        verify(context).clearUser();
        verify(context, atLeastOnce()).setUser(user);
        verify(context).success(PasswordCredentialModel.TYPE);
    }

    @Test
    void missingAttributeProtectionRejects() {
        when(context.getSession().getProvider(org.keycloak.userprofile.UserProfileProvider.class).getConfiguration())
                .thenReturn(new org.keycloak.representations.userprofile.config.UPConfig());
        auth.action(context);
        verifyNoInteractions(service);
        verify(context, never()).success();
        verify(context, never()).success(any());
    }

    @Test
    void userWritableBindingRejects() {
        var profile = context.getSession().getProvider(org.keycloak.userprofile.UserProfileProvider.class)
                .getConfiguration();
        profile.getAttribute(ImapAuthenticator.BINDING).getPermissions().setEdit(Set.of("admin", "user"));
        auth.action(context);
        verifyNoInteractions(service);
        verify(context, never()).success();
        verify(context, never()).success(any());
    }

    @Test
    void providerRegistration() {
        var factories = java.util.ServiceLoader.load(AuthenticatorFactory.class).stream()
                .map(java.util.ServiceLoader.Provider::get)
                .filter(formParameters -> formParameters.getId().equals("imap-authenticator")).toList();
        assertEquals(1, factories.size());
        assertInstanceOf(ImapAuthenticatorFactory.class, factories.get(0));
        assertTrue(Arrays.asList(factories.get(0).getRequirementChoices())
                .contains(AuthenticationExecutionModel.Requirement.REQUIRED));
    }

    @Test
    void wrongPassword() {
        when(service.authenticate(any(), anyString(), anyString())).thenReturn(false);
        auth.action(context);
        verify(context, never()).success();
        verify(context, never()).success(any());
    }

    @Test
    void wrongPasswordRedisplaysOnlyCustomFormAndKeepsFailureUser() {
        var forms = mock(org.keycloak.forms.login.LoginFormsProvider.class, RETURNS_SELF);
        var response = mock(jakarta.ws.rs.core.Response.class);
        when(context.form()).thenReturn(forms);
        when(forms.createForm("imap-login.ftl")).thenReturn(response);
        when(context.getAuthenticationSession().getAuthNote(ImapAuthenticator.ATTEMPTED_EMAIL_NOTE))
                .thenReturn("user@example.invalid");
        when(service.authenticate(any(), anyString(), anyString())).thenReturn(false);

        auth.action(context);

        verify(forms).setAttributeMapper(any());
        verify(forms).setAttribute("imapEmail", "user@example.invalid");
        verify(forms).setError("imapLoginFailed");
        verify(forms).createForm("imap-login.ftl");
        verify(forms, never()).createLoginUsernamePassword();
        verify(context).setUser(user);
        verify(context, times(1)).clearUser();
        verify(context).failureChallenge(eq(org.keycloak.authentication.AuthenticationFlowError.INVALID_CREDENTIALS),
                same(response));
        verify(context, never()).success();
        verify(context, never()).success(any());
        verify(user, never()).setEmailVerified(anyBoolean());
    }

    @Test
    void missingMailbox() {
        when(service.authenticate(any(), anyString(), anyString())).thenReturn(false);
        auth.action(context);
        verify(context).failureChallenge(any(), any());
    }

    @Test
    void noAutoCreate() {
        when(context.getSession().users().getUserByEmail(any(), anyString())).thenReturn(null);
        auth.action(context);
        verify(context, never()).success();
        verify(context, never()).success(any());
    }

    void creating() {
        when(context.getAuthenticatorConfig().getConfig())
                .thenReturn(Map.of("host", "mail.example.invalid", "autoCreate", "true", "allowedEmailDomains",
                        "example.invalid"));
        when(context.getSession().users().getUserByEmail(any(), anyString())).thenReturn(null);
        when(context.getSession().users().getUserByUsername(any(), anyString())).thenReturn(null);
        when(context.getSession().users().addUser(any(), anyString())).thenReturn(user);
    }

    @Test
    void autoCreate() {
        creating();
        auth.action(context);
        verify(user).setEmail("user@example.invalid");
        verify(user).setEnabled(true);
        verify(user).setEmailVerified(true);
        verify(context).success(PasswordCredentialModel.TYPE);
    }

    @Test
    void autoCreateWithApprovedEmployeeRole() {
        creating();
        when(context.getRealm().getId()).thenReturn("realm");
        RoleModel employee = mock(RoleModel.class);
        when(employee.getId()).thenReturn("employee-id");
        when(employee.getName()).thenReturn("employee");
        var owner = context.getRealm();
        when(employee.getContainer()).thenReturn(owner);
        when(context.getRealm().getRoleById("employee-id")).thenReturn(employee);
        when(context.getRealm().getDefaultRole()).thenReturn(employee);
        when(user.getRoleMappingsStream()).thenAnswer(i -> java.util.stream.Stream.of(employee));
        when(context.getAuthenticatorConfig().getConfig()).thenReturn(Map.of("host", "mail.example.invalid",
                "autoCreate", "true", "allowedDefaultRoles", "realm:employee-id", "allowedEmailDomains",
                "example.invalid"));
        auth.action(context);
        verify(context).success(PasswordCredentialModel.TYPE);
        unchangedPermissions();
    }

    @Test
    void newUserDoesNotReceivePluginGrants() {
        creating();
        auth.action(context);
        verify(context).success(PasswordCredentialModel.TYPE);
        unchangedPermissions();
    }

    void unchangedPermissions() {
        verify(user, never()).grantRole(any());
        verify(user, never()).deleteRoleMapping(any());
        verify(user, never()).joinGroup(any());
        verify(user, never()).leaveGroup(any());
    }

    @Test
    void ordinaryLoginPreservesRoles() {
        auth.action(context);
        verify(context).success(PasswordCredentialModel.TYPE);
        unchangedPermissions();
    }

    @Test
    void manuallyUpgradedUserPreservesRoles() {
        when(user.getRoleMappingsStream()).thenAnswer(i -> java.util.stream.Stream.of(mock(RoleModel.class)));
        when(user.getGroupsStream()).thenAnswer(i -> java.util.stream.Stream.of(mock(GroupModel.class)));
        auth.action(context);
        verify(context).success(PasswordCredentialModel.TYPE);
        unchangedPermissions();
    }

    @Test
    void failedLoginPreservesRoles() {
        when(service.authenticate(any(), anyString(), anyString())).thenReturn(false);
        auth.action(context);
        unchangedPermissions();
        verify(context, never()).success();
        verify(context, never()).success(any());
    }

    @Test
    void unsafeDefaultRoleBlocksCreation() {
        creating();
        when(context.getRealm().getDefaultRole()).thenReturn(mock(RoleModel.class));
        auth.action(context);
        verify(context.getSession().users(), never()).addUser(any(), anyString());
        verify(context, never()).success();
        verify(context, never()).success(any());
    }

    @Test
    void defaultGroupBlocksCreation() {
        creating();
        when(context.getRealm().getDefaultGroupsStream())
                .thenAnswer(i -> java.util.stream.Stream.of(mock(GroupModel.class)));
        auth.action(context);
        verify(context.getSession().users(), never()).addUser(any(), anyString());
        verify(context, never()).success();
        verify(context, never()).success(any());
    }

    @Test
    void providerAssignedGroupRollsBackCreation() {
        creating();
        when(user.getGroupsStream()).thenAnswer(i -> java.util.stream.Stream.of(mock(GroupModel.class)));
        auth.action(context);
        verify(context.getSession().getTransactionManager()).setRollbackOnly();
        verify(context, never()).success();
        verify(context, never()).success(any());
    }

    @Test
    void roleParametersAndAttributesIgnored() {
        when(user.getFirstAttribute("role")).thenReturn("admin");
        context.getHttpRequest().getDecodedFormParameters().putSingle("role", "realm-admin");
        context.getHttpRequest().getDecodedFormParameters().putSingle("group", "administrators");
        auth.action(context);
        verify(context).success(PasswordCredentialModel.TYPE);
        unchangedPermissions();
        verify(user, never()).getFirstAttribute("role");
    }

    @Test
    void localConflict() {
        when(user.getFirstAttribute(ImapAuthenticator.BINDING)).thenReturn(null);
        auth.action(context);
        verify(context, never()).success();
        verify(context, never()).success(any());
        verifyNoInteractions(service);
    }

    @Test
    void disabled() {
        when(user.isEnabled()).thenReturn(false);
        auth.action(context);
        verify(context, never()).success();
        verify(context, never()).success(any());
        verify(user, never()).setEnabled(true);
    }

    @Test
    void invalidConfiguration() {
        when(context.getAuthenticatorConfig().getConfig()).thenReturn(Map.of());
        auth.action(context);
        verify(context, never()).success();
        verify(context, never()).success(any());
    }

    @Test
    void concurrentCreation() {
        creating();
        when(context.getSession().users().addUser(any(), anyString()))
                .thenThrow(new ModelDuplicateException("duplicate"));
        auth.action(context);
        verify(context, never()).success();
        verify(context, never()).success(any());
        verify(context.getSession().getTransactionManager()).setRollbackOnly();
    }

    @Test
    void passwordNotPersisted() {
        auth.action(context);
        verify(user, never()).setSingleAttribute(anyString(), anyString());
        verify(context.getAuthenticationSession(), never()).setAuthNote(anyString(), eq("synthetic-test-password"));
        verify(user, never()).credentialManager();
    }

    @Test
    void locked() {
        when(context.getProtector().isTemporarilyDisabled(any(), any(), any())).thenReturn(true);
        auth.action(context);
        verifyNoInteractions(service);
    }

    @Test
    void duplicateEmailsRejected() {
        when(context.getRealm().isDuplicateEmailsAllowed()).thenReturn(true);
        auth.action(context);
        verify(context, never()).success();
        verify(context, never()).success(any());
    }

    @Test
    void wrongServerBinding() {
        when(user.getFirstAttribute(ImapAuthenticator.BINDING)).thenReturn("other");
        auth.action(context);
        verify(context, never()).success();
        verify(context, never()).success(any());
    }

    @Test
    void configurationDefaults() {
        var configuration = ImapConfiguration.parse(Map.of("host", "mail.example.invalid"));
        assertEquals(993, configuration.port());
        assertFalse(configuration.autoCreate());
    }

    @Test
    void configurationRejectsInsecure() {
        for (var configurationValues : List.of(Map.of("host", "mail.example.invalid", "tls", "NONE"),
                Map.of("host", "mail.example.invalid", "port", "0"),
                Map.of("host", "mail.example.invalid", "autoCreate", "yes"))) {
            assertThrows(IllegalArgumentException.class, () -> ImapConfiguration.parse(configurationValues));
        }
    }

    @Test
    void normalized() {
        assertEquals("user@example.invalid", ImapConfiguration.normalize(" USER@example.invalid "));
    }

    @Test
    void invalidEmail() {
        assertThrows(IllegalArgumentException.class, () -> ImapConfiguration.normalize("bad\r\nemail"));
    }

    @Test
    void tlsProperties() {
        var properties = ImapAuthenticationService
                .properties(ImapConfiguration.parse(Map.of("host", "mail.example.invalid", "tls", "STARTTLS")));
        assertEquals("true", properties.getProperty("mail.imap.starttls.required"));
        assertEquals("true", properties.getProperty("mail.imap.ssl.checkserveridentity"));
        assertNull(properties.getProperty("mail.imap.ssl.trust"));
    }

    @Test
    void connectionFailuresCloseResources() throws Exception {
        for (Exception cause : List.of(new java.net.ConnectException(), new java.net.SocketTimeoutException(),
                new javax.net.ssl.SSLHandshakeException("test"))) {
            Store store = mock(Store.class);
            doThrow(new MessagingException("synthetic", cause)).when(store).connect(anyString(), anyInt(), anyString(),
                    anyString());
            var svc = new ImapAuthenticationService() {

                protected Store open(Session session) {
                    return store;
                }
            };
            assertFalse(svc.authenticate(ImapConfiguration.parse(Map.of("host", "mail.example.invalid")),
                    "user@example.invalid", "synthetic"));
            verify(store).close();
        }
    }

    @Test
    void successClosesResources() throws Exception {
        Store store = mock(Store.class);
        when(store.isConnected()).thenReturn(true);
        var svc = new ImapAuthenticationService() {

            protected Store open(Session session) {
                return store;
            }
        };
        assertTrue(svc.authenticate(ImapConfiguration.parse(Map.of("host", "mail.example.invalid")),
                "user@example.invalid", "synthetic"));
        verify(store).close();
    }
}
