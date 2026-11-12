#!/bin/sh
set -eu

java -jar /app/server.jar &
server_pid=$!
keep_alive_pid=

stop_processes() {
    kill -TERM "$server_pid" 2>/dev/null || true
    if [ -n "$keep_alive_pid" ]; then
        kill -TERM "$keep_alive_pid" 2>/dev/null || true
    fi
}

trap stop_processes INT TERM

if [ "${KEEP_ALIVE_ENABLED:-false}" = "true" ] && [ -n "${RENDER_EXTERNAL_URL:-}" ]; then
    interval=${KEEP_ALIVE_INTERVAL_SECONDS:-600}
    case "$interval" in
        *[!0-9]* | "")
            echo "KEEP_ALIVE_INTERVAL_SECONDS must be a positive integer" >&2
            stop_processes
            exit 64
            ;;
    esac

    if [ "$interval" -lt 60 ] || [ "$interval" -ge 900 ]; then
        echo "KEEP_ALIVE_INTERVAL_SECONDS must be between 60 and 899" >&2
        stop_processes
        exit 64
    fi

    (
        while kill -0 "$server_pid" 2>/dev/null; do
            sleep "$interval"
            curl \
                --fail \
                --silent \
                --show-error \
                --connect-timeout 10 \
                --max-time 30 \
                "${RENDER_EXTERNAL_URL%/}/health" >/dev/null \
                || echo "Keep-alive health request failed; the next interval will retry" >&2
        done
    ) &
    keep_alive_pid=$!
fi

set +e
wait "$server_pid"
exit_code=$?
set -e

if [ -n "$keep_alive_pid" ]; then
    kill -TERM "$keep_alive_pid" 2>/dev/null || true
    wait "$keep_alive_pid" 2>/dev/null || true
fi

exit "$exit_code"
