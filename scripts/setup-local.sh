#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
if [ -e .env ] || [ -e .secrets ]; then
    echo '.env or .secrets already exists. Existing files are preserved; keys and passwords are not regenerated.'
    exit 0
fi
mkdir .secrets
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out .secrets/private.pem 2>/dev/null
openssl pkcs8 -topk8 -nocrypt -in .secrets/private.pem -outform DER -out .secrets/private.der
openssl pkey -in .secrets/private.pem -pubout -outform DER -out .secrets/public.der
cat > .env <<ENV
JWT_ISSUER=conexa-star-wars
JWT_AUDIENCE=conexa-catalog
JWT_PRIVATE_KEY_B64=$(base64 < .secrets/private.der | tr -d '\n')
JWT_PUBLIC_KEY_B64=$(base64 < .secrets/public.der | tr -d '\n')
ENV
chmod 600 .env
printf 'Local environment created in .env. Private RSA keys are stored in .secrets/. Both paths are ignored by Git.\n'
