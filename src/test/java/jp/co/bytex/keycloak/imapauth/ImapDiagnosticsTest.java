package jp.co.bytex.keycloak.imapauth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;

import javax.net.ssl.SSLHandshakeException;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import jakarta.mail.Store;

import org.junit.jupiter.api.Test;

/**
 * 資格情報を含む例外メッセージを使用せず、接続方式と失敗段階を診断できることを検証する。
 */
class ImapDiagnosticsTest {
    @Test
    void rejectedCredentialsHaveSeparateEvent() {
        assertEquals("IMAP_AUTH_REJECTED",
                ImapAuthenticationService.failureEvent(new AuthenticationFailedException("synthetic-secret")));
    }

    @Test
    void connectionAndTimeoutHaveConnectionEvent() {
        for (Exception cause : new Exception[]{new ConnectException(), new SocketTimeoutException()}) {
            assertEquals("IMAP_CONNECTION_FAILED",
                    ImapAuthenticationService.failureEvent(new MessagingException("synthetic-secret", cause)));
        }
    }

    @Test
    void nestedTlsFailureIsNotReportedAsBadPassword() {
        assertEquals("IMAP_TLS_FAILED", ImapAuthenticationService.failureEvent(
                new MessagingException("synthetic-secret", new SSLHandshakeException("synthetic-secret"))));
    }

    @Test
    void missingMailProviderHasRuntimeEvent() {
        assertEquals("IMAP_RUNTIME_FAILED",
                ImapAuthenticationService.failureEvent(new NoSuchProviderException("synthetic-secret")));
    }

    @Test
    void runtimeAndLinkageFailuresAreClassified() {
        assertEquals("IMAP_RUNTIME_FAILED",
                ImapAuthenticationService.failureEvent(new IllegalStateException("synthetic-secret")));
        assertEquals("IMAP_RUNTIME_FAILED",
                ImapAuthenticationService.failureEvent(new NoClassDefFoundError("synthetic-secret")));
    }

    @Test
    void nextExceptionAndCyclicCauseTerminate() {
        MessagingException wrapper = new MessagingException("synthetic-secret");
        wrapper.setNextException(new SSLHandshakeException("synthetic-secret"));
        assertEquals("IMAP_TLS_FAILED", ImapAuthenticationService.failureEvent(wrapper));
        RuntimeException first = new RuntimeException("synthetic-secret");
        RuntimeException second = new RuntimeException("synthetic-secret");
        first.initCause(second);
        second.initCause(first);
        assertEquals("IMAP_RUNTIME_FAILED", ImapAuthenticationService.failureEvent(first));
    }

    @Test
    void ssl993PreservesFullMailboxAndPassword() throws Exception {
        verifySuccessfulConnection("SSL", 993);
    }

    @Test
    void starttls143RequiresUpgradeAndPreservesPassword() throws Exception {
        verifySuccessfulConnection("STARTTLS", 143);
    }

    private void verifySuccessfulConnection(String mode, int port) throws Exception {
        Store store = mock(Store.class);
        when(store.isConnected()).thenReturn(true);
        ImapAuthenticationService service = new ImapAuthenticationService() {
            protected Store open(Session session) {
                assertEquals(mode.equals("SSL") ? "true" : "false",
                        session.getProperty("mail.imap.ssl.enable"));
                assertEquals(mode.equals("STARTTLS") ? "true" : "false",
                        session.getProperty("mail.imap.starttls.required"));
                assertEquals("true", session.getProperty("mail.imap.ssl.checkserveridentity"));
                assertEquals("false", session.getProperty("mail.debug"));
                return store;
            }
        };
        var configuration = ImapConfiguration.parse(Map.of("host", "mail.example.invalid",
                "tls", mode, "port", Integer.toString(port)));
        String password = " synthetic<&>-password ";
        assertTrue(service.authenticate(configuration, "mailbox@example.invalid", password));
        verify(store).connect("mail.example.invalid", port, "mailbox@example.invalid", password);
        verify(store).close();
    }

    @Test
    void rejectedPasswordNeverSucceedsAndClosesStore() throws Exception {
        Store store = mock(Store.class);
        doThrow(new AuthenticationFailedException("synthetic-secret"))
                .when(store).connect(anyString(), anyInt(), anyString(), anyString());
        ImapAuthenticationService service = new ImapAuthenticationService() {
            protected Store open(Session session) {
                return store;
            }
        };
        assertFalse(service.authenticate(ImapConfiguration.parse(Map.of("host", "mail.example.invalid")),
                "mailbox@example.invalid", "synthetic-secret"));
        verify(store).close();
    }

    @Test
    void missingProviderFailsClosed() {
        ImapAuthenticationService service = new ImapAuthenticationService() {
            protected Store open(Session session) {
                throw new NoClassDefFoundError("synthetic-missing-provider");
            }
        };
        assertFalse(service.authenticate(ImapConfiguration.parse(Map.of("host", "mail.example.invalid")),
                "mailbox@example.invalid", "synthetic-secret"));
    }

    @Test
    void diagnosticLogsDoNotContainCredentialsOrExceptionMessages() throws Exception {
        var records = new java.util.ArrayList<java.util.logging.LogRecord>();
        var logger = java.util.logging.Logger.getLogger(ImapAuthenticationService.class.getName());
        var previousLevel = logger.getLevel();
        var handler = new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) {
                records.add(record);
            }
            public void flush() {
            }
            public void close() {
            }
        };
        logger.addHandler(handler);
        logger.setLevel(java.util.logging.Level.ALL);
        try {
            Store store = mock(Store.class);
            doThrow(new AuthenticationFailedException("synthetic-secret mailbox@example.invalid"))
                    .when(store).connect(anyString(), anyInt(), anyString(), anyString());
            ImapAuthenticationService service = new ImapAuthenticationService() {
                protected Store open(Session session) {
                    return store;
                }
            };
            assertFalse(service.authenticate(ImapConfiguration.parse(Map.of("host", "mail.example.invalid")),
                    "mailbox@example.invalid", "synthetic-secret"));
            assertFalse(records.isEmpty());
            for (var record : records) {
                String contents = record.getMessage() + java.util.Arrays.toString(record.getParameters());
                assertFalse(contents.contains("synthetic-secret"));
                assertFalse(contents.contains("mailbox@example.invalid"));
                assertNull(record.getThrown());
            }
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }
    }
}
