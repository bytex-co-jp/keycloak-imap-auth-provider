# Security Policy

## Supported versions

| Version | Supported |
| --- | --- |
| 1.0.x | Yes |

Only the latest 1.0.x release receives security fixes. Each release is tested with the Keycloak version stated in the
README (currently 26.8.1).

## Reporting a vulnerability

Please **do not** open a public issue, discussion or pull request for a suspected vulnerability.

Report it privately through GitHub's Private Vulnerability Reporting: open the repository's **Security** tab and choose
**Report a vulnerability**. If that option is not available yet, contact the maintainers through the repository owner's
GitHub profile and ask for a private channel, without including any details of the vulnerability.

Include:

- the affected version and Keycloak version;
- the configuration involved (authentication flow, `autoCreate`, TLS mode), without real passwords or tokens;
- steps to reproduce and the impact you observed.

Never include real credentials, mailbox contents or personal data in a report. Use synthetic accounts.

We aim to acknowledge vulnerability reports within 7 business days. This is a best-effort goal, not a commercial service
level agreement, and it is not a commitment to deliver a fix within that time. We will coordinate a fix and a disclosure
date with you, and credit you in the release notes unless you prefer otherwise.

## Scope

In scope are flaws in the plugin itself, for example authentication bypass, wrong identity binding, privilege
escalation through user creation, TLS verification problems, or leaking passwords or tokens.

Out of scope, because they are the responsibility of other components (see the README):

- password-guessing protection and account lockout for mailboxes (mail server);
- rate limiting of login requests and HTTP floods (reverse proxy / WAF);
- vulnerabilities in Keycloak itself, which should be reported to the Keycloak project.
