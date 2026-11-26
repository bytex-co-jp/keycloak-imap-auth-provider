#!/bin/sh
# Offline tests for scripts/audit-imap-local-password.sh. Fixtures are scrubbed Admin REST responses recorded from an
# isolated Keycloak 26.8.1 realm with synthetic users:
#   baseline - built-in browser/direct grant flows, client overrides, SAML client with ECP, managed users with passwords
#   partial  - realm flows guarded, client overrides and ECP still unguarded
#   strict   - realm flows and every override guarded, ECP off
#   idp-open    - identity provider with the built-in first broker login flow; managed user linked by e-mail verification
#   idp-guarded - same provider with e-mail verification disabled, re-authentication guarded and a post broker guard
# Requires jq. Usage: scripts/tests/test-audit-imap-local-password.sh

set -u
HERE=$(cd "$(dirname "$0")" && pwd)
AUDIT=$HERE/../audit-imap-local-password.sh
SRC=$HERE/fixtures/audit
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
pass=0
fail=0

# fixture <realm> <source scenario> : copy a scenario as realm <realm> into the per-test fixture root
fixture() {
    mkdir -p "$FIXTURES/$1"
    cp "$SRC/$2"/*.json "$FIXTURES/$1/"
}
# edit <realm> <fixture key> <jq program> : modify one fixture in place
edit() {
    jq -c "$3" "$FIXTURES/$1/$2.json" >"$work/edit.tmp" && mv "$work/edit.tmp" "$FIXTURES/$1/$2.json"
}
run() {
    FAKE_LOG=$work/calls KCADM="$HERE/fake-kcadm.sh" FIXTURES=$FIXTURES sh "$AUDIT" "$@" >"$work/out" 2>&1
    status=$?
}
start() {
    name=$1
    FIXTURES=$work/$name
    : >"$work/calls"
    mkdir -p "$FIXTURES"
}
check() { # check <description> <shell condition>
    if eval "$2"; then
        pass=$((pass + 1))
    else
        fail=$((fail + 1))
        echo "FAIL [$name] $1"
        sed 's/^/    | /' "$work/out" | head -60
    fi
}
count() { grep -c "^\[$1\]" "$work/out" || true; }
has() { grep -qE "$1" "$work/out"; }

USERS='get_users_-r_REALM_-q_briefRepresentation=false_-q_first=0_-q_max=100'
CLIENTS='get_clients_-r_REALM_-q_first=0_-q_max=100'
DG_STRICT='get_authentication~flows~direct-grant-imap-strict~executions_-r_REALM'
BR_STRICT='get_authentication~flows~browser-imap-strict~executions_-r_REALM'

start baseline
fixture legacy baseline
run legacy
check "exit 1 with findings" '[ "$status" = 1 ]'
check "realm browser flow unguarded" 'has "^\[HIGH\] realm=legacy realm-browser: .*auth-username-password-form"'
check "realm direct grant names admin-cli" 'has "^\[HIGH\] realm=legacy realm-direct-grant: .*admin-cli"'
check "client browser override" 'has "^\[HIGH\] .*client=brx browser override"'
check "client direct_grant override" 'has "^\[HIGH\] .*client=dgx Direct Access Grants with direct_grant override"'
check "SAML ECP explains it cannot be guarded" 'has "^\[HIGH\] .*client=samlecp allows SAML ECP; the built-in"'
check "reset password warning" 'has "^\[WARNING\] .*reset-credentials: Reset Password is enabled"'
check "managed users with local password are HIGH while entries are open" '[ "$(grep -c "^\[HIGH\] .*managed-user: .*has a Keycloak local password" "$work/out")" = 2 ]'
check "plain local user is not reported" '! has "plain.user"'
check "never claims safety" '! has "RESULT: no HIGH"'

start partial
fixture legacy partial
run legacy
check "exit 1" '[ "$status" = 1 ]'
check "realm browser guard verified" 'has "^\[INFO\] .*realm-browser: .*has a verified managed-user guard"'
check "realm direct grant guard verified" 'has "^\[INFO\] .*realm-direct-grant: .*has a verified managed-user guard"'
check "override that bypasses the realm guard is HIGH" 'has "^\[HIGH\] .*client=dgx" && has "^\[HIGH\] .*client=brx"'
check "ECP still HIGH" 'has "^\[HIGH\] .*client=samlecp"'
check "reset flow guard verified" 'has "^\[INFO\] .*reset-credentials: .*verified managed-user guard"'

start strict
fixture strict strict
run strict
check "no HIGH in strict realm" '[ "$(count HIGH)" = 0 ]'
check "existing local passwords stay WARNING" '[ "$(grep -c "^\[WARNING\] .*has a Keycloak local password" "$work/out")" = 2 ]'
check "exit 1 because of warnings" '[ "$status" = 1 ]'
check "admin-cli covered by the guarded realm flow" 'has "^\[INFO\] .*client=admin-cli .*Direct Access Grants enabled"'
check "coverage section printed" 'has "^== Coverage" && has "Exit status 0 is not a proof"'
check "read-only: only GET requests" '[ "$(grep -cv "^get " "$work/calls")" = 0 ]'

start strict-clean
fixture strict strict
for f in "$FIXTURES"/strict/get_users~*~credentials_-r_REALM.json; do echo '[]' >"$f"; done
run strict
check "exit 0 when only INFO" '[ "$status" = 0 ]'
check "clean result is scoped, not 'safe'" 'has "RESULT: no HIGH, WARNING or REVIEW findings within the audit scope" && ! grep -qi "is safe" "$work/out"'

start multi-realm
fixture strict strict
fixture legacy baseline
run strict legacy
check "both realms audited" 'has "realm=strict " && has "realm=legacy "'
check "HIGH only in the legacy realm" '! grep -q "^\[HIGH\] realm=strict " "$work/out" && grep -q "^\[HIGH\] realm=legacy " "$work/out"'
check "summary after all realms" '[ "$(grep -c "^== Summary" "$work/out")" = 1 ]'

start incomplete
fixture strict strict
rm "$(ls "$FIXTURES"/strict/get_users~*~credentials_-r_REALM.json | head -1)"
run strict
check "exit 2 when a request fails" '[ "$status" = 2 ]'
check "error line and INCOMPLETE result" 'has "^\[ERROR\] .*cannot read credential types" && has "RESULT: INCOMPLETE"'
check "incomplete run never reports clean" '! has "RESULT: no HIGH"'

start unknown-realm
fixture strict strict
run strict nosuchrealm
check "missing realm is an error" '[ "$status" = 2 ] && has "^\[ERROR\] realm=nosuchrealm realm: cannot read the realm"'

start guard-inverted
fixture strict strict
cfg=$(jq -r '.[] | select(.providerId == "conditional-user-attribute") | .authenticationConfig' "$FIXTURES/strict/$DG_STRICT.json")
edit strict "get_authentication~config~${cfg}_-r_REALM" '.config.not = "true"'
run strict
check "inverted condition is not accepted as a guard" 'has "^\[REVIEW\] .*realm-direct-grant: .*guard-like sub-flow is not a verified sibling guard"'
check "and is not reported as verified" '! has "^\[INFO\] .*realm-direct-grant: .*verified managed-user guard"'

start guard-wrong-attribute
fixture strict strict
cfg=$(jq -r '.[] | select(.providerId == "conditional-user-attribute") | .authenticationConfig' "$FIXTURES/strict/$BR_STRICT.json")
edit strict "get_authentication~config~${cfg}_-r_REALM" '.config.attribute_name = "department"'
run strict
check "condition on another attribute is REVIEW" 'has "^\[REVIEW\] .*realm-browser: "'

start guard-before-user
fixture strict strict
# Move the guard sub-flow (and its two children) in front of "Username Validation".
edit strict "$DG_STRICT" '(map(.displayName) | index("imap-managed-no-local-password (direct grant)")) as $g | .[$g:$g+3] + .[0:$g] + .[$g+3:]'
run strict
check "guard before the user is identified is REVIEW" 'has "^\[REVIEW\] .*realm-direct-grant: .*guard runs before the user is identified"'

start guard-disabled
fixture strict strict
edit strict "$DG_STRICT" 'map(if .displayName == "imap-managed-no-local-password (direct grant)" then .requirement = "DISABLED" else . end)'
run strict
check "disabled guard does not count" 'has "^\[(HIGH|REVIEW)\] .*realm-direct-grant: "'
check "realm direct grant not reported as verified" '! has "^\[INFO\] .*realm-direct-grant: .*verified"'

start password-disabled
fixture strict strict
edit strict "$DG_STRICT" 'map(if .providerId == "direct-grant-validate-password" then .requirement = "DISABLED" else . end)'
run strict
check "a DISABLED password step is not an entry" 'has "^\[INFO\] .*realm-direct-grant: .*no local-password step found"'

start unknown-authenticator
fixture strict strict
edit strict "$BR_STRICT" '. + [{"displayName": "Vendor login", "providerId": "vendor-password-login", "requirement": "ALTERNATIVE", "level": 0, "index": 99, "authenticationFlow": false}]'
run strict
check "third-party authenticator is REVIEW" 'has "^\[REVIEW\] .*unrecognised authenticators \(vendor-password-login\)"'

start federation-and-idp
fixture strict strict
edit strict "get_components_-r_REALM_-q_type=org.keycloak.storage.UserStorageProvider" '[{"id": "ldap-1", "name": "corp-ldap", "providerId": "ldap"}]'
edit strict "get_identity-provider~instances_-r_REALM_-q_first=0_-q_max=100" '[{"alias": "corp-oidc", "enabled": true}]'
run strict
check "user storage provider is REVIEW" 'has "^\[REVIEW\] .*user-federation: user storage provider corp-ldap"'
check "IdP re-authentication with local password is HIGH" 'has "^\[HIGH\] .*idp: identity provider=corp-oidc first broker login .*idp-username-password-form"'

start new-override-bypass
fixture strict strict
dg=$(jq -r '.[] | select(.alias == "direct grant") | .id' "$FIXTURES/strict/get_authentication~flows_-r_REALM.json")
edit strict "$CLIENTS" --arg dg "$dg" '. + [{"id": "c-new", "clientId": "new-app", "enabled": true, "protocol": "openid-connect", "directAccessGrantsEnabled": true, "authenticationFlowBindingOverrides": {"direct_grant": $dg}}]' 2>/dev/null \
    || edit strict "$CLIENTS" ". + [{\"id\": \"c-new\", \"clientId\": \"new-app\", \"enabled\": true, \"protocol\": \"openid-connect\", \"directAccessGrantsEnabled\": true, \"authenticationFlowBindingOverrides\": {\"direct_grant\": \"$dg\"}}]"
run strict
check "a client added later with an unguarded override is HIGH" 'has "^\[HIGH\] .*client=new-app Direct Access Grants with direct_grant override flow=\"direct grant\""'

start no-secrets
fixture strict strict
edit strict "$CLIENTS" '. + [{"id": "c-conf", "clientId": "confidential-app", "enabled": true, "protocol": "openid-connect", "publicClient": false, "secret": "FIXTURE-SECRET-1", "attributes": {"client.secret.rotated": "FIXTURE-SECRET-2"}}]'
for f in "$FIXTURES"/strict/get_users~*~credentials_-r_REALM.json; do
    jq -c 'map(. + {"secretData": "FIXTURE-SECRET-3", "credentialData": "FIXTURE-SECRET-4"})' "$f" >"$f.tmp" && mv "$f.tmp" "$f"
done
run strict
check "no secret or credential data in the output" '! grep -q "FIXTURE-SECRET" "$work/out"'

start non-imap-realm
fixture plain baseline
edit plain "$USERS" 'map(.attributes = {})'
edit plain "get_authentication~flows~imap-only~executions_-r_REALM" 'map(select(.providerId != "imap-authenticator"))'
run plain
check "realm without IMAP flows or managed users has no actionable findings" '[ "$(count HIGH)" = 0 ] && [ "$(count WARNING)" = 0 ] && [ "$(count REVIEW)" = 0 ]'
check "entries are reported as not applicable" 'has "^\[INFO\] realm=plain scope: no flow contains the IMAP authenticator" && has "not applicable in this realm"'
check "exit 0" '[ "$status" = 0 ]'

start flow-read-failure
fixture strict strict
rm "$FIXTURES/strict/get_authentication~flows~imap-only~executions_-r_REALM.json"
run strict
check "an unreadable flow makes the run incomplete" '[ "$status" = 2 ] && has "^\[ERROR\] realm=strict flows: cannot read flow \"imap-only\""'

start idp-link
fixture strict strict
f=$(ls "$FIXTURES"/strict/get_users*federated-identity_-r_REALM.json | head -1)
echo '[{"identityProvider": "corp-oidc", "userId": "x", "userName": "x"}]' >"$f"
run strict
check "managed user linked to an identity provider is WARNING" 'has "^\[WARNING\] .*is linked to identity provider\(s\) corp-oidc"'

IDP_FBL='get_authentication~flows~first%20broker%20login~executions_-r_REALM'
IDP_POST='get_authentication~flows~post-broker-imap-guard~executions_-r_REALM'

start idp-open
fixture idp idp-open
run idp
check "re-authentication with the local password is HIGH" 'has "^\[HIGH\] realm=idp idp: identity provider=corp first broker login flow=\"first broker login\": idp-username-password-form"'
check "e-mail verification linking is WARNING" 'has "^\[WARNING\] realm=idp idp: .*Verify existing account by Email is enabled"'
check "existing link of a managed user is WARNING" 'has "^\[WARNING\] realm=idp managed-user: .*is linked to identity provider\(s\) corp and can sign in there without IMAP"'
check "exit 1" '[ "$status" = 1 ]'

start idp-auto-link
fixture idp idp-open
edit idp "$IDP_FBL" 'map(if .providerId == "idp-email-verification" then .providerId = "idp-auto-link" | .displayName = "Automatically set existing user" else . end)'
run idp
check "automatic linking is HIGH" 'has "^\[HIGH\] realm=idp idp: .*automatic linking \(idp-auto-link\)"'

start idp-email-disabled
fixture idp idp-open
edit idp "$IDP_FBL" 'map(if .providerId == "idp-email-verification" then .requirement = "DISABLED" else . end)'
run idp
check "disabled e-mail verification is not reported" '! has "Verify existing account by Email is enabled"'
check "the existing link is still reported" 'has "^\[WARNING\] realm=idp managed-user: .*is linked to identity provider\(s\) corp"'

start idp-guarded
fixture idp idp-guarded
run idp
check "post broker guard recognised" 'has "^\[INFO\] realm=idp idp: identity provider=corp post broker login flow=\"post-broker-imap-guard\" denies IMAP-managed users"'
check "guarded re-authentication is INFO" 'has "^\[INFO\] realm=idp idp: .*fbl-noemail-f1.*has a verified managed-user guard"'
check "existing link is INFO under the post broker guard" 'has "^\[INFO\] realm=idp managed-user: .*logins are denied by post broker guards"'
check "no IdP finding above INFO" '! grep -qE "^\[(HIGH|WARNING|REVIEW)\] realm=idp (idp|managed-user):" "$work/out"'

start idp-guard-without-allow
fixture idp idp-guarded
edit idp "$IDP_POST" 'map(select(.providerId != "allow-access-authenticator"))'
run idp
check "post broker guard without Allow access is REVIEW" 'has "^\[REVIEW\] realm=idp idp: .*no REQUIRED Allow access"'
check "and the link is not treated as guarded" 'has "^\[WARNING\] realm=idp managed-user: .*is linked to identity provider\(s\) corp and can sign in there without IMAP"'

start idp-post-broker-unreadable
fixture idp idp-guarded
rm "$FIXTURES/idp/$IDP_POST.json"
run idp
check "unreadable post broker flow makes the run incomplete" '[ "$status" = 2 ] && has "^\[ERROR\] realm=idp" && ! has "RESULT: no HIGH"'

echo "audit tests: $pass passed, $fail failed"
[ "$fail" = 0 ]
