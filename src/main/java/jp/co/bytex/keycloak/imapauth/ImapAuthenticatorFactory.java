package jp.co.bytex.keycloak.imapauth;

import java.util.List;

import org.keycloak.Config.Scope;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.provider.ProviderConfigProperty;

public final class ImapAuthenticatorFactory implements AuthenticatorFactory {
    @Override
    public String getId() {
        return "imap-authenticator";
    }

    @Override
    public String getDisplayType() {
        return "IMAP Authentication";
    }

    /**
     * The mailbox password is a password credential. Keycloak 26 applies native brute force detection only to the
     * {@code password}, {@code otp} and {@code recovery-authn-codes} categories, so any other value would leave IMAP
     * failures uncounted.
     */
    @Override
    public String getReferenceCategory() {
        return PasswordCredentialModel.TYPE;
    }

    @Override
    public String getHelpText() {
        return "Authenticate a mailbox over verified TLS";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public Requirement[] getRequirementChoices() {
        return new Requirement[]{Requirement.REQUIRED, Requirement.DISABLED};
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of(
                property("host", "IMAP Host", "", "Required DNS name"),
                property("port", "IMAP Port", "993", "1-65535"),
                property("tls", "TLS Mode", "SSL", "SSL or STARTTLS"),
                property("connectTimeout", "Connection Timeout", "5000", "100-60000 ms"),
                property("readTimeout", "Read Timeout", "5000", "100-60000 ms"),
                new ProviderConfigProperty("autoCreate", "Auto Create User", "Create managed users", "boolean", false),
                property("identifier", "Login Identifier", "Email", "Email only"),
                property(
                        "allowedDefaultRoles",
                        "Allowed Default Roles",
                        "",
                        "Comma-separated realm:role-id or client:client-uuid:role-id. No wildcards. Every composite member requires approval."),
                property(
                        "allowedDefaultGroups", "Allowed Default Groups", "",
                        "Comma-separated group:group-id. Parents and descendants require approval. No wildcards."),
                property(
                        ImapEmailDomains.CONFIG_KEY, "Allowed Email Domains", "",
                        "Required. Comma-separated exact domains, e.g. example.com,example.co.jp. No wildcards; subdomains must be listed explicitly. Logins from other domains are rejected before connecting to IMAP."));
    }

    private static ProviderConfigProperty property(String key, String label, String defaultValue, String helpText) {
        return new ProviderConfigProperty(key, label, helpText, "String", defaultValue);
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return new ImapAuthenticator();
    }

    @Override
    public void init(Scope configuration) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }
}
