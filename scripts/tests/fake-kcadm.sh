#!/bin/sh
# Test double for kcadm.sh: replays recorded Keycloak 26.8.1 Admin REST responses.
# Fixtures live in $FIXTURES/<realm>/<key>.json, where <key> is the argument list with the realm argument (the value
# of -r, or realms/<realm>) replaced by REALM, "/" by "~" and spaces by "_". A missing fixture behaves like a failed
# request (exit 1). FAKE_LOG, when set, records every invocation.
[ -n "${FAKE_LOG:-}" ] && printf '%s\n' "$*" >>"$FAKE_LOG"
realm=
key=
prev=
for a in "$@"; do
    if [ "$prev" = "-r" ]; then
        realm=$a
        a=REALM
    fi
    case "$a" in realms/*) realm=${a#realms/}; a=realms/REALM ;; esac
    key="${key:+${key} }$a"
    prev=$a
done
key=$(printf '%s' "$key" | tr '/ ' '~_')
file=$FIXTURES/$realm/$key.json
if [ -f "$file" ]; then
    cat "$file"
    exit 0
fi
echo "fake-kcadm: no fixture for: $*" >&2
exit 1
