package jp.co.bytex.keycloak.imapauth;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record ImapConfiguration(String host, int port, String tls, int connectTimeout, int readTimeout,
        boolean autoCreate) {
    private static final String HOST_PATTERN = "[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]";

    private static final String EMAIL_PATTERN = "[a-z0-9.!#$%&'*+/=?^_{|}~-]+@[a-z0-9][a-z0-9.-]*[a-z0-9]";

    private static final int MAX_EMAIL_LENGTH = 254;

    public static ImapConfiguration parse(Map<String, String> values) {
        Map<String, String> configurationValues = values == null ? Map.of() : values;
        String host = configurationValues.getOrDefault("host", "");
        String tlsMode = configurationValues.getOrDefault("tls", "SSL");
        String autoCreateValue = configurationValues.getOrDefault("autoCreate", "false");
        boolean valid = host.matches(HOST_PATTERN)
                && Set.of("SSL", "STARTTLS").contains(tlsMode)
                && Set.of("true", "false").contains(autoCreateValue)
                && configurationValues.getOrDefault("identifier", "Email").equals("Email");
        if (!valid) {
            throw new IllegalArgumentException();
        }
        return new ImapConfiguration(
                host.toLowerCase(Locale.ROOT),
                number(configurationValues, "port", 993, 1, 65535),
                tlsMode,
                number(configurationValues, "connectTimeout", 5000, 100, 60000),
                number(configurationValues, "readTimeout", 5000, 100, 60000),
                Boolean.parseBoolean(autoCreateValue));
    }

    static int number(Map<String, String> configurationValues, String key, int defaultValue, int min, int max) {
        int number = Integer.parseInt(configurationValues.getOrDefault(key, Integer.toString(defaultValue)));
        if (number < min || number > max) {
            throw new IllegalArgumentException();
        }
        return number;
    }

    public static String normalize(String email) {
        if (email == null || email.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException();
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (!normalizedEmail.matches(EMAIL_PATTERN) || normalizedEmail.contains("..")) {
            throw new IllegalArgumentException();
        }
        return normalizedEmail;
    }

    public String binding() {
        return host + ":" + port + ":" + tls;
    }
}
