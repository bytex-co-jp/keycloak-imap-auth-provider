# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-11-27

Initial open-source release, licensed under the Apache License 2.0. Built and tested for Keycloak 26.8.1, with Java 21
bytecode.

Earlier internal builds were never published and are not part of this version history.
Deployments upgrading from such a build must read the "Compatibility and upgrade considerations" section below.

### Features

- **IMAP authentication.** A Keycloak authenticator (provider ID `imap-authenticator`) that verifies the full e-mail
  address and mailbox password against an IMAP server over IMAPS or mandatory STARTTLS, with configurable connect and
  read timeouts.
- **TLS enforcement and certificate validation.** Certificate chain and host name verification, TLSv1.2 and TLSv1.3
  only, and no fallback to plaintext.
- **E-mail domain allowlist.** The required `allowedEmailDomains` setting lists exact domains; other addresses are
  rejected before any user lookup or IMAP connection, and a missing or invalid list rejects every login.
- **Automatic user provisioning.** Optional and disabled by default (`autoCreate=false`). The realm's default roles and
  groups are checked before and after a user is created; only built-in ordinary roles and explicitly approved role and
  group IDs are accepted, and administrative roles are always rejected.
- **Managed identity binding and protection.** Users are bound through the `imap.auth.binding` and
  `imap.auth.identity` user profile attributes, which must be single-valued and editable by administrators only. A
  local account that merely shares the e-mail address is never taken over, and disabled users are rejected.
- **Native brute force protection.** The authenticator uses Keycloak's `password` category, so Brute Force Detection
  counts failures of managed users, locks them out, and resets the count after a successful login.
- **Early rejection of unknown users.** With `autoCreate=false`, addresses unknown to Keycloak are rejected before any
  IMAP connection.
- **Fail-closed behavior.** Every error leads to the same generic login failure; mailbox passwords are never logged or
  stored.
- **Login form** based on the native Keycloak login theme, with English and Japanese messages.
- **Audit scripts.** Read-only scripts that check a realm for local-password entry points of managed users
  (`scripts/audit-imap-local-password.sh`) and for flows affected by the `password` credential category
  (`scripts/check-imap-credential-conditions.sh`).

### Documentation

- English and Chinese README, plus Chinese guides for architecture, installation, configuration, security, deployment,
  operations and troubleshooting.
- Example Dockerfiles for development and for a PostgreSQL deployment, and an example user profile configuration.
- Security policy with a private vulnerability reporting process.

### Compatibility and upgrade considerations

- Tested only with Keycloak 26.8.1. The plugin depends on Keycloak private SPIs that may change between releases.
- The JAR contains only the plugin's own classes and resources; Jakarta Mail and Angus Mail are provided by Keycloak.
- After adding the JAR, run `kc.sh build` again (or rebuild the image) when Keycloak is started with `--optimized`.
- Upgrading from an internal build:
  - **Breaking:** `allowedEmailDomains` is required. Executions without it reject every IMAP login. The value can be
    written while the old build is still running, because the old build ignores it.
  - **Breaking:** successful IMAP logins are recorded as a `password` credential. Flows that use
    "Condition - credential" (for example `credentials=none` to require OTP) behave differently and must be checked
    before upgrading. Realms must enable Brute Force Detection for failures to be counted.
  - With `autoCreate=false`, unknown addresses are rejected before IMAP; the log event changes from
    `KEYCLOAK_USER_CREATE_FAILED` to `KEYCLOAK_USER_LOOKUP_FAILED reason=unknown_user_auto_create_disabled`.
  - Rolling back to an internal build removes the domain restriction, because those builds ignore
    `allowedEmailDomains`.

  See `docs/deployment.md` for the upgrade procedures.

### Testing and validation

- Automated tests: unit tests with mocks, and integration tests against a GreenMail IMAP server on the loopback
  interface covering SSL and STARTTLS, certificate and host name rejection, no plaintext fallback, and the cases that
  must not open an IMAP connection. Shell tests cover the audit script. CI runs formatting checks, the tests and the
  build with JDK 21.
- Validated in a production Keycloak deployment operated by ByteX Inc. That deployment was upgraded with a CI build
  of the same Java sources; the release JAR is built by CI from the release commit and is not claimed to be
  byte-identical to the build used in production.

### Known issues

- Some IMAP servers answer a wrong password in a way that the mail library reports as a generic error. The login is
  rejected as expected, but the log shows `IMAP_CONNECTION_FAILED` instead of `IMAP_AUTH_REJECTED`.
- The plugin has no rate limiting of its own and no brute force protection for unknown addresses; these belong to the
  mail server and the reverse proxy.
