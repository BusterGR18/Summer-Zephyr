#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  tooling/android-auto/install-identity.sh CERTIFICATE PRIVATE_KEY

CERTIFICATE may be PEM or DER X.509.
PRIVATE_KEY may be a PEM PKCS#1 or PKCS#8 RSA key.

The script verifies that the certificate and key match, then writes the private
build inputs expected by the app:
  tooling/private/android-auto/aa_cert
  tooling/private/android-auto/aa_identity_data
USAGE
}

if [[ $# -ne 2 ]]; then
  usage
  exit 2
fi

command -v openssl >/dev/null || {
  echo "openssl is required" >&2
  exit 1
}

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cert_input="$1"
key_input="$2"
output_dir="$project_root/tooling/private/android-auto"
mkdir -p "$output_dir"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

# Normalize certificate to PEM and key to unencrypted PKCS#8 DER.
openssl x509 -in "$cert_input" -inform PEM -out "$tmp_dir/cert.pem" -outform PEM 2>/dev/null || \
  openssl x509 -in "$cert_input" -inform DER -out "$tmp_dir/cert.pem" -outform PEM
openssl pkey -in "$key_input" -out "$tmp_dir/key.pem"

cert_public="$({ openssl x509 -in "$tmp_dir/cert.pem" -pubkey -noout | openssl pkey -pubin -outform DER; } | shasum -a 256 | awk '{print $1}')"
key_public="$({ openssl pkey -in "$tmp_dir/key.pem" -pubout -outform DER; } | shasum -a 256 | awk '{print $1}')"

if [[ "$cert_public" != "$key_public" ]]; then
  echo "The certificate and private key do not match." >&2
  exit 1
fi

cp "$tmp_dir/cert.pem" "$output_dir/aa_cert"
openssl pkey -in "$tmp_dir/key.pem" -outform DER | openssl base64 -A > "$output_dir/aa_identity_data"
printf '\n' >> "$output_dir/aa_identity_data"
chmod 600 "$output_dir/aa_cert" "$output_dir/aa_identity_data"

echo "Private Android Auto identity installed under:"
echo "  $output_dir"
echo "These files are ignored by Git. Do not publish the resulting private APK."
