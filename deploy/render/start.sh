#!/bin/sh
set -eu
umask 077
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
app_dir=${GROUP33_APP_DIR:-/app}
bind_address=${GROUP33_BIND_ADDRESS:-0.0.0.0}
case "$bind_address" in 0.0.0.0|127.0.0.1) ;; *) echo "Invalid bind address" >&2; exit 64 ;; esac
PORT=${PORT:-10000}
export PORT
config_file=$(java -Xms16m -Xmx48m "-Djava.io.tmpdir=${TMPDIR:-/tmp}" -cp "$app_dir/bootstrap" RenderConfigWriter)
# Replace this shell with the server. No bootstrap JVM remains; no private env reaches the child.
exec env -i PATH="$PATH" HOME="${HOME:-/tmp}" LANG=C.UTF-8 PORT="$PORT" \
    java -XX:InitialRAMPercentage=10.0 -XX:MaxRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError \
    -jar "$app_dir/server.jar" "--spring.config.additional-location=file:$config_file" \
    "--server.address=$bind_address" "--server.port=$PORT"
