#!/bin/sh
# Read-only audit: can IMAP-managed users authenticate with a Keycloak local password?
#
# IMAP-managed users (users with the imap.auth.binding attribute) do not need a Keycloak local password, but Keycloak
# does not stop them from getting one (UPDATE_PASSWORD, reset credentials, administrators). A local password keeps
# working on every entry point that validates Keycloak passwords, even after the mailbox is disabled. The strict
# IMAP-managed mode therefore guards those entry points with a flow guard (see docs/installation.md). This script
# reports where that guard is missing, which managed users already hold a local password, and what needs review.
#
# The script only sends GET requests through kcadm.sh. It never modifies users, credentials, flows or clients and
# never prints passwords, tokens, secrets or credential data. Output contains user IDs and usernames.
#
# Usage:
#   kcadm.sh config credentials --server https://keycloak.example.com --realm master --user <admin>
#   scripts/audit-imap-local-password.sh <realm> [<realm>...]
#
# Environment:
#   KCADM  kcadm command, split on spaces (default: kcadm.sh),
#          e.g. KCADM="docker exec -i keycloak /opt/keycloak/bin/kcadm.sh"
#
# Levels: HIGH (a managed user can authenticate with a local password now), WARNING (precondition or latent entry),
# REVIEW (cannot be decided statically), INFO (context or a verified guard).
# Exit status: 0 = only INFO findings, 1 = HIGH/WARNING/REVIEW findings, 2 = a request failed (results incomplete).
# Exit status 0 is not a proof of safety; see the coverage section printed at the end.

set -u
umask 077

KCADM=${KCADM:-kcadm.sh}
BINDING_ATTRIBUTE=imap.auth.binding
PAGE=100

kc() {
    # shellcheck disable=SC2086 # KCADM is a command line by design.
    $KCADM "$@" </dev/null
}

uri() {
    printf '%s' "$1" | jq -sRr @uri
}

