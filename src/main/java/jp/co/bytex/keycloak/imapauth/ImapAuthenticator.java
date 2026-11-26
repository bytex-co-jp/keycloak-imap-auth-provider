package jp.co.bytex.keycloak.imapauth;

import java.util.List;
import java.util.Set;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.forms.login.freemarker.model.AuthenticationContextBean;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.keycloak.userprofile.UserProfileProvider;

public final class ImapAuthenticator implements Authenticator {
    private static final Logger LOGGER = Logger.getLogger(ImapAuthenticator.class);

    public static final String BINDING = "imap.auth.binding";

    public static final String IDENTITY = "imap.auth.identity";

    private static final List<String> PROTECTED_ATTRIBUTES = List.of(BINDING, IDENTITY);

    /**
     * Plugin-private auth note holding the e-mail to redisplay after a failed attempt. Keycloak's own
     * {@code ATTEMPTED_USERNAME} note is deliberately not written: Keycloak resolves it to a user for brute force
     * accounting, which would charge IMAP failures to unmanaged local accounts that share the e-mail address.
     */
    static final String ATTEMPTED_EMAIL_NOTE = "IMAP_ATTEMPTED_EMAIL";

    /**
     * Keycloak's attempted-username auth note ({@code AbstractUsernameFormAuthenticator.ATTEMPTED_USERNAME}). Another
     * form in the same authentication session, such as the username/password form left via "Try another way", may have
     * written it. It is removed on every IMAP submission so that a later IMAP failure is never charged to that account.
     */
    static final String KEYCLOAK_ATTEMPTED_USERNAME_NOTE = "ATTEMPTED_USERNAME";

    private static final String LOGIN_TEMPLATE = "imap-login.ftl";

    private static final int MAX_PASSWORD_LENGTH = 4096;

    private final ImapAuthenticationService service;

    public ImapAuthenticator() {
        this(new ImapAuthenticationService());
    }

