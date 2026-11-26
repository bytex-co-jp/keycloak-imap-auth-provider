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
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.authenticators.browser.AbstractUsernameFormAuthenticator;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPAttributePermissions;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.keycloak.services.managers.DefaultBruteForceProtector;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.userprofile.UserProfileProvider;
import org.mockito.InOrder;

/**
 * Keycloak ネイティブの Brute Force 計数との連携を検証する。
 *
 * <p>
 * Keycloak は失敗時に認証済みユーザー、なければ ATTEMPTED_USERNAME で計数対象を探す。管理対象ユーザーは setUser により計数され、ATTEMPTED_USERNAME
 * は書き込まず、他のフォームが残した値も毎回の送信の最初に削除することで、非管理のローカルアカウントへの誤計数を防ぐ (G2)。成功時は password
 * クレデンシャルを記録し、ネイティブのパスワードフォームと同様に失敗回数をリセットさせる (G1)。実際の計数とロックは実 Keycloak 26.8.1 で別途検証する。
 */
class ImapBruteForceAccountingTest {

    static final String KEYCLOAK_ATTEMPTED_USERNAME = "ATTEMPTED_USERNAME";

    AuthenticationFlowContext context;

    AuthenticationSessionModel authSession;

    ImapAuthenticationService service;

    ImapAuthenticator auth;

    UserModel user;

    Map<String, String> config;

    MultivaluedHashMap<String, String> form;

    @BeforeEach
    void setup() {
        context = mock(AuthenticationFlowContext.class, RETURNS_DEEP_STUBS);
        service = mock(ImapAuthenticationService.class);
        auth = new ImapAuthenticator(service);
        var profile = new UPConfig();
        for (String name : List.of(ImapAuthenticator.BINDING, ImapAuthenticator.IDENTITY)) {
            profile.addOrReplaceAttribute(
                    new UPAttribute(name, new UPAttributePermissions(Set.of("admin"), Set.of("admin"))));
        }
        var profileProvider = mock(UserProfileProvider.class);
        when(profileProvider.getConfiguration()).thenReturn(profile);
        var session = context.getSession();
        doReturn(profileProvider).when(session).getProvider(UserProfileProvider.class);
        config = new HashMap<>(Map.of("host", "mail.example.invalid", "autoCreate", "true",
                ImapEmailDomains.CONFIG_KEY, "example.invalid"));
        when(context.getAuthenticatorConfig().getConfig()).thenAnswer(invocation -> config);
        form = new MultivaluedHashMap<>();
        form.putSingle("username", "user@example.invalid");
        form.putSingle("password", "synthetic-test-password");
        when(context.getHttpRequest().getDecodedFormParameters()).thenReturn(form);
        when(context.getRealm().isDuplicateEmailsAllowed()).thenReturn(false);
        when(context.getRealm().getDefaultRole()).thenReturn(null);
        authSession = context.getAuthenticationSession();
        user = mock(UserModel.class);
        when(user.isEnabled()).thenReturn(true);
        when(user.getEmail()).thenReturn("user@example.invalid");
        when(user.getFirstAttribute(ImapAuthenticator.BINDING)).thenReturn("mail.example.invalid:993:SSL");
        when(user.getFirstAttribute(ImapAuthenticator.IDENTITY)).thenReturn("user@example.invalid");
        when(context.getSession().users().getUserByEmail(any(), anyString())).thenReturn(user);
        when(service.authenticate(any(), anyString(), anyString())).thenReturn(false);
    }

    void unmanagedLocalUser() {
        when(user.getFirstAttribute(ImapAuthenticator.BINDING)).thenReturn(null);
        when(user.getFirstAttribute(ImapAuthenticator.IDENTITY)).thenReturn(null);
    }

    /**
     * The stale Keycloak note must be removed right after the flow user is cleared, before any branch can bind a user,
     * contact IMAP or issue a result.
     */
    void assertStaleNoteRemovedFirst() {
        InOrder order = inOrder(context, authSession);
        order.verify(context).clearUser();
        order.verify(authSession).removeAuthNote(KEYCLOAK_ATTEMPTED_USERNAME);
    }

