#!/usr/bin/env bash
set -euo pipefail

KEYSTORE_FILE="release.keystore"
ALIAS="recipecatcher"
STORE_PASS="${1:-$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 20)}"
KEY_PASS="${STORE_PASS}"

if [ -f "$KEYSTORE_FILE" ]; then
    echo "Keystore $KEYSTORE_FILE already exists. Remove it or back it up first."
    exit 1
fi

echo "Generating release keystore..."
keytool -genkeypair \
    -v \
    -keystore "$KEYSTORE_FILE" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass "$STORE_PASS" \
    -keypass "$KEY_PASS" \
    -dname "CN=Recipe Catcher, OU=Android, O=Recipe Catcher, L=Kolkata, ST=WB, C=IN"

echo "Keystore created: $KEYSTORE_FILE"
echo ""
echo "=== GitHub Actions Secrets ==="
echo "KEYSTORE_BASE64:"
base64 "$KEYSTORE_FILE" | tr -d '\r\n'
echo ""
echo "KEYSTORE_PASSWORD: $STORE_PASS"
echo "KEY_ALIAS: $ALIAS"
echo "KEY_PASSWORD: $KEY_PASS"