    public ImapAuthenticator(ImapAuthenticationService authenticationService) {
        this.service = authenticationService;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        context.challenge(form(context).createForm(LOGIN_TEMPLATE));
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        // Brute force accounting uses the flow user, otherwise ATTEMPTED_USERNAME. Clear both before any branch can
        // fail, so only a managed user bound below via setUser can be charged for this submission.
        context.clearUser();
        context.getAuthenticationSession().removeAuthNote(KEYCLOAK_ATTEMPTED_USERNAME_NOTE);
        String stage = "KEYCLOAK_PROFILE_CHECK";
        LOGGER.debug("KEYCLOAK_AUTH_START");

        try {
            if (!protectedAttributes(context)) {
                reject(context);
                return;
            }

            stage = "IMAP_CONFIGURATION";
            ImapConfiguration configuration = ImapConfiguration.parse(
                    context.getAuthenticatorConfig() == null ? null : context.getAuthenticatorConfig().getConfig());

            stage = "IMAP_DOMAIN_CONFIGURATION";
            ImapEmailDomains allowedDomains = allowedDomains(context);

            stage = "IMAP_FORM_INPUT";
            MultivaluedMap<String, String> formParameters = context.getHttpRequest().getDecodedFormParameters();
            String email = ImapConfiguration.normalize(formParameters.getFirst("username"));
            String password = formParameters.getFirst("password");
            context.getEvent().detail("username", email);
            context.getAuthenticationSession().setAuthNote(ATTEMPTED_EMAIL_NOTE, email);
            if (password == null || password.isEmpty() || password.length() > MAX_PASSWORD_LENGTH) {
                LOGGER.warn("IMAP_FORM_INVALID reason=password_missing_or_too_long");
                reject(context);
                return;
            }

            // Reject untrusted domains before any user lookup, user binding or IMAP connection.
            stage = "IMAP_DOMAIN_CHECK";
            if (!allowedDomains.allows(email)) {
                LOGGER.warn("IMAP_DOMAIN_REJECTED reason=domain_not_allowed");
                reject(context);
                return;
            }

            UserProvider users = context.getSession().users();
            RealmModel realm = context.getRealm();
            if (realm.isDuplicateEmailsAllowed()) {
                LOGGER.warn("KEYCLOAK_USER_LOOKUP_FAILED reason=duplicate_emails_enabled");
                reject(context);
                return;
            }

            stage = "KEYCLOAK_USER_LOOKUP";
            LOGGER.debug(stage);
            UserModel user = users.getUserByEmail(realm, email);
            if (user != null) {
                if (!isUsableManagedUser(user, configuration, email)) {
                    LOGGER.warn("KEYCLOAK_USER_LOOKUP_FAILED reason=unmanaged_or_disabled_user");
                    reject(context);
                    return;
                }

                context.setUser(user);
                if (isLockedOut(context, realm, user)) {
                    context.getEvent().error("user_temporarily_disabled");
                    LOGGER.warn("KEYCLOAK_USER_LOOKUP_FAILED reason=brute_force_lock");
                    context.forceChallenge(loginForm(context));
                    return;
                }
            } else if (!configuration.autoCreate()) {
                // Without auto-creation an unknown address can never log in, so it is rejected before any IMAP
                // connection. The response is the same generic failure as a wrong password.
                LOGGER.warn("KEYCLOAK_USER_LOOKUP_FAILED reason=unknown_user_auto_create_disabled");
                reject(context);
                return;
            }

            stage = "IMAP_AUTHENTICATION";
            if (!service.authenticate(configuration, email, password)) {
                reject(context);
                return;
            }

            if (user == null) {
                if (!configuration.autoCreate() || users.getUserByUsername(realm, email) != null) {
                    LOGGER.warn("KEYCLOAK_USER_CREATE_FAILED reason=creation_disabled_or_username_conflict");
                    reject(context);
                    return;
                }

                stage = "KEYCLOAK_USER_CREATE";
                user = createManagedUser(context, configuration, users, realm, email);
                if (user == null) {
                    return;
                }
            }

            stage = "KEYCLOAK_AUTH_COMPLETE";
            if (!isUsableManagedUser(user, configuration, email)) {
                LOGGER.warn("KEYCLOAK_USER_LOOKUP_FAILED reason=post_authentication_identity_mismatch");
                context.getSession().getTransactionManager().setRollbackOnly();
                reject(context);
                return;
            }

            if (!user.isEmailVerified()) {
                user.setEmailVerified(true);
            }

            context.setUser(user);
            // Records a password credential like the native password form, so a successful login resets the brute
            // force failure count.
            context.success(PasswordCredentialModel.TYPE);
            LOGGER.debug("KEYCLOAK_AUTH_SUCCESS");
        } catch (Exception failure) {
            LOGGER.warnf("KEYCLOAK_AUTH_FAILED stage=%s exceptionType=%s", stage, failure.getClass().getName());
            context.getSession().getTransactionManager().setRollbackOnly();
            reject(context);
        }
    }

    /**
     * Creates a managed user after checking the realm defaults, then re-checks the permissions actually attached to the
     * new user. Returns {@code null} after issuing a failure challenge when either check fails.
     */
    private UserModel createManagedUser(AuthenticationFlowContext context, ImapConfiguration configuration,
            UserProvider users, RealmModel realm, String email) {
        ImapDefaultPermissions.Allowed allowed;
        try {
            allowed = ImapDefaultPermissions.Allowed.parse(context.getAuthenticatorConfig().getConfig());
            ImapDefaultPermissions.checkDefaults(realm, allowed);
        } catch (RuntimeException permissionFailure) {
            LOGGER.warnf("DEFAULT_PERMISSION_CHECK_FAILED stage=before_create exceptionType=%s",
                    permissionFailure.getClass().getName());
            reject(context);
            return null;
        }

        LOGGER.debug("KEYCLOAK_USER_CREATE");

        UserModel user;
        try {
            user = users.addUser(realm, email);
            user.setEmail(email);
            user.setSingleAttribute(BINDING, configuration.binding());
            user.setSingleAttribute(IDENTITY, email);
            user.setEnabled(true);
        } catch (RuntimeException createFailure) {
            LOGGER.warnf("KEYCLOAK_USER_CREATE_FAILED exceptionType=%s", createFailure.getClass().getName());
            throw createFailure;
        }

        try {
            ImapDefaultPermissions.checkCreatedUser(realm, user, allowed);
            return user;
        } catch (RuntimeException permissionFailure) {
            LOGGER.warnf("DEFAULT_PERMISSION_CHECK_FAILED stage=after_create exceptionType=%s",
                    permissionFailure.getClass().getName());
            context.getSession().getTransactionManager().setRollbackOnly();
            reject(context);
            return null;
        }
    }

