package jp.co.bytex.keycloak.imapauth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import jakarta.ws.rs.core.MultivaluedHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPAttributePermissions;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.userprofile.UserProfileProvider;

/**
 * autoCreate=false の場合、Keycloak に存在しないアドレスは IMAP に接続する前に拒否する (H-2B)。autoCreate=true、既存の管理対象ユーザー、
 * 未バインドのローカルユーザーの扱いは変えない。
 */
class ImapUnknownUserTest {

    static final String EMAIL = "user@example.invalid";

    static final String PASSWORD = "synthetic-test-password";

    AuthenticationFlowContext context;

    AuthenticationSessionModel authSession;

    ImapAuthenticationService service;

    Map<String, String> config;

    MultivaluedHashMap<String, String> form;

    UserModel managed;

    @BeforeEach
    void setup() {
        context = mock(AuthenticationFlowContext.class, RETURNS_DEEP_STUBS);
        service = mock(ImapAuthenticationService.class);
        var profile = new UPConfig();
        for (String name : List.of(ImapAuthenticator.BINDING, ImapAuthenticator.IDENTITY)) {
            profile.addOrReplaceAttribute(
                    new UPAttribute(name, new UPAttributePermissions(Set.of("admin"), Set.of("admin"))));
        }
        var profileProvider = mock(UserProfileProvider.class);
        when(profileProvider.getConfiguration()).thenReturn(profile);
        var session = context.getSession();
        doReturn(profileProvider).when(session).getProvider(UserProfileProvider.class);
        config = new HashMap<>(Map.of("host", "mail.example.invalid", ImapEmailDomains.CONFIG_KEY, "example.invalid"));
        when(context.getAuthenticatorConfig().getConfig()).thenAnswer(invocation -> config);
        form = new MultivaluedHashMap<>();
        form.putSingle("username", EMAIL);
        form.putSingle("password", PASSWORD);
        when(context.getHttpRequest().getDecodedFormParameters()).thenReturn(form);
        when(context.getRealm().isDuplicateEmailsAllowed()).thenReturn(false);
        when(context.getRealm().getDefaultRole()).thenReturn(null);
        authSession = context.getAuthenticationSession();
        managed = managedUser();
        when(context.getSession().users().getUserByEmail(any(), anyString())).thenReturn(null);
        when(context.getSession().users().getUserByUsername(any(), anyString())).thenReturn(null);
        when(service.authenticate(any(), anyString(), anyString())).thenReturn(true);
    }

    static UserModel managedUser() {
        UserModel user = mock(UserModel.class);
        when(user.isEnabled()).thenReturn(true);
        when(user.getEmail()).thenReturn(EMAIL);
        when(user.getFirstAttribute(ImapAuthenticator.BINDING)).thenReturn("mail.example.invalid:993:SSL");
        when(user.getFirstAttribute(ImapAuthenticator.IDENTITY)).thenReturn(EMAIL);
        return user;
    }

    void autoCreate(boolean enabled) {
        config.put("autoCreate", Boolean.toString(enabled));
    }

    void existing(UserModel user) {
        when(context.getSession().users().getUserByEmail(any(), anyString())).thenReturn(user);
    }

    List<String> run() {
        return warnings(() -> new ImapAuthenticator(service).action(context));
    }

    void assertGenericFailure() {
        verify(context).failureChallenge(eq(AuthenticationFlowError.INVALID_CREDENTIALS), any());
        verify(context.getEvent()).error("invalid_user_credentials");
        verify(context, never()).success();
        verify(context, never()).success(any());
        verify(context, never()).forceChallenge(any());
    }

    void assertKeycloakAttemptedUsernameNotWritten() {
        verify(authSession, never()).setAuthNote(eq(ImapAuthenticator.KEYCLOAK_ATTEMPTED_USERNAME_NOTE), any());
    }

    @Test
    void unknownUserWithoutAutoCreateIsRejectedBeforeImap() {
        autoCreate(false);
        List<String> warnings = run();
        verifyNoInteractions(service);
        assertGenericFailure();
        verify(context, never()).setUser(any());
        verify(context.getSession().users(), never()).addUser(any(), anyString());
        assertKeycloakAttemptedUsernameNotWritten();
        assertEquals(List.of("KEYCLOAK_USER_LOOKUP_FAILED reason=unknown_user_auto_create_disabled"), warnings);
    }

    @Test
    void autoCreateDefaultsToDisabledAndRejectsBeforeImap() {
        config.remove("autoCreate");
        run();
        verifyNoInteractions(service);
        assertGenericFailure();
    }

    @Test
    void unknownUserIsRejectedWithoutImapEvenWhenTheServerIsUnavailable() {
        autoCreate(false);
        when(service.authenticate(any(), anyString(), anyString())).thenThrow(new IllegalStateException("down"));
        run();
        verifyNoInteractions(service);
        assertGenericFailure();
    }

