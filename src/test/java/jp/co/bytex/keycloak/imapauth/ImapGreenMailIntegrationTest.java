package jp.co.bytex.keycloak.imapauth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.ws.rs.core.MultivaluedHashMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPAttributePermissions;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.keycloak.userprofile.UserProfileProvider;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;

/**
 * GreenMail を実際の IMAP サーバーとして使用する結合テスト。
 *
 * <p>
 * 証明書はテスト実行時に JDK の keytool で一時生成し、秘密鍵をリポジトリに含めない。GreenMail は IMAP STARTTLS コマンドを 提供しないため、STARTTLS はテスト専用のフロントエンドで TLS
 * へ昇格した後、GreenMail の平文 IMAP ポートへ中継する。 認証そのものは常に GreenMail が行う。各経路の前段で TCP 接続数を数え、拒否されたドメインや無効な設定で接続が発生しないことを検証する。
 */
class ImapGreenMailIntegrationTest {

    static final String ALLOWED_EMAIL = "user@allowed.test";

    static final String PASSWORD = "synthetic-greenmail-password";

    @TempDir
    static Path tempDirectory;

    static GreenMail greenMail;

    static SSLContext serverTls;

    static SSLSocketFactory trustingClientFactory;

    static CountingProxy sslProxy;

    static CountingProxy plainProxy;

    static StartTlsFrontEnd startTlsFrontEnd;

    static final Map<String, String> previousSystemProperties = new HashMap<>();

