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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPAttributePermissions;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.keycloak.userprofile.UserProfileProvider;

/**
 * allowedEmailDomains による認証前の拒否を検証する。
 *
 * <p>
 * 許可されていないドメインや無効な設定では、Keycloak ユーザー検索・ユーザー設定・IMAP 接続のいずれも行われないことを確認する。
 */
class ImapDomainAllowlistTest {

    AuthenticationFlowContext context;

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
        user = mock(UserModel.class);
        when(user.isEnabled()).thenReturn(true);
        when(context.getSession().users().getUserByEmail(any(), anyString())).thenReturn(user);
        when(service.authenticate(any(), anyString(), anyString())).thenReturn(true);
    }

    void boundUser(String email) {
        when(user.getEmail()).thenReturn(email);
        when(user.getFirstAttribute(ImapAuthenticator.BINDING)).thenReturn("mail.example.invalid:993:SSL");
        when(user.getFirstAttribute(ImapAuthenticator.IDENTITY)).thenReturn(email);
    }

    void assertRejectedBeforeLookupAndImap() {
        verify(context).failureChallenge(eq(AuthenticationFlowError.INVALID_CREDENTIALS), any());
        verify(context, never()).success();
        verify(context, never()).success(any());
        verifyNoInteractions(service);
        verify(context.getSession().users(), never()).getUserByEmail(any(), anyString());
        verify(context.getSession().users(), never()).getUserByUsername(any(), anyString());
        verify(context.getSession().users(), never()).addUser(any(), anyString());
        verify(context, never()).setUser(any());
        verify(user, never()).setEmailVerified(anyBoolean());
    }

    @Test
    void allowedDomainAuthenticatesBoundUser() {
        boundUser("user@example.invalid");
        auth.action(context);
        verify(service).authenticate(any(), eq("user@example.invalid"), eq("synthetic-test-password"));
        verify(context).success(PasswordCredentialModel.TYPE);
    }

    @Test
    void allowedDomainMatchesCaseInsensitively() {
        config.put(ImapEmailDomains.CONFIG_KEY, " Example.INVALID ");
        form.putSingle("username", "  USER@Example.Invalid ");
        boundUser("user@example.invalid");
        auth.action(context);
        verify(service).authenticate(any(), eq("user@example.invalid"), eq("synthetic-test-password"));
        verify(context).success(PasswordCredentialModel.TYPE);
    }

    @Test
    void oneOfSeveralDomainsAllows() {
        config.put(ImapEmailDomains.CONFIG_KEY, "other.invalid,example.invalid");
        boundUser("user@example.invalid");
        auth.action(context);
        verify(context).success(PasswordCredentialModel.TYPE);
    }

    @Test
    void allowedDomainStillAutoCreates() {
        when(context.getSession().users().getUserByEmail(any(), anyString())).thenReturn(null);
        when(context.getSession().users().getUserByUsername(any(), anyString())).thenReturn(null);
        when(context.getSession().users().addUser(any(), anyString())).thenReturn(user);
        boundUser("user@example.invalid");
        auth.action(context);
        verify(context.getSession().users()).addUser(any(), eq("user@example.invalid"));
        verify(context).success(PasswordCredentialModel.TYPE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user@other.invalid", "user@mail.example.invalid", "user@notexample.invalid",
            "user@example.invalid.attacker.test"})
    void disallowedDomainRejectedBeforeUserLookupAndImap(String email) {
        form.putSingle("username", email);
        auth.action(context);
        assertRejectedBeforeLookupAndImap();
    }

    @ParameterizedTest
    @ValueSource(strings = {"user@other.invalid@example.invalid", "user@example.invalid@other.invalid",
            "user@@example.invalid", "user@exa mple.invalid", "user@exam\u0000ple.invalid", "user@例え.invalid",
            "user@example_invalid", "user@example..invalid", "@example.invalid", "user@"})
    void malformedLoginRejectedBeforeLookupAndImap(String email) {
        form.putSingle("username", email);
        auth.action(context);
        assertRejectedBeforeLookupAndImap();
    }

    @Test
    void checkedDomainIsTheDomainSentToImap() {
        form.putSingle("username", "  User@EXAMPLE.invalid\t");
        boundUser("user@example.invalid");
        auth.action(context);
        verify(service).authenticate(any(), eq("user@example.invalid"), anyString());
        verifyNoMoreInteractions(service);
    }

    @Test
    void existingBoundUserFromDisallowedDomainRejectedBeforeImap() {
        form.putSingle("username", "user@other.invalid");
        boundUser("user@other.invalid");
        auth.action(context);
        assertRejectedBeforeLookupAndImap();
    }

    @Test
    void disallowedDomainWithoutAutoCreateRejectedBeforeImap() {
        config.put("autoCreate", "false");
        form.putSingle("username", "user@other.invalid");
        auth.action(context);
        assertRejectedBeforeLookupAndImap();
    }

    @Test
    void disallowedDomainNeedsNoRollback() {
        form.putSingle("username", "user@other.invalid");
        auth.action(context);
        verify(context.getSession().getTransactionManager(), never()).setRollbackOnly();
    }

    @Test
    void missingConfigurationFailsClosed() {
        config.remove(ImapEmailDomains.CONFIG_KEY);
        boundUser("user@example.invalid");
        auth.action(context);
        assertRejectedBeforeLookupAndImap();
        verify(context.getSession().getTransactionManager()).setRollbackOnly();
        verify(context.getHttpRequest(), never()).getDecodedFormParameters();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "*", "*.example.invalid", ".example.invalid", "example.invalid,",
            "example.invalid,*", "例え.jp"})
    void invalidConfigurationFailsClosed(String value) {
        config.put(ImapEmailDomains.CONFIG_KEY, value);
        boundUser("user@example.invalid");
        auth.action(context);
        assertRejectedBeforeLookupAndImap();
        verify(context.getSession().getTransactionManager()).setRollbackOnly();
    }

    @Test
    void nullAuthenticatorConfigFailsClosed() {
        when(context.getAuthenticatorConfig()).thenReturn(null);
        auth.action(context);
        assertRejectedBeforeLookupAndImap();
    }

    @Test
    void domainLogsAreFixedAndContainNoCredentials() {
        var records = capture(() -> {
            form.putSingle("username", "victim@other.invalid");
            auth.action(context);
            config.put(ImapEmailDomains.CONFIG_KEY, "*");
            auth.action(context);
            config.remove(ImapEmailDomains.CONFIG_KEY);
            auth.action(context);
        });
        List<String> messages = records.stream().map(ImapDomainAllowlistTest::render).toList();
        assertTrue(messages.contains("IMAP_DOMAIN_REJECTED reason=domain_not_allowed"), messages.toString());
        assertTrue(messages.contains("IMAP_DOMAIN_CONFIG_INVALID reason=invalid_entry"), messages.toString());
        assertTrue(messages.contains("IMAP_DOMAIN_CONFIG_INVALID reason=missing"), messages.toString());
        for (var record : records) {
            String contents = render(record);
            assertFalse(contents.contains("synthetic-test-password"), contents);
            assertFalse(contents.contains("victim"), contents);
            assertFalse(contents.contains("other.invalid"), contents);
            assertFalse(contents.contains("example.invalid"), contents);
            assertNull(record.getThrown());
        }
    }

    @Test
    void factoryDeclaresAllowedEmailDomainsWithoutChangingExistingProperties() {
        var properties = new ImapAuthenticatorFactory().getConfigProperties();
        assertEquals(List.of("host", "port", "tls", "connectTimeout", "readTimeout", "autoCreate", "identifier",
                "allowedDefaultRoles", "allowedDefaultGroups", "allowedEmailDomains"),
                properties.stream().map(ProviderConfigProperty::getName).toList());
        var defaults = properties.stream().collect(HashMap<String, Object>::new,
                (map, property) -> map.put(property.getName(), property.getDefaultValue()), HashMap::putAll);
        assertEquals("", defaults.get("host"));
        assertEquals("993", defaults.get("port"));
        assertEquals("SSL", defaults.get("tls"));
        assertEquals("5000", defaults.get("connectTimeout"));
        assertEquals("5000", defaults.get("readTimeout"));
        assertEquals(false, defaults.get("autoCreate"));
        assertEquals("Email", defaults.get("identifier"));
        assertEquals("", defaults.get("allowedEmailDomains"));
        assertEquals("imap-authenticator", new ImapAuthenticatorFactory().getId());
    }

    static String render(LogRecord record) {
        Object[] parameters = record.getParameters();
        String message = record.getMessage();
        return parameters == null || parameters.length == 0 ? message : String.format(message, parameters);
    }

    static List<LogRecord> capture(Runnable action) {
        var records = new ArrayList<LogRecord>();
        var logger = Logger.getLogger(ImapAuthenticator.class.getName());
        var previousLevel = logger.getLevel();
        var handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
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
            logger.setLevel(previousLevel);
        }
        return records;
    }
}
