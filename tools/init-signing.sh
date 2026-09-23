#!/usr/bin/env bash
# One-time: create the release signing keystore and seal it into secrets.yaml.
#
# The password is generated here and never printed or typed, so it does not end
# up in a shell history or a terminal scrollback. The only copy that matters is
# the encrypted one in secrets.yaml.
#
# Losing the keystore means no existing install can ever be updated — Android
# refuses an APK signed with a different key. That is why it lives encrypted in
# the repository rather than on one machine.
#
#   nix develop --command tools/init-signing.sh
set -euo pipefail

if [[ -f secrets.yaml ]]; then
    echo "secrets.yaml already exists — refusing to overwrite a signing key." >&2
    echo "Remove it deliberately if you really mean to start over." >&2
    exit 1
fi

KEYSTORE="miefquirl.jks"
ALIAS="${1:-miefquirl}"
DNAME="${DNAME:-CN=Holger Amann, O=miefquirl}"

if [[ -f "$KEYSTORE" ]]; then
    echo "$KEYSTORE already exists — refusing to overwrite it." >&2
    exit 1
fi

PASSWORD=$(head -c 32 /dev/urandom | base64 | tr -d '\n=/+' | head -c 32)

keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -storepass "$PASSWORD" \
    -keypass "$PASSWORD" \
    -dname "$DNAME" \
    2>/dev/null

# Written straight to its final name so sops matches the creation rule for
# secrets.yaml, then encrypted in place — the plaintext exists only for the
# moment between these two lines.
umask 077
cat > secrets.yaml <<EOF
keystore_base64: $(base64 < "$KEYSTORE" | tr -d '\n')
keystore_password: $PASSWORD
key_alias: $ALIAS
key_password: $PASSWORD
EOF

sops -e -i secrets.yaml

if grep -q "keystore_password: $PASSWORD" secrets.yaml 2>/dev/null; then
    echo "secrets.yaml is still in plaintext — encryption failed" >&2
    rm -f secrets.yaml
    exit 1
fi

echo "created $KEYSTORE (alias $ALIAS) and sealed it into secrets.yaml"
echo "secrets.yaml is safe to commit; $KEYSTORE is gitignored and regenerable from it"