    @Test
    void unknownUserWithAutoCreateStillAuthenticatesAndCreates() {
        autoCreate(true);
        UserModel created = managedUser();
        when(context.getSession().users().addUser(any(), eq(EMAIL))).thenReturn(created);
        run();
        verify(service).authenticate(any(), eq(EMAIL), eq(PASSWORD));
        verify(created).setSingleAttribute(ImapAuthenticator.BINDING, "mail.example.invalid:993:SSL");
        verify(created).setSingleAttribute(ImapAuthenticator.IDENTITY, EMAIL);
        verify(context).success(PasswordCredentialModel.TYPE);
    }

    @Test
    void unknownUserWithAutoCreateAndWrongPasswordStillReachesImapAndIsNotCreated() {
        autoCreate(true);
        when(service.authenticate(any(), anyString(), anyString())).thenReturn(false);
        run();
        verify(service).authenticate(any(), eq(EMAIL), eq(PASSWORD));
        verify(context.getSession().users(), never()).addUser(any(), anyString());
        assertGenericFailure();
    }

    @Test
    void autoCreateUsernameConflictIsStillCheckedAfterImapAsBefore() {
        autoCreate(true);
        when(context.getSession().users().getUserByUsername(any(), anyString())).thenReturn(mock(UserModel.class));
        List<String> warnings = run();
        verify(service).authenticate(any(), anyString(), anyString());
        verify(context.getSession().users(), never()).addUser(any(), anyString());
        assertGenericFailure();
        assertEquals(List.of("KEYCLOAK_USER_CREATE_FAILED reason=creation_disabled_or_username_conflict"), warnings);
    }

    @Test
    void managedUserWithoutAutoCreateLogsInThroughImap() {
        autoCreate(false);
        existing(managed);
        run();
        verify(service).authenticate(any(), eq(EMAIL), eq(PASSWORD));
        verify(context).success(PasswordCredentialModel.TYPE);
    }

    @Test
    void managedUserWithAutoCreateLogsInThroughImap() {
        autoCreate(true);
        existing(managed);
        run();
        verify(service).authenticate(any(), eq(EMAIL), eq(PASSWORD));
        verify(context).success(PasswordCredentialModel.TYPE);
        verify(context.getSession().users(), never()).addUser(any(), anyString());
    }

    @Test
    void managedUserFailureIsStillChargedToThatUser() {
        autoCreate(false);
        existing(managed);
        when(service.authenticate(any(), anyString(), anyString())).thenReturn(false);
        run();
        verify(context).setUser(managed);
        assertGenericFailure();
    }

    @Test
    void lockedManagedUserStillNeverReachesImap() {
        autoCreate(false);
        existing(managed);
        when(context.getProtector().isTemporarilyDisabled(any(), any(), any())).thenReturn(true);
        run();
        verifyNoInteractions(service);
        verify(context).forceChallenge(any());
    }

    @Test
    void unboundLocalUserIsNeitherAuthenticatedNorBound() {
        for (boolean enabled : new boolean[]{false, true}) {
            setup();
            autoCreate(enabled);
            UserModel local = mock(UserModel.class);
            when(local.isEnabled()).thenReturn(true);
            when(local.getEmail()).thenReturn(EMAIL);
            when(local.getUsername()).thenReturn(EMAIL);
            existing(local);
            run();
            verifyNoInteractions(service);
            verify(local, never()).setSingleAttribute(anyString(), anyString());
            verify(local, never()).setEmailVerified(anyBoolean());
            verify(context, never()).setUser(any());
            verify(context.getSession().users(), never()).addUser(any(), anyString());
            assertGenericFailure();
        }
    }

    @Test
    void domainRejectionStillHappensBeforeTheUserLookup() {
        autoCreate(false);
        form.putSingle("username", "user@other.invalid");
        run();
        verify(context.getSession().users(), never()).getUserByEmail(any(), anyString());
        verifyNoInteractions(service);
        assertGenericFailure();
    }

    @Test
    void unknownUserGetsTheSameResponseAsAManagedUserWithAWrongPassword() {
        autoCreate(false);
        run();
        verify(authSession).setAuthNote(ImapAuthenticator.ATTEMPTED_EMAIL_NOTE, EMAIL);
        verify(context.getEvent()).detail("username", EMAIL);
        verify(context.form(), atLeastOnce()).setAttributeMapper(any());

        setup();
        autoCreate(false);
        existing(managed);
        when(service.authenticate(any(), anyString(), anyString())).thenReturn(false);
        run();
        verify(authSession).setAuthNote(ImapAuthenticator.ATTEMPTED_EMAIL_NOTE, EMAIL);
        verify(context.getEvent()).detail("username", EMAIL);
        assertGenericFailure();
    }

    @Test
    void warningsNeverContainTheAddressOrPassword() {
        autoCreate(false);
        for (String line : run()) {
            assertFalse(line.contains(EMAIL), line);
            assertFalse(line.contains(PASSWORD), line);
        }
    }

    /** Captures WARN records of the authenticator; the plugin logs through JBoss Logging, which uses JUL in tests. */
    static List<String> warnings(Runnable action) {
        var records = new ArrayList<String>();
        var logger = Logger.getLogger(ImapAuthenticator.class.getName());
        var previous = logger.getLevel();
        var handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    records.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
        return records;
    }
}