    /**
     * Requires both binding attributes to be declared in the user profile as single-valued and editable by
     * administrators only.
     */
    static boolean protectedAttributes(AuthenticationFlowContext context) {
        UserProfileProvider provider = context.getSession().getProvider(UserProfileProvider.class);
        if (provider == null) {
            LOGGER.warn("KEYCLOAK_PROFILE_CHECK_FAILED reason=provider_missing");
            return false;
        }

        UPConfig profile = provider.getConfiguration();
        if (profile == null) {
            LOGGER.warn("KEYCLOAK_PROFILE_CHECK_FAILED reason=configuration_missing");
            return false;
        }

        for (String name : PROTECTED_ATTRIBUTES) {
            UPAttribute attribute = profile.getAttribute(name);
            if (attribute == null || attribute.isMultivalued() || attribute.getPermissions() == null) {
                LOGGER.warnf("KEYCLOAK_PROFILE_CHECK_FAILED reason=missing_or_invalid_attribute attribute=%s", name);
                return false;
            }

            Set<String> edit = attribute.getPermissions().getEdit();
            if (edit == null || !edit.equals(Set.of("admin"))) {
                LOGGER.warnf("KEYCLOAK_PROFILE_CHECK_FAILED reason=attribute_not_admin_only attribute=%s", name);
                return false;
            }
        }
        return true;
    }

    /**
     * Parses the domain allowlist. An invalid configuration is logged with a fixed reason and rethrown, so the caller's
     * generic failure handling (rollback and failure challenge) still applies.
     */
    private static ImapEmailDomains allowedDomains(AuthenticationFlowContext context) {
        try {
            return ImapEmailDomains.parse(
                    context.getAuthenticatorConfig() == null ? null : context.getAuthenticatorConfig().getConfig());
        } catch (ImapEmailDomains.InvalidConfigurationException invalidConfiguration) {
            LOGGER.warnf("IMAP_DOMAIN_CONFIG_INVALID reason=%s", invalidConfiguration.reason().logValue());
            throw invalidConfiguration;
        }
    }

    static boolean managed(UserModel user, ImapConfiguration configuration, String email) {
        return configuration.binding().equals(user.getFirstAttribute(BINDING))
                && email.equals(user.getFirstAttribute(IDENTITY));
    }

    private static boolean isUsableManagedUser(UserModel user, ImapConfiguration configuration, String email) {
        return managed(user, configuration, email) && user.isEnabled() && sameEmail(user, email);
    }

    private static boolean isLockedOut(AuthenticationFlowContext context, RealmModel realm, UserModel user) {
        return context.getProtector().isTemporarilyDisabled(context.getSession(), realm, user)
                || context.getProtector().isPermanentlyLockedOut(context.getSession(), realm, user);
    }

    private static boolean sameEmail(UserModel user, String email) {
        try {
            return email.equals(ImapConfiguration.normalize(user.getEmail()));
        } catch (IllegalArgumentException invalidStoredEmail) {
            return false;
        }
    }

    private Response loginForm(AuthenticationFlowContext context) {
        String email = context.getAuthenticationSession().getAuthNote(ATTEMPTED_EMAIL_NOTE);
        return form(context)
                .setAttribute("imapEmail", email == null ? "" : email)
                .setError("imapLoginFailed")
                .createForm(LOGIN_TEMPLATE);
    }

    /**
     * Hides the username summary rendered by the native layout so that the page contains a single input area.
     */
    private LoginFormsProvider form(AuthenticationFlowContext context) {
        return context.form().setAttributeMapper(attributes -> {
            attributes.put("auth", new AuthenticationContextBean(context, null) {
                @Override
                public boolean showUsername() {
                    return false;
                }
            });
            return attributes;
        });
    }

    private void reject(AuthenticationFlowContext context) {
        if (context.getUser() != null) {
            context.getEvent().user(context.getUser());
        }
        context.getEvent().error("invalid_user_credentials");
        context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, loginForm(context));
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

    @Override
    public void close() {
    }
}