if [ $# -eq 0 ]; then
    sed -n '2,28p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
fi
command -v jq >/dev/null 2>&1 || { echo "ERROR jq is required" >&2; exit 2; }

work=$(mktemp -d) || { echo "ERROR cannot create a temporary directory" >&2; exit 2; }
trap 'rm -rf "$work"' EXIT
findings=$work/findings
: >"$findings"

# report <LEVEL> <realm> <category> <message>
report() {
    printf '[%s] realm=%s %s: %s\n' "$1" "$2" "$3" "$4" | tee -a "$findings"
}

error() {
    report ERROR "$1" "$2" "$3"
}

# get_paged <realm> <path> <output-file> [query...] : fetch every page of a list endpoint into one JSON array.
get_paged() {
    realm=$1 path=$2 output=$3
    shift 3
    first=0
    previous=
    echo '[]' >"$output"
    while :; do
        page=$(kc get "$path" -r "$realm" "$@" -q first=$first -q max=$PAGE) || return 1
        printf '%s' "$page" | jq -e 'type == "array"' >/dev/null 2>&1 || return 1
        head=$(printf '%s' "$page" | jq -c '.[0] // empty | (.id // .alias // .)')
        # A server that ignores first/max would repeat the same page forever.
        [ -n "$head" ] && [ "$head" = "$previous" ] && return 1
        previous=$head
        jq -s '.[0] + .[1]' "$output" - >"$output.tmp" <<EOF || return 1
$page
EOF
        mv "$output.tmp" "$output"
        count=$(printf '%s' "$page" | jq length)
        [ "$count" -lt "$PAGE" ] && return 0
        first=$((first + PAGE))
    done
}

# Analysis of one flattened execution list (GET authentication/flows/{alias}/executions).
# Input: executions; $cfg: authenticationConfig id -> config map.
# Output: {steps: [password-validating steps with a guard status], unknown: [unrecognised provider IDs], imap: bool}
ANALYZE='
def pw: ["auth-username-password-form", "auth-password-form", "direct-grant-validate-password",
         "idp-username-password-form", "http-basic-authenticator", "docker-http-basic-authenticator"];
def identity: pw + ["auth-username-form", "direct-grant-validate-username", "reset-credentials-choose-user",
         "idp-create-user-if-unique", "idp-detect-existing-broker-user", "auth-x509-client-username-form",
         "direct-grant-auth-x509-username"];
def known: identity + ["auth-cookie", "auth-spnego", "identity-provider-redirector", "organization",
         "auth-otp-form", "auth-conditional-otp-form", "webauthn-authenticator", "webauthn-authenticator-passwordless",
         "auth-recovery-authn-code-form", "conditional-user-configured", "conditional-user-role",
         "conditional-user-attribute", "conditional-credential", "conditional-level-of-authentication",
         "conditional-sub-flow-executed", "conditional-client-scope", "deny-access-authenticator",
         "allow-access-authenticator", "direct-grant-validate-otp", "reset-credential-email", "reset-password",
         "reset-otp", "idp-review-profile", "idp-confirm-link", "idp-email-verification", "idp-auto-link",
         "idp-confirm-override-link", "idp-add-organization-member", "user-session-limits", "imap-authenticator",
         "registration-page-form", "registration-user-creation", "registration-password-action",
         "registration-recaptcha-action", "registration-recaptcha-enterprise", "registration-terms-and-conditions",
         "client-secret", "client-jwt", "client-secret-jwt", "client-x509",
         "basic-flow", "form-flow", "client-flow"];
. as $e
| def parent($i): [range(0; $i) | select($e[.].level == $e[$i].level - 1)] | last;
  def off($i): $e[$i].requirement == "DISABLED" or (parent($i) as $p | $p != null and off($p));
  def scope($i; $lvl): [range($i + 1; $e | length)]
      | reduce .[] as $j ({stop: false, out: []};
          if .stop or $e[$j].level < $lvl then .stop = true
          elif $e[$j].level == $lvl then .out += [$j] else . end) | .out;
  def siblings($i): parent($i) as $p
      | if $p == null then [range(0; $e | length) | select($e[.].level == 0)] else scope($p; $e[$i].level) end;
  def children($i): scope($i; $e[$i].level + 1);
  def matcher($c): ($cfg[$c.authenticationConfig // ""] // {}) as $k
      | $k.attribute_name == "imap.auth.binding" and ($k.not // "false") != "true"
        and ($k.regex // "false") == "true" and (($k.attribute_expected_value // "") | IN(".+", ".*", "^.+$", "^.*$"));
  def guard($i): $e[$i].authenticationFlow == true and $e[$i].requirement == "CONDITIONAL"
      and (children($i) as $c
        | ($c | any($e[.] | .providerId == "conditional-user-attribute" and .requirement == "REQUIRED" and matcher(.)))
          and ($c | any($e[.] | .providerId == "deny-access-authenticator" and .requirement == "REQUIRED"))
          and ($c | all($e[.].requirement != "ALTERNATIVE")));
  def guardlike($i): $e[$i].authenticationFlow == true
      and (children($i) | any($e[.].providerId == "deny-access-authenticator" or $e[.].providerId == "conditional-user-attribute"));
{
  imap: any($e[]; .providerId == "imap-authenticator"),
  guard_any: any(range(0; $e | length); . as $x | guard($x) and (off($x) | not)),
  allow_top: any(range(0; $e | length); $e[.].level == 0 and $e[.].providerId == "allow-access-authenticator"
      and $e[.].requirement == "REQUIRED"),
  email_link: any(range(0; $e | length); $e[.].providerId == "idp-email-verification" and (off(.) | not)),
  auto_link: any(range(0; $e | length); $e[.].providerId == "idp-auto-link" and (off(.) | not)),
  guard_after_identity: any(range(0; $e | length); . as $x | guard($x) and (off($x) | not)
      and any(range(0; $x); ($e[.].providerId // "") | IN(identity[]))),
  unknown: [$e[] | .providerId // empty | select(IN(known[]) | not)] | unique,
  steps: [range(0; $e | length) as $i | select(($e[$i].providerId // "") | IN(pw[])) | select(off($i) | not)
    | siblings($i) as $s | ($s | map(select(guard(.)))) as $g
    | {provider: $e[$i].providerId, name: $e[$i].displayName, requirement: $e[$i].requirement,
       status: (
         if ($g | length) > 0 and $e[$i].requirement != "REQUIRED" then "review:password step is not REQUIRED next to a guard"
         elif ($g | any(. > $i)) then "guarded"
         elif ($g | any(. as $x | $s | any(. < $x and (($e[.].providerId // "") | IN(identity[]))))) then "guarded"
         elif ($g | length) > 0 then "review:guard runs before the user is identified"
         elif any(range(0; $e | length); guardlike(.)) then "review:guard-like sub-flow is not a verified sibling guard"
         else "unguarded" end)}]
}'

for realm in "$@"; do
    rdir=$work/$(printf '%s' "$realm" | jq -sRr @uri)
    mkdir -p "$rdir"
    if ! kc get "realms/$(uri "$realm")" >"$rdir/realm.json" 2>/dev/null; then
        error "$realm" realm "cannot read the realm"
        continue
    fi
    if ! kc get authentication/flows -r "$realm" >"$rdir/flows.json" 2>/dev/null; then
        error "$realm" flows "cannot list authentication flows"
        continue
    fi
    if ! get_paged "$realm" clients "$rdir/clients.json"; then
        error "$realm" clients "cannot list clients"
        continue
    fi
    if ! get_paged "$realm" identity-provider/instances "$rdir/idps.json"; then
        error "$realm" identity-providers "cannot list identity providers"
        echo '[]' >"$rdir/idps.json"
    fi
    # Reduce client representations to the fields used below; secrets are never kept.
    jq '[.[] | {id, clientId, enabled, protocol: (.protocol // "openid-connect"), bearerOnly, publicClient,
                directAccessGrantsEnabled, standardFlowEnabled, implicitFlowEnabled,
                overrides: (.authenticationFlowBindingOverrides // {}),
                ecp: ((.attributes // {})["saml.allow.ecp.flow"] // "false")}]' "$rdir/clients.json" >"$rdir/c.json" \
        && mv "$rdir/c.json" "$rdir/clients.json"

    # Flow alias for a flow id, and the analysis of every flow that an entry point can reach.
    alias_of() {
        jq -r --arg id "$1" '.[] | select(.id == $id) | .alias' "$rdir/flows.json"
    }
    analyze() { # analyze <alias> -> prints the path of the analysis JSON, or fails
        a=$1
        f=$rdir/flow-$(printf '%s' "$a" | jq -sRr @uri).json
        if [ ! -f "$f" ]; then
            kc get "authentication/flows/$(uri "$a")/executions" -r "$realm" >"$f.exec" 2>/dev/null || return 1
            echo '{}' >"$f.cfg"
            for cid in $(jq -r '.[] | select(.providerId == "conditional-user-attribute") | .authenticationConfig // empty' "$f.exec"); do
                c=$(kc get "authentication/config/$cid" -r "$realm" 2>/dev/null) || return 1
                jq --arg id "$cid" --argjson c "$c" '.[$id] = ($c.config // {})' "$f.cfg" >"$f.tmp" && mv "$f.tmp" "$f.cfg"
            done
            jq --slurpfile cfg "$f.cfg" "\$cfg[0] as \$cfg | $ANALYZE" "$f.exec" >"$f" || return 1
        fi
        echo "$f"
    }
    # check_entry <level-if-unguarded> <category> <entry description> <flow alias>
    check_entry() {
        lvl=$1 cat=$2 entry=$3 a=$4
        if [ -z "$a" ]; then
            rep REVIEW "$realm" "$cat" "$entry: bound flow could not be resolved"
            return
        fi
        f=$(analyze "$a") || { error "$realm" "$cat" "$entry: cannot read flow \"$a\""; return; }
        unknown=$(jq -r '.unknown | join(",")' "$f")
        [ -n "$unknown" ] && rep REVIEW "$realm" "$cat" "$entry flow=\"$a\": unrecognised authenticators ($unknown) may accept a local password or other credentials"
        if [ "$(jq '.steps | length' "$f")" = 0 ]; then
            report INFO "$realm" "$cat" "$entry flow=\"$a\": no local-password step found"
            return
        fi
        jq -r '.steps[] | "\(.status)\t\(.provider)\t\(.requirement)"' "$f" | while IFS="$(printf '\t')" read -r status provider requirement; do
            case "$status" in
                guarded) report INFO "$realm" "$cat" "$entry flow=\"$a\": $provider [$requirement] has a verified managed-user guard" ;;
                unguarded) rep "$lvl" "$realm" "$cat" "$entry flow=\"$a\": $provider [$requirement] accepts a local password from IMAP-managed users (no guard)" ;;
                *) rep REVIEW "$realm" "$cat" "$entry flow=\"$a\": $provider [$requirement] ${status#review:}" ;;
            esac
        done
    }

    browser=$(jq -r '.browserFlow // empty' "$rdir/realm.json")
    direct=$(jq -r '.directGrantFlow // empty' "$rdir/realm.json")
    reset=$(jq -r '.resetCredentialsFlow // empty' "$rdir/realm.json")
    broker=$(jq -r '.firstBrokerLoginFlow // empty' "$rdir/realm.json")
    docker_flow=$(jq -r '.dockerAuthenticationFlow // empty' "$rdir/realm.json")

    imap_flows=$(jq -r '.[] | select(.topLevel) | .alias' "$rdir/flows.json" | while read -r a; do
        if f=$(analyze "$a"); then
            [ "$(jq .imap "$f")" = true ] && echo "$a"
        else
            error "$realm" flows "cannot read flow \"$a\"" >&2
        fi
    done | paste -sd, -)
    report INFO "$realm" flows "flows containing the IMAP authenticator: ${imap_flows:-none}"

    # Managed users are read first: they decide whether the entry checks below apply to this realm.
    if ! get_paged "$realm" users "$rdir/users.json" -q briefRepresentation=false; then
        error "$realm" users "cannot list users; managed users were not checked"
        echo '[]' >"$rdir/users.json"
    fi
    jq -c --arg b "$BINDING_ATTRIBUTE" '.[] | select(((.attributes // {})[$b] // []) | map(select(length > 0)) | length > 0)
        | {id, username, enabled, federationLink, upd: ((.requiredActions // []) | index("UPDATE_PASSWORD") != null)}' \
        "$rdir/users.json" >"$rdir/managed.jsonl"
    managed=$(wc -l <"$rdir/managed.jsonl" | tr -d ' ')
    if [ -n "$imap_flows" ] || [ "$managed" -gt 0 ]; then
        applicable=yes
    else
        applicable=no
        report INFO "$realm" scope "no flow contains the IMAP authenticator and no user has $BINDING_ATTRIBUTE; findings below are informational"
    fi
    # rep: like report, but HIGH/WARNING/REVIEW become INFO in a realm where IMAP does not apply.
    rep() {
        if [ "$applicable" = no ] && [ "$1" != INFO ] && [ "$1" != ERROR ]; then
            report INFO "$2" "$3" "$4 (not applicable in this realm)"
        else
            report "$@"
        fi
    }

    # Realm default entry points.
    check_entry HIGH realm-browser "realm browser flow" "$browser"
    dg_clients=$(jq -r '[.[] | select(.enabled != false and .bearerOnly != true and .protocol == "openid-connect"
        and .directAccessGrantsEnabled == true and ((.overrides.direct_grant // "") == "")) | .clientId] | join(",")' "$rdir/clients.json")
    if [ -n "$dg_clients" ]; then
        check_entry HIGH realm-direct-grant "realm direct grant flow (Direct Access Grants clients without override: $dg_clients)" "$direct"
    else
        check_entry WARNING realm-direct-grant "realm direct grant flow (no enabled client uses Direct Access Grants now)" "$direct"
    fi

    # Clients: Direct Access Grants, flow overrides, SAML ECP, docker.
    jq -c '.[] | select(.enabled != false and .bearerOnly != true)' "$rdir/clients.json" | while read -r c; do
        cid=$(printf '%s' "$c" | jq -r .clientId)
        proto=$(printf '%s' "$c" | jq -r .protocol)
        label="client=$cid"
        [ "$cid" = admin-cli ] && label="client=admin-cli (created by Keycloak in every realm)"
        dg_override=$(printf '%s' "$c" | jq -r '.overrides.direct_grant // empty')
        br_override=$(printf '%s' "$c" | jq -r '.overrides.browser // empty')
        if [ "$proto" = openid-connect ] && [ "$(printf '%s' "$c" | jq .directAccessGrantsEnabled)" = true ]; then
            if [ -n "$dg_override" ]; then
                check_entry HIGH client-direct-grant "$label Direct Access Grants with direct_grant override" "$(alias_of "$dg_override")"
            else
                report INFO "$realm" client-direct-grant "$label Direct Access Grants enabled; uses the realm direct grant flow (see realm-direct-grant)"
            fi
        fi
        if [ -n "$br_override" ]; then
            check_entry HIGH client-browser "$label browser override" "$(alias_of "$br_override")"
        fi
        if [ "$proto" = saml ] && [ "$(printf '%s' "$c" | jq -r .ecp)" = true ]; then
            check_entry HIGH client-saml-ecp "$label allows SAML ECP; the built-in \"saml ecp\" flow cannot be modified or overridden, so only turning ECP off for this client removes the entry" "saml ecp"
        fi
        if [ "$proto" = docker-v2 ]; then
            check_entry HIGH client-docker "$label docker protocol (HTTP Basic, only when the docker feature is enabled)" "$(alias_of "$docker_flow")"
        fi
        case "$proto" in openid-connect | saml | docker-v2) ;; *) rep REVIEW "$realm" client "$label protocol=$proto is not analysed" ;; esac
    done

    # Identity brokering. A linked external account signs in without IMAP. New links are created by the first
    # broker login flow (re-authentication with the local password, e-mail verification, or automatic linking);
    # every login through the provider, with a new or an existing link, then runs the post broker login flow.
    : >"$rdir/idp-guarded"
    jq -c '.[] | select(.enabled != false)' "$rdir/idps.json" | while read -r i; do
        ialias=$(printf '%s' "$i" | jq -r .alias)
        fb=$(printf '%s' "$i" | jq -r --arg d "$broker" '.firstBrokerLoginFlowAlias // $d | select(length > 0)')
        pb=$(printf '%s' "$i" | jq -r '.postBrokerLoginFlowAlias // empty')
        post_guard=no
        if [ -n "$pb" ]; then
            if pf=$(analyze "$pb"); then
                if [ "$(jq .guard_any "$pf")" = true ] && [ "$(jq .allow_top "$pf")" = true ]; then
                    post_guard=yes
                    echo "$ialias" >>"$rdir/idp-guarded"
                    report INFO "$realm" idp "identity provider=$ialias post broker login flow=\"$pb\" denies IMAP-managed users after every login through this provider (new and existing links)"
                elif [ "$(jq .guard_any "$pf")" = true ]; then
                    rep REVIEW "$realm" idp "identity provider=$ialias post broker login flow=\"$pb\" has a managed-user guard but no REQUIRED Allow access at the top level; logins of other users through this provider fail"
                else
                    check_entry HIGH idp "identity provider=$ialias post broker login" "$pb"
                fi
            else
                error "$realm" idp "identity provider=$ialias: cannot read post broker login flow \"$pb\""
            fi
        fi
        if [ "$post_guard" = yes ]; then
            check_entry WARNING idp "identity provider=$ialias first broker login (a link can still be created, logins are denied by the post broker guard)" "$fb"
            continue
        fi
        check_entry HIGH idp "identity provider=$ialias first broker login" "$fb"
        if ff=$(analyze "$fb"); then
            [ "$(jq .email_link "$ff")" = true ] && rep WARNING "$realm" idp "identity provider=$ialias first broker login flow=\"$fb\": Verify existing account by Email is enabled; whoever can read mail at a managed user's address can link an external account that then signs in without IMAP"
            [ "$(jq .auto_link "$ff")" = true ] && rep HIGH "$realm" idp "identity provider=$ialias first broker login flow=\"$fb\": automatic linking (idp-auto-link) links external accounts to existing users without verification"
        fi
    done

    # Ways to obtain a local password.
    if [ "$(jq -r '.resetPasswordAllowed // false' "$rdir/realm.json")" = true ]; then
        if f=$(analyze "$reset"); then
            if [ "$(jq .guard_after_identity "$f")" = true ]; then
                report INFO "$realm" reset-credentials "Reset Password is enabled; flow \"$reset\" has a verified managed-user guard after the user is chosen"
            else
                rep WARNING "$realm" reset-credentials "Reset Password is enabled; IMAP-managed users can set a local password through the e-mail link (flow \"$reset\" has no verified guard)"
            fi
        else
            error "$realm" reset-credentials "cannot read reset credentials flow \"$reset\""
        fi
    else
        report INFO "$realm" reset-credentials "Reset Password is disabled"
    fi
    upd=$(kc get authentication/required-actions/UPDATE_PASSWORD -r "$realm" 2>/dev/null) \
        && upd=$(printf '%s' "$upd" | jq -r '.enabled // empty' 2>/dev/null) || upd=
    case "$upd" in
        true) report INFO "$realm" required-action "UPDATE_PASSWORD is enabled; IMAP-managed users can set a local password (kc_action, account console); flows cannot prevent this, guards must block its use" ;;
        false) report INFO "$realm" required-action "UPDATE_PASSWORD is disabled" ;;
        *) error "$realm" required-action "cannot read the UPDATE_PASSWORD required action" ;;
    esac
    [ "$(jq -r '.registrationAllowed // false' "$rdir/realm.json")" = true ] \
        && report INFO "$realm" registration "self-registration is enabled (creates new local users with passwords)"
    [ "$(jq -r '.bruteForceProtected // false' "$rdir/realm.json")" = true ] \
        || rep WARNING "$realm" brute-force "Brute Force Detection is disabled; IMAP and local-password failures are not counted"

    # User storage federation can validate passwords outside Keycloak.
    if comps=$(kc get components -r "$realm" -q type=org.keycloak.storage.UserStorageProvider 2>/dev/null); then
        printf '%s' "$comps" | jq -r '.[] | "\(.name) (\(.providerId))"' | while read -r name; do
            rep REVIEW "$realm" user-federation "user storage provider $name may validate passwords outside Keycloak; this audit cannot see them"
        done
    else
        error "$realm" user-federation "cannot list user storage providers"
    fi

    # Managed users: local passwords and pending UPDATE_PASSWORD.
    open_high=$(grep -c "^\[HIGH\] realm=$realm " "$findings" || true)
    with_password=0
    while read -r u; do
        id=$(printf '%s' "$u" | jq -r .id)
        name=$(printf '%s' "$u" | jq -r .username)
        state=$(printf '%s' "$u" | jq -r 'if .enabled == false then "disabled" else "enabled" end')
        # Only credential types are read; credential data is discarded immediately.
        if ! creds=$(kc get "users/$id/credentials" -r "$realm" 2>/dev/null) \
            || ! types=$(printf '%s' "$creds" | jq -r '[.[].type] | unique | join(",")' 2>/dev/null); then
            error "$realm" managed-user "user=$name id=$id: cannot read credential types"
            continue
        fi
        creds=
        case ",$types," in
            *,password,*)
                with_password=$((with_password + 1))
                if [ "$open_high" -gt 0 ]; then lvl=HIGH; else lvl=WARNING; fi
                report "$lvl" "$realm" managed-user "user=$name id=$id ($state) has a Keycloak local password" ;;
        esac
        if links=$(kc get "users/$id/federated-identity" -r "$realm" 2>/dev/null) \
            && links=$(printf '%s' "$links" | jq -r '[.[].identityProvider] | unique | join(",")' 2>/dev/null); then
            if [ -n "$links" ]; then
                open_links=$(printf '%s' "$links" | tr ',' '\n' | grep -vxF -f "$rdir/idp-guarded" | paste -sd, -)
                if [ -n "$open_links" ]; then
                    report WARNING "$realm" managed-user "user=$name id=$id ($state) is linked to identity provider(s) $open_links and can sign in there without IMAP"
                else
                    report INFO "$realm" managed-user "user=$name id=$id ($state) is linked to identity provider(s) $links; logins are denied by post broker guards"
                fi
            fi
        else
            error "$realm" managed-user "user=$name id=$id: cannot read identity provider links"
        fi
        [ "$(printf '%s' "$u" | jq .upd)" = true ] \
            && report WARNING "$realm" managed-user "user=$name id=$id ($state) has the UPDATE_PASSWORD required action"
        [ "$(printf '%s' "$u" | jq -r '.federationLink // empty')" != "" ] \
            && report REVIEW "$realm" managed-user "user=$name id=$id is linked to user storage federation"
    done <"$rdir/managed.jsonl"
    report INFO "$realm" managed-user "IMAP-managed users: $managed (with local password: $with_password)"
done

echo
echo "== Summary"
for level in ERROR HIGH WARNING REVIEW INFO; do
    printf '%-8s %s\n' "$level" "$(grep -c "^\[$level\]" "$findings" || true)"
done
cat <<'EOF'

== Coverage
Checked: realm browser and direct grant bindings; every enabled client's Direct Access Grants, browser and
direct_grant overrides; SAML clients that allow ECP; docker clients; identity provider broker flows; reset
credentials; UPDATE_PASSWORD; self-registration; brute force detection; user storage providers; and, for every user
with imap.auth.binding, local password credentials, identity provider links, UPDATE_PASSWORD and user
storage links.
Recognised local-password steps: auth-username-password-form, auth-password-form, direct-grant-validate-password,
idp-username-password-form, http-basic-authenticator, docker-http-basic-authenticator.
Not covered: third-party authenticators (reported as REVIEW when unrecognised, but their behaviour is unknown),
passwords validated by user storage providers, custom REST endpoints or extensions, entry points added by future
Keycloak versions, and anything changed after this run. A guard is "verified" only by its static shape; test the
flows in a non-production realm. Exit status 0 is not a proof that no local-password path exists.
EOF

if grep -q '^\[ERROR\]' "$findings"; then
    echo "RESULT: INCOMPLETE - some requests failed; do not treat this run as clean. Nothing was modified." >&2
    exit 2
fi
if grep -qE '^\[(HIGH|WARNING|REVIEW)\]' "$findings"; then
    echo "RESULT: findings require action or review. Nothing was modified." >&2
    exit 1
fi
echo "RESULT: no HIGH, WARNING or REVIEW findings within the audit scope. Nothing was modified." >&2
exit 0
