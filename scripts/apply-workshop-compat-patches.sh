#!/bin/bash
# Cron wrapper around apply-workshop-compat-patches.py (repo: apply_workshop_compat_patches.py).
# Install as mode 0755: the Baota cron executes this path directly, not via bash.
# Does not run fix-permissions.sh — cron must still call that afterwards, then
# clean the 宝塔 .pl marker, then exit with this script's status.
# Exit codes from the python: 0 ok, 2 dry-run needs patch, 3 warnings.
set -u

PY=/home/pzserver/scripts/apply-workshop-compat-patches.py
LOG_DIR=/home/pzserver/log/script
STATE=$LOG_DIR/workshop-compat.last-alert
WEBHOOK_CFG=/home/pzserver/scripts/mod-update-checker/config.cfg

mkdir -p "$LOG_DIR"

LOG=$LOG_DIR/workshop-compat-$(date +%F).log
{
    echo "[$(date '+%F %T')] start $*"
    python3 "$PY" "$@"
    rc=$?
    echo "[$(date '+%F %T')] exit=$rc"
} >>"$LOG" 2>&1
rc=${rc:-1}

if [ "$rc" -ne 0 ]; then
    today=$(date +%F)
    last=$(cat "$STATE" 2>/dev/null || true)
    if [ "$last" != "$today" ]; then
        webhook=
        if [ -f "$WEBHOOK_CFG" ]; then
            webhook=$(python3 -c '
import re, sys
from pathlib import Path
text = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
for line in text.splitlines():
    match = re.match(r"\s*DISCORD_WEBHOOK=(.*)$", line)
    if match:
        print(match.group(1).strip().strip("\"'\''"))
        break
' "$WEBHOOK_CFG")
        fi
        if [ -n "$webhook" ]; then
            excerpt=$(tail -n 20 "$LOG")
            payload=$(python3 -c 'import json,sys; print(json.dumps({"content": sys.stdin.read()[:1800]}))' <<EOF
workshop-compat patches failed (exit $rc) on $today
\`\`\`
$excerpt
\`\`\`
EOF
)
            http=$(curl -sS --fail --connect-timeout 5 --max-time 15 \
                -o /dev/null -w '%{http_code}' \
                -X POST -H 'Content-Type: application/json' \
                -d "$payload" "$webhook" || true)
            if [ "$http" = "204" ] || [ "$http" = "200" ]; then
                printf '%s\n' "$today" >"$STATE"
            fi
        fi
    fi
fi

exit "$rc"
