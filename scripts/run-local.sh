#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
    echo '.env is missing. Run ./scripts/setup-local.sh before starting the API.' >&2
    exit 1
fi

set -a
source .env
set +a

: "${JWT_PRIVATE_KEY_B64:?JWT_PRIVATE_KEY_B64 is missing from .env.}"
: "${JWT_PUBLIC_KEY_B64:?JWT_PUBLIC_KEY_B64 is missing from .env.}"
: "${JWT_ISSUER:?JWT_ISSUER is missing from .env.}"
: "${JWT_AUDIENCE:?JWT_AUDIENCE is missing from .env.}"

source scripts/java-env.sh
exec ./mvnw spring-boot:run "$@"
