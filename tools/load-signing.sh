#!/usr/bin/env bash
# Decrypt the release signing material out of secrets.yaml and expose it to the
# Gradle build.
#
# secrets.yaml is the single source of truth: encrypted with sops and committed,
# so the signing key survives a lost laptop, and rotating it is a matter of
# re-encrypting and committing rather than remembering to re-push values
# somewhere else.
#
# In CI, sops decrypts with the dedicated key in SOPS_AGE_KEY. Locally it uses
# whatever key you already have in ~/.config/sops/age/keys.txt.
#
# Sourced, it exports the variables into the current shell:
#
#   source tools/load-signing.sh
#   (cd android && gradle assembleRelease)
#
# Run under GitHub Actions it writes them to $GITHUB_ENV for later steps.
set -euo pipefail

FILE="secrets.yaml"
if [[ ! -f "$FILE" ]]; then
    echo "$FILE not found — see the Releasing section of the README" >&2
    exit 1
fi

DEST="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
KEYSTORE="$DEST/miefquirl.jks"

extract() {
    # Fails loudly rather than yielding an empty value, which would otherwise
    # surface much later as an unhelpful Gradle error.
    sops -d --extract "[\"$1\"]" "$FILE"
}

extract keystore_base64 | base64 -d > "$KEYSTORE"
if [[ ! -s "$KEYSTORE" ]]; then
    echo "decrypted keystore is empty" >&2
    exit 1
fi
chmod 600 "$KEYSTORE"

KEYSTORE_PASSWORD=$(extract keystore_password)
KEY_ALIAS=$(extract key_alias)
KEY_PASSWORD=$(extract key_password)

export MIEFQUIRL_KEYSTORE="$KEYSTORE"
export MIEFQUIRL_KEYSTORE_PASSWORD="$KEYSTORE_PASSWORD"
export MIEFQUIRL_KEY_ALIAS="$KEY_ALIAS"
export MIEFQUIRL_KEY_PASSWORD="$KEY_PASSWORD"

if [[ -n "${GITHUB_ENV:-}" ]]; then
    # Mask before writing: anything that reaches a log line afterwards is
    # replaced with ***, and CI logs are the usual way secrets escape.
    for secret in "$KEYSTORE_PASSWORD" "$KEY_PASSWORD"; do
        echo "::add-mask::$secret"
    done
    {
        echo "MIEFQUIRL_KEYSTORE=$KEYSTORE"
        echo "MIEFQUIRL_KEYSTORE_PASSWORD=$KEYSTORE_PASSWORD"
        echo "MIEFQUIRL_KEY_ALIAS=$KEY_ALIAS"
        echo "MIEFQUIRL_KEY_PASSWORD=$KEY_PASSWORD"
    } >> "$GITHUB_ENV"
fi

echo "signing material ready: $KEYSTORE (alias $KEY_ALIAS)"