    void assertKeycloakAttemptedUsernameUntouched() {
        verify(authSession, never()).setAuthNote(eq(KEYCLOAK_ATTEMPTED_USERNAME), any());
        verify(authSession, never()).getAuthNote(KEYCLOAK_ATTEMPTED_USERNAME);
    }

    @Test
    void categoryIsPasswordAndCountedByKeycloakBruteForce() {
        String category = new ImapAuthenticatorFactory().getReferenceCategory();
        assertEquals(PasswordCredentialModel.TYPE, category);
        // Guards against a Keycloak upgrade that changes which categories brute force detection processes.
        assertTrue(DefaultBruteForceProtector.ALLOWED_AUTHENTICATION_CATEGORIES.contains(category),
                DefaultBruteForceProtector.ALLOWED_AUTHENTICATION_CATEGORIES.toString());
    }

    @Test
    void providerIdIsUnchanged() {
        assertEquals("imap-authenticator", new ImapAuthenticatorFactory().getId());
    }

    @Test
    void privateNoteDoesNotCollideWithKeycloakNote() {
        assertNotEquals(KEYCLOAK_ATTEMPTED_USERNAME, ImapAuthenticator.ATTEMPTED_EMAIL_NOTE);
        assertTrue(ImapAuthenticator.ATTEMPTED_EMAIL_NOTE.startsWith("IMAP_"));
    }

    @Test
    void managedUserFailureIsChargedViaSetUserBeforeImap() {
        auth.action(context);
        InOrder order = inOrder(context, service);
        order.verify(context).setUser(user);
        order.verify(service).authenticate(any(), eq("user@example.invalid"), anyString());
        order.verify(context).failureChallenge(eq(AuthenticationFlowError.INVALID_CREDENTIALS), any());
        assertKeycloakAttemptedUsernameUntouched();
    }

    @Test
    void managedUserStillChargedWhenImapThrows() {
        when(service.authenticate(any(), anyString(), anyString())).thenThrow(new IllegalStateException("x"));
        auth.action(context);
        verify(context).setUser(user);
        verify(context).failureChallenge(eq(AuthenticationFlowError.INVALID_CREDENTIALS), any());
        assertKeycloakAttemptedUsernameUntouched();
    }

    @Test
    void lockedManagedUserIsNotChargedAgainAndNeverReachesImap() {
        when(context.getProtector().isTemporarilyDisabled(any(), any(), any())).thenReturn(true);
        auth.action(context);
        verifyNoInteractions(service);
        verify(context).forceChallenge(any());
        verify(context, never()).failureChallenge(any(), any());
        assertKeycloakAttemptedUsernameUntouched();
    }

    @Test
    void permanentlyLockedManagedUserNeverReachesImap() {
        when(context.getProtector().isPermanentlyLockedOut(any(), any(), any())).thenReturn(true);
        auth.action(context);
        verifyNoInteractions(service);
        verify(context, never()).failureChallenge(any(), any());
    }

    @Test
    void unmanagedLocalUserIsNeitherSetNorNamedForAccounting() {
        unmanagedLocalUser();
        auth.action(context);
        verifyNoInteractions(service);
        verify(context, never()).setUser(any());
        verify(context).failureChallenge(eq(AuthenticationFlowError.INVALID_CREDENTIALS), any());
        assertKeycloakAttemptedUsernameUntouched();
    }

    @Test
    void unmanagedLocalUserWithUsernameEqualToEmailIsNotNamedForAccounting() {
        unmanagedLocalUser();
        when(user.getUsername()).thenReturn("user@example.invalid");
        auth.action(context);
        verify(context, never()).setUser(any());
        assertKeycloakAttemptedUsernameUntouched();
    }

    @Test
    void domainRejectedLocalAccountIsNotNamedForAccounting() {
        form.putSingle("username", "local@other.invalid");
        auth.action(context);
        verify(context.getSession().users(), never()).getUserByEmail(any(), anyString());
        verify(context, never()).setUser(any());
        verifyNoInteractions(service);
        assertKeycloakAttemptedUsernameUntouched();
    }

    @Test
    void unknownUserIsNotNamedForAccounting() {
        when(context.getSession().users().getUserByEmail(any(), anyString())).thenReturn(null);
        auth.action(context);
        verify(context, never()).setUser(any());
        assertKeycloakAttemptedUsernameUntouched();
    }

