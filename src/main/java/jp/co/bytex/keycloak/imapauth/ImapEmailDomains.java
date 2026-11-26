package jp.co.bytex.keycloak.imapauth;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Exact-match allowlist of the e-mail domains an IMAP execution trusts. A missing, empty or invalid configuration is
 * rejected as a whole so that the authenticator fails closed.
 */
final class ImapEmailDomains {
    static final String CONFIG_KEY = "allowedEmailDomains";

    static final int MAX_DOMAINS = 100;

    private static final int MAX_CONFIGURATION_LENGTH = 16000;

    private static final int MAX_DOMAIN_LENGTH = 253;

    private static final Pattern ASCII_DOMAIN_CHARACTERS = Pattern.compile("[A-Za-z0-9.-]+");

    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private final Set<String> domains;

    private ImapEmailDomains(Set<String> domains) {
        this.domains = domains;
    }

    /** Fixed, non-sensitive reasons for an invalid configuration, safe to write to logs. */
    enum Reason {
        MISSING("missing"), TOO_LONG("too_long"), INVALID_ENTRY("invalid_entry"), TOO_MANY("too_many_domains");

        private final String logValue;

        Reason(String logValue) {
            this.logValue = logValue;
        }

        String logValue() {
            return logValue;
        }
    }

    static final class InvalidConfigurationException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private final Reason reason;

        InvalidConfigurationException(Reason reason) {
            super(CONFIG_KEY);
            this.reason = reason;
        }

        Reason reason() {
            return reason;
        }
    }

    static ImapEmailDomains parse(Map<String, String> values) {
        String text = values == null ? null : values.get(CONFIG_KEY);
        if (text == null || text.isBlank()) {
            throw new InvalidConfigurationException(Reason.MISSING);
        }
        if (text.length() > MAX_CONFIGURATION_LENGTH) {
            throw new InvalidConfigurationException(Reason.TOO_LONG);
        }

        Set<String> domains = new LinkedHashSet<>();
        for (String rawEntry : text.split(",", -1)) {
            String entry = rawEntry.trim();
            // Reject non-ASCII before lower-casing: Locale.ROOT folds some non-ASCII characters (e.g. KELVIN SIGN)
            // into ASCII letters.
            if (!ASCII_DOMAIN_CHARACTERS.matcher(entry).matches()) {
                throw new InvalidConfigurationException(Reason.INVALID_ENTRY);
            }
            String domain = entry.toLowerCase(Locale.ROOT);
            if (!isValidDomain(domain)) {
                throw new InvalidConfigurationException(Reason.INVALID_ENTRY);
            }
            // Duplicates after normalization are ignored.
            domains.add(domain);
            if (domains.size() > MAX_DOMAINS) {
                throw new InvalidConfigurationException(Reason.TOO_MANY);
            }
        }
        return new ImapEmailDomains(Set.copyOf(domains));
    }

    private static boolean isValidDomain(String domain) {
        if (domain.length() > MAX_DOMAIN_LENGTH) {
            return false;
        }
        // A limit of -1 keeps empty labels, so leading, trailing and consecutive dots are rejected.
        for (String label : domain.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether the domain of an e-mail address already normalized by {@link ImapConfiguration#normalize} is
     * listed exactly. Suffixes and subdomains never match implicitly.
     */
    boolean allows(String normalizedEmail) {
        if (normalizedEmail == null) {
            return false;
        }
        int separator = normalizedEmail.indexOf('@');
        // Exactly one '@' is required; normalize() already guarantees this, the check keeps the method safe on its own.
        if (separator < 0 || separator != normalizedEmail.lastIndexOf('@')) {
            return false;
        }
        return domains.contains(normalizedEmail.substring(separator + 1));
    }

    Set<String> domains() {
        return domains;
    }
}
