# Keycloak IMAP Authentication Provider

English | [简体中文](README.zh-CN.md)

A lightweight Keycloak Authentication SPI plugin. Users sign in with their full e-mail address and mailbox password; the
plugin verifies the password against your IMAP server over verified TLS and maps the result to a Keycloak user. Keycloak
keeps handling identities, roles, groups, SSO and OIDC for your applications.

Version **1.0.0**, built and tested for **Keycloak 26.8.1**. Developed and maintained by ByteX Inc.

## What the plugin does, and what it does not

The plugin is responsible for:

- verifying the mailbox password over IMAPS or mandatory STARTTLS, with certificate chain and host name verification and
  no plaintext fallback;
- creating and binding users safely: a local account that merely shares the e-mail address is never taken over, and the
  binding attributes are writable by administrators only;
- enforcing an exact e-mail domain allowlist before any user lookup or IMAP connection;
- integrating with Keycloak's native Brute Force Detection for managed users;
- failing closed on every error, and never logging or storing the mailbox password.

It is **not** a complete identity protection system. In particular, it has **no rate limiting of its own** and offers
**no brute force protection for unknown addresses**. Mail systems usually expose the same password through Webmail,
IMAP and SMTP AUTH, so password-guessing protection for mailboxes belongs to the mail server, and HTTP flood protection
belongs to your reverse proxy or WAF. See [Responsibilities](#responsibilities).

## Requirements

| Item | Version |
| --- | --- |
| Keycloak | 26.8.1 (other versions are untested; the plugin uses Keycloak private SPIs) |
| Java | bytecode level 21; build with JDK 21 or later |
| Build | Gradle Wrapper 9.8.2 (`./gradlew`) |
| IMAP server | password authentication over IMAPS or STARTTLS |

The JAR contains only the plugin's own classes and resources. Jakarta Mail / Angus Mail are provided by the Keycloak
runtime.

## Build

```sh
./gradlew clean test build
```

The plugin JAR is written to `build/libs/keycloak-imap-auth-provider-1.0.0.jar`. A development image based on the
official Keycloak image can be built with:

```sh
docker build -t imap-keycloak:1.0.0 .
```

## Installation outline

Try it in an isolated test realm first. Do not change the master realm's authentication flows.

1. Copy the JAR to `/opt/keycloak/providers/` and run `kc.sh build` (or use the Dockerfile above).
2. In the realm, disable **Duplicate Emails**.
3. Add two user profile attributes, `imap.auth.binding` and `imap.auth.identity`: single-valued, not required, not
   viewable or editable by users, viewable and editable by administrators. Merge them into the existing user profile;
   do not overwrite it ([example](config/user-profile-imap-attributes.json)).
4. Enable **Brute Force Detection** for the realm. Without it, failures are neither counted nor locked.
5. In **Authentication > Flows**, duplicate the built-in browser flow (keep the original). In the copy, add
   **IMAP Authentication** as REQUIRED, remove the local **Username Password Form**, and remove or disable other
   branches that can complete the flow (cookie, identity provider redirector) if every login must go through IMAP.
6. Configure the execution (see [Configuration](#configuration)); `host` and `allowedEmailDomains` are required.
7. Bind the flow to a test client (Authentication Flow Overrides) or to the test realm, and verify both successful and
   failed logins in a fresh browser session.

If managed users must only ever sign in through IMAP ("strict" mode), the other local-password entry points (direct
grant, password reset, identity provider re-authentication, client flow overrides, SAML ECP) need to be guarded as
well. The read-only audit script `scripts/audit-imap-local-password.sh` checks a realm for such gaps.

Detailed steps (in Chinese): [installation](docs/installation.md), [deployment](docs/deployment.md).

## Configuration

All settings are stored per authentication execution. Provider ID: `imap-authenticator`.

| Setting | Key | Default |
| --- | --- | --- |
| IMAP Host | `host` | required |
| IMAP Port | `port` | `993` |
| TLS Mode | `tls` | `SSL` (`SSL` or `STARTTLS`) |
| Connection Timeout | `connectTimeout` | `5000` ms (100–60000) |
| Read Timeout | `readTimeout` | `5000` ms (100–60000) |
| Auto Create User | `autoCreate` | `false` |
| Login Identifier | `identifier` | `Email` (only value) |
| Allowed Default Roles | `allowedDefaultRoles` | empty |
| Allowed Default Groups | `allowedDefaultGroups` | empty |
| Allowed Email Domains | `allowedEmailDomains` | **required**, comma-separated exact domains |

With STARTTLS, set the port explicitly (usually 143). The role and group allowlists use stable IDs and only approve
default permissions inherited by auto-created users; they never grant anything. Reference (in Chinese):
[configuration](docs/configuration.md).

## Security model

- A user is accepted only if its binding attributes match the configured IMAP endpoint and address, it is enabled, and
  its stored e-mail matches. Otherwise the login fails with a generic error.
- With `autoCreate=false` (the default and recommended setting), addresses unknown to Keycloak are rejected before any
  IMAP connection.
- With `autoCreate=true`, every submission for an unknown address is forwarded to the IMAP server. Before and after
  creation, the realm's default roles and groups are checked against the configured allowlists; administrative roles
  are always rejected.
- A successful IMAP login is recorded as a `password` credential, so native Brute Force Detection counts failures and
  resets them on success. Before upgrading flows that use **Condition - credential**, read the
  [upgrade notes](docs/deployment.md).
- Response times differ between failure branches, which can reveal whether an address belongs to a managed user.

Full design (in Chinese): [security](docs/security.md).

## Responsibilities

| Party | Responsible for |
| --- | --- |
| This plugin | correct IMAP verification, safe user creation and binding, the domain allowlist, native brute force integration, generic failures, no secret leakage |
| Keycloak | authentication flows, users, Brute Force Detection, sessions and tokens |
| Mail server | password-guessing protection, account lockout and authentication failure policy for mailboxes |
| Reverse proxy / WAF | rate limiting of login requests, HTTPS, overwriting `X-Forwarded-For` |
| Administrator | trusted proxy settings, flow configuration, network access, mail server security |

Deployment notes:

- **Mail server lockout and IP bans.** Keep the mail server's own authentication failure protection enabled. Wrong
  passwords submitted through Keycloak can lock mailbox accounts, and if the mail server bans source IPs after repeated
  failures (for example with fail2ban), all Keycloak traffic shares one egress IP, so a ban can break IMAP logins for
  everyone. Review that policy for the Keycloak IP: excluding it from IP bans removes that protection for the Keycloak
  path, so only do so together with per-account lockout on the mail server and rate limiting in front of Keycloak.
- **Reverse proxy.** Keycloak uses the left-most `X-Forwarded-For` value. Configure the proxy to overwrite the header
  (`proxy_set_header X-Forwarded-For $remote_addr;`) rather than append to it, and set `proxy-trusted-addresses` in
  Keycloak. A minimal Nginx rate limit for the login endpoint is shown in [deployment](docs/deployment.md).

## Known limitations

- Password authentication with the full e-mail address only; no IMAP OAuth.
- Addresses are trimmed and lower-cased; internationalized addresses are not supported.
- No rate limiting or concurrency limit inside the plugin; IMAP calls are synchronous and bounded by the timeouts.
- If IMAP is unavailable, new IMAP logins fail; there is no fallback to local passwords.
- Existing SSO sessions and issued tokens are not revoked when a mailbox password changes or a mailbox is disabled.
- Tested only with Keycloak 26.8.1; the plugin depends on private SPIs that may change between Keycloak releases.

## Testing

```sh
./gradlew clean test build
sh scripts/tests/test-audit-imap-local-password.sh   # requires jq
```

Unit tests use mocks. Integration tests start GreenMail on the loopback interface with a certificate generated at test
time and cover SSL and STARTTLS success, certificate and host name rejection, no plaintext fallback, and the cases that
must not open any IMAP connection.

## Documentation

Detailed documentation is currently in Chinese: [architecture](docs/architecture.md),
[installation](docs/installation.md), [configuration](docs/configuration.md), [security](docs/security.md),
[deployment](docs/deployment.md), [operations](docs/operations.md), [troubleshooting](docs/troubleshooting.md).
Changes are listed in the [CHANGELOG](CHANGELOG.md).

## Reporting security issues

Please do not open public issues for vulnerabilities. See [SECURITY.md](SECURITY.md).

## License

Copyright 2026 ByteX Inc. Licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE).