    @Test
    void invalidConfigurationAndMalformedInputDoNotTouchKeycloakNote() {
        config.remove(ImapEmailDomains.CONFIG_KEY);
        auth.action(context);
        config.put(ImapEmailDomains.CONFIG_KEY, "example.invalid");
        form.putSingle("username", "not-an-address");
        auth.action(context);
        assertKeycloakAttemptedUsernameUntouched();
        verify(authSession, never()).setAuthNote(eq(ImapAuthenticator.ATTEMPTED_EMAIL_NOTE), any());
    }

    @Test
    void successfulLoginDoesNotTouchKeycloakNote() {
        when(service.authenticate(any(), anyString(), anyString())).thenReturn(true);
        auth.action(context);
        verify(context).success(PasswordCredentialModel.TYPE);
        verify(context, never()).success();
        verify(context, atLeastOnce()).setUser(user);
        assertKeycloakAttemptedUsernameUntouched();
    }

    @Test
    void normalizedEmailIsStoredInPrivateNoteForRedisplay() {
        form.putSingle("username", "  USER@Example.INVALID ");
        auth.action(context);
        verify(authSession).setAuthNote(ImapAuthenticator.ATTEMPTED_EMAIL_NOTE, "user@example.invalid");
        verify(authSession, atLeastOnce()).getAuthNote(ImapAuthenticator.ATTEMPTED_EMAIL_NOTE);
    }

    @Test
    void eventKeepsUsernameDetail() {
        unmanagedLocalUser();
        auth.action(context);
        verify(context.getEvent()).detail("username", "user@example.invalid");
        verify(context.getEvent()).error("invalid_user_credentials");
    }

    @Test
    void g1SuccessRecordsPasswordCredentialLikeNativePasswordForm() {
        when(service.authenticate(any(), anyString(), anyString())).thenReturn(true);
        auth.action(context);
        InOrder order = inOrder(context);
        order.verify(context).setUser(user);
        order.verify(context).success(PasswordCredentialModel.TYPE);
        verify(context, never()).success();
        verify(context, never()).failureChallenge(any(), any());
    }

    @Test
    void g1FailuresNeverRecordACredential() {
        auth.action(context);
        unmanagedLocalUser();
        auth.action(context);
        form.putSingle("username", "local@other.invalid");
        auth.action(context);
        verify(context, never()).success(any());
        verify(context, never()).success();
    }

    @Test
    void g2StaleNoteRemovedBeforeManagedUserIsBoundAndImapContacted() {
        auth.action(context);
        assertStaleNoteRemovedFirst();
        InOrder order = inOrder(context, authSession, service);
        order.verify(context).clearUser();
        order.verify(authSession).removeAuthNote(KEYCLOAK_ATTEMPTED_USERNAME);
        order.verify(context).setUser(user);
        order.verify(service).authenticate(any(), anyString(), anyString());
        order.verify(context).failureChallenge(eq(AuthenticationFlowError.INVALID_CREDENTIALS), any());
    }

    @Test
    void g2StaleNoteRemovedWhenImapThrows() {
        when(service.authenticate(any(), anyString(), anyString())).thenThrow(new IllegalStateException("x"));
        auth.action(context);
        assertStaleNoteRemovedFirst();
        InOrder order = inOrder(authSession, context);
        order.verify(authSession).removeAuthNote(KEYCLOAK_ATTEMPTED_USERNAME);
        order.verify(context).failureChallenge(any(), any());
    }

    @Test
    void g2StaleNoteRemovedForUnmanagedLocalUser() {
        unmanagedLocalUser();
        auth.action(context);
        assertStaleNoteRemovedFirst();
        verify(context, never()).setUser(any());
        InOrder order = inOrder(authSession, context);
        order.verify(authSession).removeAuthNote(KEYCLOAK_ATTEMPTED_USERNAME);
        order.verify(context).failureChallenge(any(), any());
    }

    @Test
    void g2StaleNoteRemovedForUnmanagedLocalUserWithUsernameEqualToEmail() {
        unmanagedLocalUser();
        when(user.getUsername()).thenReturn("user@example.invalid");
        auth.action(context);
        InOrder order = inOrder(authSession, context);
        order.verify(authSession).removeAuthNote(KEYCLOAK_ATTEMPTED_USERNAME);
        order.verify(context).failureChallenge(any(), any());
        verify(context, never()).setUser(any());
    }

