#!/usr/bin/env bash
# Generate a VAPID (ECDSA P-256) key pair for web push and print values ready
# to be set as env vars / sealed as a Kubernetes secret.
#
# Usage:   ./scripts/generate-vapid.sh
# Output:  WEBPUSH_PUBLIC_KEY=...
#          WEBPUSH_PRIVATE_KEY=...
#
# The backend expects URL-safe base64 with no padding, which is what
# nl.martijndwars.web-push (and every browser PushManager) consumes. OpenSSL
# gives us raw binary; we strip/encode here so the output can be pasted
# straight into a Secret manifest.

set -euo pipefail

if ! command -v openssl >/dev/null 2>&1; then
  echo "openssl is required" >&2
  exit 1
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# 1. Generate an ECDSA P-256 private key (PEM, SEC1 form).
openssl ecparam -name prime256v1 -genkey -noout -out "$tmp/private.pem" 2>/dev/null

# 2. Extract the raw 32-byte private scalar. DER layout:
#      30 <len>
#        02 01 01                     ; version = 1
#        04 20 <32 bytes private>     ; SEC1 private key
#        a0 <...>                     ; named curve (optional)
#        a1 <...>                     ; public key (optional)
#    We grab the 32 bytes right after the `04 20` prefix.
openssl ec -in "$tmp/private.pem" -outform DER 2>/dev/null \
  | tail -c +8 | head -c 32 > "$tmp/private.bin"

# 3. Public key in uncompressed form (04 || X (32) || Y (32)) = 65 bytes.
openssl ec -in "$tmp/private.pem" -pubout -outform DER 2>/dev/null \
  | tail -c 65 > "$tmp/public.bin"

url_b64() {
  base64 -w0 < "$1" | tr '+/' '-_' | tr -d '='
}

pub="$(url_b64 "$tmp/public.bin")"
priv="$(url_b64 "$tmp/private.bin")"

cat <<EOF
# Paste these into .env (dev) or into a Kubernetes Secret (prod).
# The public key is handed to the browser via GET /api/push/config and is
# therefore safe to ship in the frontend bundle too. The private key stays
# server-side only.

WEBPUSH_PUBLIC_KEY=$pub
WEBPUSH_PRIVATE_KEY=$priv
WEBPUSH_SUBJECT=mailto:admin@kin-keeper.local

# To seal for k8s:
#   cat <<YAML | kubeseal --cert sealed-secrets-cert.pem --format yaml >> k8s/webpush-sealed-secret.yaml
#   apiVersion: v1
#   kind: Secret
#   metadata:
#     name: kin-keeper-webpush
#     namespace: homelab
#   stringData:
#     WEBPUSH_PUBLIC_KEY: $pub
#     WEBPUSH_PRIVATE_KEY: $priv
#     WEBPUSH_SUBJECT: mailto:admin@kin-keeper.local
#   YAML
EOF