    @BeforeAll
    static void startServers() throws Exception {
        char[] storePassword = UUID.randomUUID().toString().toCharArray();
        Path keyStoreFile = tempDirectory.resolve("imap-test-server.p12");
        generateServerKeyStore(keyStoreFile, storePassword);

        KeyStore serverKeyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keyStoreFile)) {
            serverKeyStore.load(input, storePassword);
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(serverKeyStore, storePassword);
        serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keyManagers.getKeyManagers(), null, null);

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        Certificate certificate = serverKeyStore.getCertificate("imap-test");
        trustStore.setCertificateEntry("imap-test", certificate);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);
        SSLContext clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, trustManagers.getTrustManagers(), null);
        trustingClientFactory = clientTls.getSocketFactory();

        setSystemProperty("greenmail.tls.keystore.file", keyStoreFile.toString());
        setSystemProperty("greenmail.tls.keystore.password", new String(storePassword));
        setSystemProperty("greenmail.tls.key.password", new String(storePassword));

        String loopback = InetAddress.getLoopbackAddress().getHostAddress();
        greenMail = new GreenMail(new ServerSetup[]{
                new ServerSetup(0, loopback, ServerSetup.PROTOCOL_IMAPS).dynamicPort(),
                new ServerSetup(0, loopback, ServerSetup.PROTOCOL_IMAP).dynamicPort()});
        greenMail.start();
        greenMail.setUser(ALLOWED_EMAIL, ALLOWED_EMAIL, PASSWORD);
        greenMail.setUser("user@other.test", "user@other.test", PASSWORD);

        sslProxy = new CountingProxy(greenMail.getImaps().getPort());
        plainProxy = new CountingProxy(greenMail.getImap().getPort());
        startTlsFrontEnd = new StartTlsFrontEnd(greenMail.getImap().getPort());
    }

    @AfterAll
    static void stopServers() throws Exception {
        for (AutoCloseable closeable : new AutoCloseable[]{sslProxy, plainProxy, startTlsFrontEnd}) {
            if (closeable != null) {
                closeable.close();
            }
        }
        if (greenMail != null) {
            greenMail.stop();
        }
        previousSystemProperties.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    @BeforeEach
    void resetCounters() {
        sslProxy.connections.set(0);
        plainProxy.connections.set(0);
        startTlsFrontEnd.connections.set(0);
        startTlsFrontEnd.plaintextLogins.set(0);
    }

    // --- ImapAuthenticationService against a real IMAP server ---

    @Test
    void sslAuthenticatesAllowedMailbox() {
        assertTrue(trustingService().authenticate(sslConfiguration("localhost"), ALLOWED_EMAIL, PASSWORD));
        assertEquals(1, sslProxy.connections.get());
    }

    @Test
    void sslRejectsWrongPassword() {
        List<String> events = serviceEvents(() -> assertFalse(
                trustingService().authenticate(sslConfiguration("localhost"), ALLOWED_EMAIL, "wrong-password")));
        assertEquals(List.of("IMAP_AUTH_REJECTED"), events);
    }

    @Test
    void sslRejectsUntrustedCertificate() {
        // Production service: JVM default trust store, which does not contain the generated test certificate.
        List<String> events = serviceEvents(() -> assertFalse(
                new ImapAuthenticationService().authenticate(sslConfiguration("localhost"), ALLOWED_EMAIL,
                        PASSWORD)));
        assertEquals(List.of("IMAP_TLS_FAILED"), events);
        assertEquals(1, sslProxy.connections.get());
    }

    @Test
    void sslRejectsCertificateForAnotherHostName() {
        // Trusted certificate, but issued for "localhost" only; connecting by IP address must fail identity checks.
        String loopback = InetAddress.getLoopbackAddress().getHostAddress();
        List<String> events = serviceEvents(() -> assertFalse(
                trustingService().authenticate(sslConfiguration(loopback), ALLOWED_EMAIL, PASSWORD)));
        assertEquals(List.of("IMAP_TLS_FAILED"), events);
    }

    @Test
    void startTlsAuthenticatesAllowedMailbox() {
        assertTrue(trustingService().authenticate(startTlsConfiguration(startTlsFrontEnd.port()), ALLOWED_EMAIL,
                PASSWORD));
        assertEquals(1, startTlsFrontEnd.connections.get());
        assertEquals(0, startTlsFrontEnd.plaintextLogins.get());
    }

    @Test
    void startTlsRejectsUntrustedCertificate() {
        List<String> events = serviceEvents(() -> assertFalse(new ImapAuthenticationService()
                .authenticate(startTlsConfiguration(startTlsFrontEnd.port()), ALLOWED_EMAIL, PASSWORD)));
        assertEquals(List.of("IMAP_TLS_FAILED"), events);
        assertEquals(0, startTlsFrontEnd.plaintextLogins.get());
    }

    @Test
    void startTlsIsRequiredAndNeverFallsBackToPlaintext() {
        // GreenMail's plain IMAP port accepts LOGIN without TLS; success here would mean a plaintext fallback.
        assertFalse(trustingService().authenticate(startTlsConfiguration(plainProxy.port()), ALLOWED_EMAIL, PASSWORD));
        assertEquals(1, plainProxy.connections.get());
    }

    // --- Full authenticator flow: the domain gate runs before any IMAP connection ---

    @Test
    void allowedDomainAuthenticatesThroughGreenMail() {
        var flow = new Flow(ALLOWED_EMAIL, "allowed.test");
        flow.run();
        verify(flow.context).success(PasswordCredentialModel.TYPE);
        assertEquals(1, sslProxy.connections.get());
    }

    @Test
    void wrongPasswordForAllowedDomainIsRejectedByGreenMail() {
        var flow = new Flow(ALLOWED_EMAIL, "allowed.test");
        flow.form.putSingle("password", "wrong-password");
        flow.run();
        verify(flow.context).failureChallenge(eq(AuthenticationFlowError.INVALID_CREDENTIALS), any());
        assertEquals(1, sslProxy.connections.get());
    }

    @Test
    void disallowedDomainNeverConnects() {
        // The mailbox exists on GreenMail with the correct password, but its domain is not allowed.
        var flow = new Flow("user@other.test", "allowed.test");
        flow.run();
        verify(flow.context).failureChallenge(eq(AuthenticationFlowError.INVALID_CREDENTIALS), any());
        verify(flow.context, never()).success();
        verify(flow.context, never()).success(any());
        assertEquals(0, sslProxy.connections.get());
    }

    @Test
    void subdomainOfAllowedDomainNeverConnects() {
        var flow = new Flow("user@mail.allowed.test", "allowed.test");
        flow.run();
        verify(flow.context, never()).success();
        verify(flow.context, never()).success(any());
        assertEquals(0, sslProxy.connections.get());
    }

    @Test
    void invalidDomainConfigurationNeverConnects() {
        for (String invalid : new String[]{"*", "", ".allowed.test", "allowed.test,"}) {
            var flow = new Flow(ALLOWED_EMAIL, invalid);
            flow.run();
            verify(flow.context, never()).success();
            verify(flow.context, never()).success(any());
        }
        assertEquals(0, sslProxy.connections.get());
    }

    @Test
    void missingDomainConfigurationNeverConnects() {
        var flow = new Flow(ALLOWED_EMAIL, null);
        flow.run();
        verify(flow.context, never()).success();
        verify(flow.context, never()).success(any());
        assertEquals(0, sslProxy.connections.get());
    }

    @Test
    void unknownUserWithoutAutoCreateNeverConnects() {
        // The mailbox exists on GreenMail with the correct password, but no Keycloak user exists and autoCreate=false.
        var flow = new Flow(ALLOWED_EMAIL, "allowed.test").unknown(false);
        List<String> events = serviceEvents(flow::run);
        verify(flow.context).failureChallenge(eq(AuthenticationFlowError.INVALID_CREDENTIALS), any());
        verify(flow.context, never()).success(any());
        verify(flow.context.getSession().users(), never()).addUser(any(), anyString());
        assertEquals(0, sslProxy.connections.get());
        assertEquals(List.of(), events);
    }

    @Test
    void unknownUserWithAutoCreateConnectsAndIsCreated() {
        var flow = new Flow(ALLOWED_EMAIL, "allowed.test").unknown(true);
        flow.run();
        verify(flow.context.getSession().users()).addUser(any(), eq(ALLOWED_EMAIL));
        verify(flow.context).success(PasswordCredentialModel.TYPE);
        assertEquals(1, sslProxy.connections.get());
    }

    @Test
    void unknownUserWithoutAutoCreateIsRejectedWhileImapIsUnavailable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        var unknown = new Flow(ALLOWED_EMAIL, "allowed.test").unknown(false).port(closedPort);
        assertEquals(List.of(), serviceEvents(unknown::run));
        verify(unknown.context).failureChallenge(eq(AuthenticationFlowError.INVALID_CREDENTIALS), any());

        // A managed user still goes to IMAP and fails there.
        var managed = new Flow(ALLOWED_EMAIL, "allowed.test").port(closedPort);
        assertFalse(serviceEvents(managed::run).isEmpty());
        verify(managed.context).failureChallenge(eq(AuthenticationFlowError.INVALID_CREDENTIALS), any());
    }

    // --- helpers ---

    static ImapConfiguration sslConfiguration(String host) {
        return ImapConfiguration.parse(Map.of("host", host, "port", Integer.toString(sslProxy.port()), "tls", "SSL"));
    }

    static ImapConfiguration startTlsConfiguration(int port) {
        return ImapConfiguration.parse(Map.of("host", "localhost", "port", Integer.toString(port), "tls", "STARTTLS"));
    }

    /**
     * Uses the production session properties unchanged and only adds a socket factory trusting the generated test
     * certificate. Server identity checking stays enabled.
     */
    static ImapAuthenticationService trustingService() {
        return new ImapAuthenticationService() {
            @Override
            protected Store open(Session session) throws MessagingException {
                Properties properties = new Properties();
                properties.putAll(session.getProperties());
                assertEquals("true", properties.getProperty("mail.imap.ssl.checkserveridentity"));
                assertEquals("false", properties.getProperty("mail.debug"));
                properties.put("mail.imap.ssl.socketFactory", trustingClientFactory);
                return Session.getInstance(properties).getStore("imap");
            }
        };
    }

    static List<String> serviceEvents(Runnable action) {
        var records = new ArrayList<LogRecord>();
        var logger = Logger.getLogger(ImapAuthenticationService.class.getName());
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
        List<String> events = new ArrayList<>();
        for (LogRecord record : records) {
            String contents = record.getMessage() + java.util.Arrays.toString(record.getParameters());
            assertFalse(contents.contains(PASSWORD), contents);
            assertNull(record.getThrown());
            Object[] parameters = record.getParameters();
            if (record.getLevel().intValue() >= Level.WARNING.intValue() && parameters != null
                    && parameters.length > 0 && record.getMessage().startsWith("%s ")) {
                events.add(String.valueOf(parameters[0]));
            } else if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                events.add(record.getMessage().split(" ", 2)[0]);
            }
        }
        return events;
    }

    /** Authenticator flow with mocked Keycloak models and the real IMAP service pointed at GreenMail over SSL. */
    static final class Flow {
        final AuthenticationFlowContext context = mock(AuthenticationFlowContext.class, RETURNS_DEEP_STUBS);

        final MultivaluedHashMap<String, String> form = new MultivaluedHashMap<>();

        Flow(String email, String allowedDomains) {
            var profile = new UPConfig();
            for (String name : List.of(ImapAuthenticator.BINDING, ImapAuthenticator.IDENTITY)) {
                profile.addOrReplaceAttribute(
                        new UPAttribute(name, new UPAttributePermissions(Set.of("admin"), Set.of("admin"))));
            }
            var profileProvider = mock(UserProfileProvider.class);
            when(profileProvider.getConfiguration()).thenReturn(profile);
            var session = context.getSession();
            doReturn(profileProvider).when(session).getProvider(UserProfileProvider.class);

            config = new HashMap<>(
                    Map.of("host", "localhost", "port", Integer.toString(sslProxy.port()), "tls", "SSL"));
            if (allowedDomains != null) {
                config.put(ImapEmailDomains.CONFIG_KEY, allowedDomains);
            }
            when(context.getAuthenticatorConfig().getConfig()).thenAnswer(invocation -> config);

            form.putSingle("username", email);
            form.putSingle("password", PASSWORD);
            when(context.getHttpRequest().getDecodedFormParameters()).thenReturn(form);
            when(context.getRealm().isDuplicateEmailsAllowed()).thenReturn(false);

            this.email = email;
            UserModel existing = managedUser(email);
            when(context.getSession().users().getUserByEmail(any(), anyString())).thenReturn(existing);
        }

        final String email;

        Map<String, String> config;

        UserModel managedUser(String address) {
            UserModel user = mock(UserModel.class);
            when(user.isEnabled()).thenReturn(true);
            when(user.getEmail()).thenReturn(address);
            when(user.getFirstAttribute(ImapAuthenticator.BINDING))
                    .thenAnswer(invocation -> "localhost:" + config.get("port") + ":SSL");
            when(user.getFirstAttribute(ImapAuthenticator.IDENTITY)).thenReturn(address);
            return user;
        }

        /** No Keycloak user exists for the address; autoCreate as given. */
        Flow unknown(boolean autoCreate) {
            config.put("autoCreate", Boolean.toString(autoCreate));
            when(context.getSession().users().getUserByEmail(any(), anyString())).thenReturn(null);
            when(context.getSession().users().getUserByUsername(any(), anyString())).thenReturn(null);
            UserModel created = managedUser(email);
            when(context.getSession().users().addUser(any(), eq(email))).thenReturn(created);
            when(context.getRealm().getDefaultRole()).thenReturn(null);
            return this;
        }

        Flow port(int port) {
            config.put("port", Integer.toString(port));
            return this;
        }

        void run() {
            new ImapAuthenticator(trustingService()).action(context);
        }
    }

    static void generateServerKeyStore(Path keyStoreFile, char[] password) throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "keytool.exe" : "keytool");
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "imap-test",
                "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", keyStoreFile.toString(),
                "-storepass", new String(password), "-keypass", new String(password), "-noprompt")
                .redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "keytool timed out");
        assertEquals(0, process.exitValue(), new String(output, StandardCharsets.UTF_8));
    }

    static void setSystemProperty(String key, String value) {
        previousSystemProperties.putIfAbsent(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    static String readLine(InputStream input) throws IOException {
        // Byte-wise on purpose: buffering could swallow the TLS handshake that follows STARTTLS.
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        for (int current = input.read(); current != -1; current = input.read()) {
            if (previous == '\r' && current == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
            }
            line.write(current);
            previous = current;
        }
        return null;
    }

    static void write(OutputStream output, String text) throws IOException {
        output.write(text.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    static void pipe(Socket first, Socket second) throws InterruptedException {
        Thread forward = copy(first, second);
        Thread backward = copy(second, first);
        forward.join();
        backward.join();
    }

    static Thread copy(Socket from, Socket to) {
        Thread thread = new Thread(() -> {
            try {
                from.getInputStream().transferTo(to.getOutputStream());
            } catch (IOException ignored) {
                // Connection closed by either side.
            } finally {
                closeQuietly(from);
                closeQuietly(to);
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    /** Accepts loopback connections and handles each on its own daemon thread. */
    abstract static class LoopbackServer implements AutoCloseable {
        final AtomicInteger connections = new AtomicInteger();

        final ServerSocket serverSocket;

        LoopbackServer() throws IOException {
            serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        connections.incrementAndGet();
                        Thread handler = new Thread(() -> handle(client));
                        handler.setDaemon(true);
                        handler.start();
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        abstract void handle(Socket client);

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    /** Transparent TCP relay that counts connections made by the client under test. */
    static final class CountingProxy extends LoopbackServer {
        final int upstreamPort;

        CountingProxy(int upstreamPort) throws IOException {
            this.upstreamPort = upstreamPort;
        }

        @Override
        void handle(Socket client) {
            try {
                pipe(client, new Socket(InetAddress.getLoopbackAddress(), upstreamPort));
            } catch (IOException | InterruptedException failure) {
                closeQuietly(client);
            }
        }
    }

    /**
     * Minimal IMAP STARTTLS front end: refuses everything except CAPABILITY, STARTTLS and LOGOUT before TLS, then
     * relays the upgraded connection to GreenMail's plain IMAP port after consuming GreenMail's greeting.
     */
    static final class StartTlsFrontEnd extends LoopbackServer {
        final int upstreamPort;

        final AtomicInteger plaintextLogins = new AtomicInteger();

        StartTlsFrontEnd(int upstreamPort) throws IOException {
            this.upstreamPort = upstreamPort;
        }

        @Override
        void handle(Socket client) {
            try {
                InputStream input = client.getInputStream();
                OutputStream output = client.getOutputStream();
                write(output, "* OK [CAPABILITY IMAP4rev1 STARTTLS LOGINDISABLED] STARTTLS test front end\r\n");
                for (String line = readLine(input); line != null; line = readLine(input)) {
                    String[] parts = line.split(" ", 3);
                    String tag = parts[0];
                    String command = parts.length > 1 ? parts[1].toUpperCase(Locale.ROOT) : "";
                    switch (command) {
                        case "CAPABILITY" -> write(output, "* CAPABILITY IMAP4rev1 STARTTLS LOGINDISABLED\r\n" + tag
                                + " OK CAPABILITY completed\r\n");
                        case "STARTTLS" -> {
                            write(output, tag + " OK Begin TLS negotiation now\r\n");
                            SSLSocket tls = (SSLSocket) serverTls.getSocketFactory().createSocket(client,
                                    client.getInetAddress().getHostAddress(), client.getPort(), true);
                            tls.setUseClientMode(false);
                            tls.startHandshake();
                            Socket upstream = new Socket(InetAddress.getLoopbackAddress(), upstreamPort);
                            readLine(upstream.getInputStream());
                            pipe(tls, upstream);
                            return;
                        }
                        case "LOGOUT" -> {
                            write(output, "* BYE\r\n" + tag + " OK LOGOUT completed\r\n");
                            return;
                        }
                        default -> {
                            if (command.equals("LOGIN") || command.equals("AUTHENTICATE")) {
                                plaintextLogins.incrementAndGet();
                            }
                            write(output, tag + " BAD STARTTLS required\r\n");
                        }
                    }
                }
            } catch (IOException | InterruptedException failure) {
                // Handshake failures are expected in negative tests.
            } finally {
                closeQuietly(client);
            }
        }
    }
}
