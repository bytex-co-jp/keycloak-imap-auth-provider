package jp.co.bytex.keycloak.imapauth;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Properties;
import java.util.Set;

import javax.net.ssl.SSLException;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import jakarta.mail.Store;

import org.jboss.logging.Logger;

public class ImapAuthenticationService {
    private static final Logger LOGGER = Logger.getLogger(ImapAuthenticationService.class);

    private static final int MAX_INSPECTED_CAUSES = 32;

    protected Store open(Session session) throws MessagingException {
        return session.getStore("imap");
    }

    public boolean authenticate(ImapConfiguration configuration, String email, String password) {
        Store store = null;
        LOGGER.debug("IMAP_AUTH_START");
        try {
            store = open(Session.getInstance(properties(configuration)));
            store.connect(configuration.host(), configuration.port(), email, password);
            boolean authenticated = store.isConnected();
            if (authenticated) {
                LOGGER.debug("IMAP_AUTH_SUCCESS");
            } else {
                LOGGER.warn("IMAP_AUTH_REJECTED reason=store_not_connected");
            }
            return authenticated;
        } catch (RuntimeException | LinkageError | MessagingException failure) {
            LOGGER.warnf("%s exceptionType=%s", failureEvent(failure), failure.getClass().getName());
            return false;
        } finally {
            closeStore(store);
        }
    }

    private static void closeStore(Store store) {
        if (store == null) {
            return;
        }
        try {
            store.close();
        } catch (MessagingException closeFailure) {
            LOGGER.debugf("IMAP_CLOSE_FAILED exceptionType=%s", closeFailure.getClass().getName());
        }
    }

    /**
     * Classifies a failure by walking its cause and next-exception chain. TLS failures take precedence over every other
     * classification; runtime failures take precedence over rejected credentials.
     */
    static String failureEvent(Throwable failure) {
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(failure);
        boolean rejected = false;
        boolean runtimeFailure = false;

        while (!pending.isEmpty() && visited.size() < MAX_INSPECTED_CAUSES) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof SSLException) {
                return "IMAP_TLS_FAILED";
            }
            rejected |= current instanceof AuthenticationFailedException;
            runtimeFailure |= current instanceof RuntimeException || current instanceof LinkageError
                    || current instanceof NoSuchProviderException;
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
            if (current instanceof MessagingException messaging && messaging.getNextException() != null) {
                pending.add(messaging.getNextException());
            }
        }

        if (runtimeFailure) {
            return "IMAP_RUNTIME_FAILED";
        }
        return rejected ? "IMAP_AUTH_REJECTED" : "IMAP_CONNECTION_FAILED";
    }

    static Properties properties(ImapConfiguration configuration) {
        boolean ssl = configuration.tls().equals("SSL");
        boolean startTls = configuration.tls().equals("STARTTLS");
        Properties properties = new Properties();
        properties.setProperty("mail.imap.ssl.enable", String.valueOf(ssl));
        properties.setProperty("mail.imap.starttls.enable", String.valueOf(startTls));
        properties.setProperty("mail.imap.starttls.required", String.valueOf(startTls));
        properties.setProperty("mail.imap.ssl.checkserveridentity", "true");
        properties.setProperty("mail.imap.ssl.protocols", "TLSv1.3 TLSv1.2");
        properties.setProperty("mail.imap.connectiontimeout", String.valueOf(configuration.connectTimeout()));
        properties.setProperty("mail.imap.timeout", String.valueOf(configuration.readTimeout()));
        properties.setProperty("mail.imap.writetimeout", String.valueOf(configuration.readTimeout()));
        properties.setProperty("mail.debug", "false");
        return properties;
    }
}
