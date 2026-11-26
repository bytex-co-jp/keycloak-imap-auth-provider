#!/bin/sh
# Read-only pre-upgrade check for the IMAP authenticator credential-type change.
#
# Since this release a successful IMAP login is recorded as a "password" credential, as Keycloak's own password form
# does. "Condition - credential" (conditional-credential) evaluates the credentials recorded in the authentication
# session, so a condition in a flow that contains the IMAP authenticator can change its result after the upgrade.
# For example, a sub-flow that adds OTP when "none" was used no longer runs after an IMAP login.
#
# The script only sends GET requests through kcadm.sh and never modifies a flow. It cannot prove that a flow is
# safe: every flow that contains the IMAP authenticator is reported for manual review.
#
# Usage:
#   kcadm.sh config credentials --server https://keycloak.example.com --realm master --user <admin>
#   scripts/check-imap-credential-conditions.sh <realm> [<realm>...]
#
# Environment:
#   KCADM  kcadm command, split on spaces (default: kcadm.sh),
#          e.g. KCADM="docker exec -i keycloak /opt/keycloak/bin/kcadm.sh"
#
# Requires jq. Exit status: 0 = no flow in the listed realms contains the IMAP authenticator,
# 1 = findings that need manual review, 2 = usage or request error (results are incomplete).

set -u

KCADM=${KCADM:-kcadm.sh}
TAB=$(printf '\t')

kc() {
    # shellcheck disable=SC2086 # KCADM is a command line by design.
    $KCADM "$@" </dev/null
}

uri() {
    printf '%s' "$1" | jq -sRr @uri
}

fail() {
    echo "ERROR $*" >&2
    exit 2
}

if [ $# -eq 0 ]; then
    sed -n '2,23p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
fi
command -v jq >/dev/null 2>&1 || fail "jq is required"

out=$(mktemp) || fail "cannot create a temporary file"
trap 'rm -f "$out"' EXIT

report() {
    echo "$1" | tee -a "$out"
}

# Built-in conditions that do not read the recorded credential types (Keycloak 26.8.1).
KNOWN_CONDITIONS='["conditional-user-configured","conditional-user-role","conditional-user-attribute",
  "conditional-level-of-authentication","conditional-sub-flow-executed","conditional-client-scope"]'