    @Test
    void g2StaleNoteRemovedForDomainRejection() {
        form.putSingle("username", "local@other.invalid");
        auth.action(context);
        InOrder order = inOrder(authSession, context);
        order.verify(authSession).removeAuthNote(KEYCLOAK_ATTEMPTED_USERNAME);
        order.verify(context).failureChallenge(any(), any());
        verifyNoInteractions(service);
    }

    @Test
    void g2StaleNoteRemovedForUnknownUser() {
        when(context.getSession().users().getUserByEmail(any(), anyString())).thenReturn(null);
        auth.action(context);
        InOrder order = inOrder(authSession, service, context);
        order.verify(authSession).removeAuthNote(KEYCLOAK_ATTEMPTED_USERNAME);
        order.verify(service).authenticate(any(), anyString(), anyString());
        order.verify(context).failureChallenge(any(), any());
        verify(context, never()).setUser(any());
    }

    @Test
    void g2StaleNoteRemovedForInvalidConfigurationAndMalformedInput() {
        config.remove(ImapEmailDomains.CONFIG_KEY);
        auth.action(context);
        config.put(ImapEmailDomains.CONFIG_KEY, "example.invalid");
        form.putSingle("username", "not-an-address");
        auth.action(context);
        form.putSingle("username", "user@example.invalid");
        form.remove("password");
        auth.action(context);
        verify(authSession, times(3)).removeAuthNote(KEYCLOAK_ATTEMPTED_USERNAME);
        verify(context, times(3)).failureChallenge(any(), any());
        verifyNoInteractions(service);
    }

    @Test
    void g2StaleNoteRemovedForMissingUserProfileProtection() {
        var session = context.getSession();
        doReturn(null).when(session).getProvider(UserProfileProvider.class);
        auth.action(context);
        InOrder order = inOrder(authSession, context);
        order.verify(authSession).removeAuthNote(KEYCLOAK_ATTEMPTED_USERNAME);
        order.verify(context).failureChallenge(any(), any());
    }

    @Test
    void g2StaleNoteRemovedBeforeLockedUserChallenge() {
        when(context.getProtector().isTemporarilyDisabled(any(), any(), any())).thenReturn(true);
        auth.action(context);
        InOrder order = inOrder(authSession, context);
        order.verify(authSession).removeAuthNote(KEYCLOAK_ATTEMPTED_USERNAME);
        order.verify(context).forceChallenge(any());
    }

    @Test
    void g2StaleNoteRemovedOnSuccessSoItIsNotUsedAsLoginUsername() {
        when(service.authenticate(any(), anyString(), anyString())).thenReturn(true);
        auth.action(context);
        InOrder order = inOrder(authSession, context);
        order.verify(authSession).removeAuthNote(KEYCLOAK_ATTEMPTED_USERNAME);
        order.verify(context).success(PasswordCredentialModel.TYPE);
    }

    @Test
    void g2RemovalHappensOnEverySubmission() {
        auth.action(context);
        auth.action(context);
        verify(authSession, times(2)).removeAuthNote(KEYCLOAK_ATTEMPTED_USERNAME);
    }

    @Test
    void g2RenderingTheFormDoesNotTouchKeycloakNote() {
        auth.authenticate(context);
        verify(authSession, never()).removeAuthNote(any());
        assertKeycloakAttemptedUsernameUntouched();
    }

    @Test
    void privateRedisplayNoteIsKept() {
        auth.action(context);
        verify(authSession).setAuthNote(ImapAuthenticator.ATTEMPTED_EMAIL_NOTE, "user@example.invalid");
        verify(authSession, never()).removeAuthNote(ImapAuthenticator.ATTEMPTED_EMAIL_NOTE);
        assertEquals(AbstractUsernameFormAuthenticator.ATTEMPTED_USERNAME,
                ImapAuthenticator.KEYCLOAK_ATTEMPTED_USERNAME_NOTE);
        assertEquals(AbstractUsernameFormAuthenticator.ATTEMPTED_USERNAME, KEYCLOAK_ATTEMPTED_USERNAME);
    }
}
