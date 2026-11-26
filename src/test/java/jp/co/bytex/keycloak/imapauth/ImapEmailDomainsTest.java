package jp.co.bytex.keycloak.imapauth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * allowedEmailDomains の解析、正規化、完全一致判定を検証する。
 *
 * <p>
 * 欠落・空・不正な設定は全体を無効として扱い、認証器が fail-closed になることを前提とする。
 */
class ImapEmailDomainsTest {

    static ImapEmailDomains parse(String value) {
        return ImapEmailDomains.parse(Map.of(ImapEmailDomains.CONFIG_KEY, value));
    }

    static ImapEmailDomains.Reason rejection(String value) {
        return assertThrows(ImapEmailDomains.InvalidConfigurationException.class, () -> parse(value)).reason();
    }

    @Test
    void singleDomain() {
        assertEquals(Set.of("example.com"), parse("example.com").domains());
    }

    @Test
    void domainsAreLowerCased() {
        assertEquals(Set.of("example.com"), parse("Example.COM").domains());
    }

    @Test
    void multipleDomainsAreTrimmed() {
        assertEquals(Set.of("example.com", "example.co.jp"), parse("  example.com , example.co.jp\t").domains());
    }

    @Test
    void duplicatesAfterNormalizationAreIgnored() {
        assertEquals(Set.of("example.com", "example.org"),
                parse("example.com, EXAMPLE.com,example.org,Example.Com").domains());
    }

    @Test
    void punycodeAndSingleLabelDomainsAreAccepted() {
        assertEquals(Set.of("xn--eckwd4c7c.jp", "corp"), parse("xn--eckwd4c7c.jp,corp").domains());
    }

    @Test
    void missingKeyIsRejected() {
        var reason = assertThrows(ImapEmailDomains.InvalidConfigurationException.class,
                () -> ImapEmailDomains.parse(Map.of("host", "mail.example.invalid"))).reason();
        assertEquals(ImapEmailDomains.Reason.MISSING, reason);
    }

    @Test
    void nullConfigurationIsRejected() {
        var reason = assertThrows(ImapEmailDomains.InvalidConfigurationException.class,
                () -> ImapEmailDomains.parse(null)).reason();
        assertEquals(ImapEmailDomains.Reason.MISSING, reason);
    }

    @Test
    void nullValueIsRejected() {
        var values = new HashMap<String, String>();
        values.put(ImapEmailDomains.CONFIG_KEY, null);
        var reason = assertThrows(ImapEmailDomains.InvalidConfigurationException.class,
                () -> ImapEmailDomains.parse(values)).reason();
        assertEquals(ImapEmailDomains.Reason.MISSING, reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void blankValueIsRejected(String value) {
        assertEquals(ImapEmailDomains.Reason.MISSING, rejection(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {",", "example.com,", ",example.com", "example.com,,example.org", "example.com, ,x.org"})
    void emptyEntriesAreRejected(String value) {
        assertEquals(ImapEmailDomains.Reason.INVALID_ENTRY, rejection(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "*.example.com", "example.*", ".example.com", "example.com.", "example..com",
            "@example.com", "user@example.com", "exa mple.com", "example.com:993", "example.com/path",
            "-example.com", "example-.com", "example.-com", "exa_mple.com", "例え.jp", "Kexample.com",
            "xn--eckwd4c7c.jp;example.com"})
    void invalidDomainsAreRejected(String value) {
        assertEquals(ImapEmailDomains.Reason.INVALID_ENTRY, rejection(value));
    }

    @Test
    void invalidEntryRejectsWholeConfiguration() {
        assertEquals(ImapEmailDomains.Reason.INVALID_ENTRY, rejection("example.com,*,example.org"));
    }

    @Test
    void labelAndDomainLengthLimits() {
        String label63 = "a".repeat(63);
        assertEquals(Set.of(label63 + ".com"), parse(label63 + ".com").domains());
        assertEquals(ImapEmailDomains.Reason.INVALID_ENTRY, rejection("a".repeat(64) + ".com"));

        String domain253 = String.join(".", "a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(61));
        assertEquals(253, domain253.length());
        assertEquals(Set.of(domain253), parse(domain253).domains());
        assertEquals(ImapEmailDomains.Reason.INVALID_ENTRY, rejection(domain253 + "d"));
    }

    @Test
    void atMostOneHundredDistinctDomains() {
        String hundred = IntStream.range(0, ImapEmailDomains.MAX_DOMAINS).mapToObj(i -> "d" + i + ".example")
                .collect(Collectors.joining(","));
        assertEquals(ImapEmailDomains.MAX_DOMAINS, parse(hundred).domains().size());
        assertEquals(ImapEmailDomains.Reason.TOO_MANY, rejection(hundred + ",d100.example"));
        // Duplicates do not count towards the limit.
        assertEquals(ImapEmailDomains.MAX_DOMAINS, parse(hundred + ",D0.EXAMPLE").domains().size());
    }

    @Test
    void overlongConfigurationIsRejected() {
        String overlong = "a".repeat(63) + ".com," + " ".repeat(16000);
        assertEquals(ImapEmailDomains.Reason.TOO_LONG, rejection(overlong));
    }

    @Test
    void exactDomainMatches() {
        var domains = parse("example.com,example.co.jp");
        assertTrue(domains.allows("user@example.com"));
        assertTrue(domains.allows("user@example.co.jp"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user@mail.example.com", "user@notexample.com", "user@evil-example.com",
            "user@example.com.attacker.net", "user@example.co", "user@com", "user@", "example.com", ""})
    void suffixesSubdomainsAndMalformedAddressesDoNotMatch(String email) {
        assertFalse(parse("example.com").allows(email));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@b@example.com", "user@other.org@example.com", "@@example.com", "user@@example.com"})
    void addressesWithMoreThanOneAtSignDoNotMatch(String email) {
        assertFalse(parse("example.com").allows(email));
    }

    @Test
    void adminRestMaskWrittenBackIsRejected() {
        // Keycloak's Admin REST masks undeclared config values as "**********"; writing that back must fail closed.
        assertEquals(ImapEmailDomains.Reason.INVALID_ENTRY, rejection("**********"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.com\nb.com", "a.com\r\nb.com", "a.com b.com", "a.com;b.com", "a.com\u0000b.com"})
    void separatorsOtherThanCommaAreRejected(String value) {
        assertEquals(ImapEmailDomains.Reason.INVALID_ENTRY, rejection(value));
    }

    @Test
    void listedSubdomainDoesNotAllowParent() {
        assertFalse(parse("mail.example.com").allows("user@example.com"));
    }

    @Test
    void nullAddressDoesNotMatch() {
        assertFalse(parse("example.com").allows(null));
    }

    @Test
    void parsedSetIsImmutable() {
        assertThrows(UnsupportedOperationException.class, () -> parse("example.com").domains().add("x.org"));
    }
}
