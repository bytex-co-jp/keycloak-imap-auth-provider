package jp.co.bytex.keycloak.imapauth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.core.MultivaluedHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticatorUtil;
import org.keycloak.authentication.authenticators.conditional.ConditionalCredentialAuthenticator;
import org.keycloak.authentication.authenticators.conditional.ConditionalCredentialAuthenticatorFactory;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPAttributePermissions;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.userprofile.UserProfileProvider;

/**
 * Keycloak の "Condition - credential" が IMAP ログイン後にどう判定するかを、Keycloak 本体の ConditionalCredentialAuthenticator と
 * AuthenticatorUtil で検証する。IMAP 成功は password として記録されるため、none を条件とするサブフロー (例: OTP 追加) は IMAP
 * ログイン後に実行されなくなる。これは意図した互換性変更であり、アップグレード前の確認が必要 (docs/deployment.md)。
 */
class ImapConditionalCredentialTest {

    AuthenticationFlowContext context;

    AuthenticationSessionModel authSession;

    ImapAuthenticationService service;

    Map<String, String> notes;

    @BeforeEach
    void setup() {
        notes = new HashMap<>();
        authSession = mock(AuthenticationSessionModel.class);
        doAnswer(invocation -> notes.put(invocation.getArgument(0), invocation.getArgument(1))).when(authSession)
                .setAuthNote(anyString(), anyString());
        doAnswer(invocation -> notes.get(invocation.<String>getArgument(0))).when(authSession)
                .getAuthNote(anyString());
        doAnswer(invocation -> notes.remove(invocation.<String>getArgument(0))).when(authSession)
                .removeAuthNote(anyString());

        context = mock(AuthenticationFlowContext.class, RETURNS_DEEP_STUBS);
        when(context.getAuthenticationSession()).thenReturn(authSession);
        // Same bookkeeping as AuthenticationProcessor.Result.success(String) in Keycloak 26.8.1.
        doAnswer(invocation -> {
            AuthenticatorUtil.addAuthCredential(authSession, invocation.getArgument(0));
            return null;
        }).when(context).success(anyString());

        var profile = new UPConfig();
        for (String name : List.of(ImapAuthenticator.BINDING, ImapAuthenticator.IDENTITY)) {
            profile.addOrReplaceAttribute(
                    new UPAttribute(name, new UPAttributePermissions(Set.of("admin"), Set.of("admin"))));
        }
        var profileProvider = mock(UserProfileProvider.class);
        when(profileProvider.getConfiguration()).thenReturn(profile);
        var session = context.getSession();
        doReturn(profileProvider).when(session).getProvider(UserProfileProvider.class);
        when(context.getAuthenticatorConfig().getConfig()).thenReturn(Map.of("host", "mail.example.invalid",
                "autoCreate", "false", ImapEmailDomains.CONFIG_KEY, "example.invalid"));
        var form = new MultivaluedHashMap<String, String>();
        form.putSingle("username", "user@example.invalid");
        form.putSingle("password", "synthetic-test-password");
        when(context.getHttpRequest().getDecodedFormParameters()).thenReturn(form);
        when(context.getRealm().isDuplicateEmailsAllowed()).thenReturn(false);

        UserModel user = mock(UserModel.class);
        when(user.isEnabled()).thenReturn(true);
        when(user.isEmailVerified()).thenReturn(true);
        when(user.getEmail()).thenReturn("user@example.invalid");
        when(user.getFirstAttribute(ImapAuthenticator.BINDING)).thenReturn("mail.example.invalid:993:SSL");
        when(user.getFirstAttribute(ImapAuthenticator.IDENTITY)).thenReturn("user@example.invalid");
        when(context.getSession().users().getUserByEmail(any(), anyString())).thenReturn(user);
        service = mock(ImapAuthenticationService.class);
    }

    void imapLogin(boolean mailboxAccepts) {
        when(service.authenticate(any(), anyString(), anyString())).thenReturn(mailboxAccepts);
        new ImapAuthenticator(service).action(context);
    }

    /** Evaluates Keycloak's own Condition - credential against the current authentication session. */
    boolean condition(String credentials, boolean included) {
        AuthenticationFlowContext conditionContext = mock(AuthenticationFlowContext.class);
        AuthenticatorConfigModel config = new AuthenticatorConfigModel();
        config.setConfig(Map.of(ConditionalCredentialAuthenticatorFactory.CONF_CREDENTIALS, credentials,
                ConditionalCredentialAuthenticatorFactory.CONF_INCLUDED, Boolean.toString(included)));
        when(conditionContext.getAuthenticatorConfig()).thenReturn(config);
        when(conditionContext.getAuthenticationSession()).thenReturn(authSession);
        return new ConditionalCredentialAuthenticator().matchCondition(conditionContext);
    }

    @Test
    void successfulImapLoginIsRecordedAsPasswordCredential() {
        imapLogin(true);
        assertEquals(List.of(PasswordCredentialModel.TYPE), AuthenticatorUtil.getAuthnCredentials(authSession));
    }

    @Test
    void failedImapLoginRecordsNoCredential() {
        imapLogin(false);
        assertEquals(List.of(), AuthenticatorUtil.getAuthnCredentials(authSession));
        assertTrue(condition(ConditionalCredentialAuthenticatorFactory.NONE_CREDENTIAL, true));
    }

    @Test
    void noneIncludedNoLongerMatchesAfterImapLogin() {
        // Before the IMAP step nothing is recorded, which is what earlier plugin versions left behind.
        assertTrue(condition(ConditionalCredentialAuthenticatorFactory.NONE_CREDENTIAL, true));
        imapLogin(true);
        assertFalse(condition(ConditionalCredentialAuthenticatorFactory.NONE_CREDENTIAL, true));
    }

    @Test
    void passwordExcludedNoLongerMatchesAfterImapLogin() {
        assertTrue(condition(PasswordCredentialModel.TYPE, false));
        imapLogin(true);
        assertFalse(condition(PasswordCredentialModel.TYPE, false));
    }

    @Test
    void passwordIncludedStartsToMatchAfterImapLogin() {
        assertFalse(condition(PasswordCredentialModel.TYPE, true));
        imapLogin(true);
        assertTrue(condition(PasswordCredentialModel.TYPE, true));
    }

    @Test
    void noneExcludedStartsToMatchAfterImapLogin() {
        assertFalse(condition(ConditionalCredentialAuthenticatorFactory.NONE_CREDENTIAL, false));
        imapLogin(true);
        assertTrue(condition(ConditionalCredentialAuthenticatorFactory.NONE_CREDENTIAL, false));
    }

    @Test
    void conditionsOnOtherCredentialTypesAreUnchanged() {
        assertFalse(condition("otp", true));
        assertTrue(condition("otp", false));
        imapLogin(true);
        assertFalse(condition("otp", true));
        assertTrue(condition("otp", false));
    }

    @Test
    void imapSuccessAddsPasswordToCredentialsRecordedEarlierInTheSession() {
        AuthenticatorUtil.addAuthCredential(authSession, "otp");
        imapLogin(true);
        assertEquals(List.of("otp", PasswordCredentialModel.TYPE), AuthenticatorUtil.getAuthnCredentials(authSession));
    }
}
