#!/bin/sh
set -eu

preview_url=${1:-${GROUP33_PREVIEW_URL:-}}

if [ -z "$preview_url" ]; then
    echo "Usage: $0 https://your-service.onrender.com" >&2
    echo "Or set GROUP33_PREVIEW_URL." >&2
    exit 64
fi

case "$preview_url" in
    https://*.onrender.com | https://*.onrender.com/) ;;
    *)
        echo "Refusing a non-Render or non-HTTPS preview URL: $preview_url" >&2
        exit 64
        ;;
esac

health_url=${preview_url%/}/health
attempt=1

while [ "$attempt" -le 18 ]; do
    status=$(curl \
        --silent \
        --show-error \
        --output /dev/null \
        --write-out '%{http_code}' \
        --connect-timeout 10 \
        --max-time 20 \
        "$health_url" || true)

    if [ "$status" = "200" ]; then
        echo "Preview is awake: $health_url"
        exit 0
    fi

    if [ "$attempt" -lt 18 ]; then
        echo "Wake attempt $attempt/18 returned HTTP ${status:-000}; retrying in 5 seconds..." >&2
        sleep 5
    else
        echo "Wake attempt $attempt/18 returned HTTP ${status:-000}." >&2
    fi
    attempt=$((attempt + 1))
done

echo "Preview did not become healthy after 18 attempts: $health_url" >&2
exit 1