for realm in "$@"; do
    realm_json=$(kc get "realms/$(uri "$realm")") || fail "cannot read realm $realm"
    flows=$(kc get authentication/flows -r "$realm") || fail "cannot list flows of realm $realm"
    clients=$(kc get clients -r "$realm" --fields 'clientId,authenticationFlowBindingOverrides(*)') \
        || fail "cannot list clients of realm $realm"
    idps=$(kc get identity-provider/instances -r "$realm") || fail "cannot list identity providers of realm $realm"

    flow_list=$(printf '%s' "$flows" | jq -r '.[] | select(.topLevel) | "\(.id)\t\(.alias)"')
    while IFS="$TAB" read -r flow_id alias; do
        [ -n "$flow_id" ] || continue
        execs=$(kc get "authentication/flows/$(uri "$alias")/executions" -r "$realm") \
            || fail "cannot read executions of flow \"$alias\" in realm $realm"

        used_by=$(jq -nr --arg id "$flow_id" --arg alias "$alias" \
            --argjson realm "$realm_json" --argjson clients "$clients" --argjson idps "$idps" '
            [ ($realm | to_entries[] | select((.key | endswith("Flow")) and .value == $alias) | "realm:\(.key)"),
              ($clients[] | select((.authenticationFlowBindingOverrides // {}) | to_entries | any(.value == $id))
                | "client:\(.clientId)"),
              ($idps[] | select(.firstBrokerLoginFlowAlias == $alias or .postBrokerLoginFlowAlias == $alias)
                | "idp:\(.alias)") ] | if length == 0 then "unbound" else join(",") end')
        label="realm=$realm flow=\"$alias\" used_by=$used_by"

        has_imap=$(printf '%s' "$execs" | jq 'any(.[]; .providerId == "imap-authenticator")')
        # One line per Condition - credential: config id, enclosing sub-flow, its sibling steps, IMAP position.
        conditions=$(printf '%s' "$execs" | jq -r '
            . as $all | range(0; length) as $i | $all[$i] | select(.providerId == "conditional-credential") | . as $c
            | ([range(0; $i)] | map(select($all[.].level == $c.level - 1)) | last) as $p
            | ([range($i + 1; $all | length)] | reduce .[] as $j ({stop: false, out: []};
                if .stop or $all[$j].level < $c.level then .stop = true
                elif $all[$j].level == $c.level then .out += [$all[$j].providerId // $all[$j].displayName]
                else . end) | .out | join(",")) as $steps
            | [ ($c.authenticationConfig // "-"),
                (if $p == null then "(top level)" else "\($all[$p].displayName)[\($all[$p].requirement)]" end),
                (if $steps == "" then "-" else $steps end),
                (any($all[0:$i][]; .providerId == "imap-authenticator") | tostring) ] | join("\t")')

        if [ "$has_imap" != true ]; then
            if [ -n "$conditions" ]; then
                report "[INFO]   $label: Condition - credential without the IMAP authenticator; not affected unless the same authentication session also runs a flow that contains IMAP"
            fi
            continue
        fi

        others=$(printf '%s' "$execs" | jq -r --argjson known "$KNOWN_CONDITIONS" '
            [.[] | .providerId // empty | select(startswith("conditional-") and . != "conditional-credential")
             | select(IN($known[]) | not)] | unique | join(",")')
        if [ -z "$conditions" ]; then
            report "[REVIEW] $label: contains IMAP and no Condition - credential; confirm that no custom authenticator or condition reads the recorded credential types"
        fi
        if [ -n "$others" ]; then
            report "[REVIEW] $label: unrecognised conditional authenticators ($others) may depend on the recorded credential types"
        fi

        printf '%s\n' "$conditions" | while IFS="$TAB" read -r cfg_id subflow steps imap_before; do
            [ -n "$cfg_id" ] || continue
            where="subflow=\"$subflow\" steps=$steps imap_earlier=$imap_before"
            if [ "$cfg_id" = "-" ]; then
                report "[REVIEW] $label $where: Condition - credential has no configuration"
                continue
            fi
            cfg=$(kc get "authentication/config/$cfg_id" -r "$realm") || {
                report "[ERROR]  $label $where: cannot read configuration $cfg_id"
                continue
            }
            creds=$(printf '%s' "$cfg" | jq -r '.config.credentials // ""')
            included=$(printf '%s' "$cfg" | jq -r '.config.included // "false"')
            verdict=$(jq -nr --arg creds "$creds" --arg inc "$included" '
                ($creds | split("##") | map(select(length > 0))) as $set | ($inc == "true") as $in
                | ($set | index("none") != null) as $none | ($set | index("password") != null) as $pw
                | if ($none and $in) or ($pw and ($in | not)) then
                    "HIGH|matched IMAP logins before the upgrade and no longer matches: the steps of this sub-flow (for example OTP) are skipped after IMAP"
                  elif ($pw and $in) or ($none and ($in | not)) then
                    "CHANGE|did not match IMAP logins before the upgrade and now matches: the steps of this sub-flow start to run after IMAP"
                  else
                    "REVIEW|references neither none nor password; the result still depends on credentials recorded by other authenticators in the same session"
                  end')
            report "[${verdict%%|*}] $label $where credentials=$creds included=$included: ${verdict#*|}"
        done
    done <<EOF
$flow_list
EOF
done

if grep -q '^\[ERROR\]' "$out"; then
    echo "RESULT: some configurations could not be read; the check is incomplete. No flow was modified." >&2
    exit 2
fi
if grep -qE '^\[(HIGH|CHANGE|REVIEW)\]' "$out"; then
    echo "RESULT: review every finding before upgrading. No flow was modified." >&2
    exit 1
fi
echo "RESULT: no flow in the listed realms contains the IMAP authenticator. No flow was modified." >&2
exit 0
