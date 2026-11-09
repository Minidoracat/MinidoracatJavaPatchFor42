#!/usr/bin/env bash
# 在隔離的臨時 serverfiles 上驗證 install -> uninstall，可重複兩輪且不接觸正式路徑。
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
TMP_BASE="${TMPDIR:-/tmp}"
TMP_BASE="${TMP_BASE%/}"
TMP_ROOT="$(mktemp -d "$TMP_BASE/mdc-pz-patch-roundtrip.XXXXXX")"

case "$TMP_ROOT" in
    "$TMP_BASE"/mdc-pz-patch-roundtrip.*) ;;
    *)
        echo "[中止] mktemp 路徑不在預期範圍：$TMP_ROOT" >&2
        exit 1
        ;;
esac
[ "$TMP_ROOT" != "/" ] || { echo "[中止] 拒絕使用根目錄" >&2; exit 1; }
# uninstall 會把安裝目錄搬到 serverfiles 的上一層，所以 serverfiles 放在暫存目錄裡一層
TMP_SERVER="$TMP_ROOT/serverfiles"
[ "$TMP_SERVER" != "/home/pzserver/serverfiles" ] || { echo "[中止] 拒絕使用正式路徑" >&2; exit 1; }

cleanup() {
    case "$TMP_ROOT" in
        "$TMP_BASE"/mdc-pz-patch-roundtrip.*)
            rm -rf -- "$TMP_ROOT"
            ;;
        *)
            echo "[警告] cleanup 路徑驗證失敗，未移除：$TMP_ROOT" >&2
            ;;
    esac
}
trap cleanup EXIT

mkdir -p "$TMP_SERVER/java"
cp "$ROOT/work/projectzomboid.jar" "$TMP_SERVER/java/projectzomboid.jar"
agent_sha="$(awk -F'\t' '$1 == "agent" { print $2 }' "$ROOT/dist/agent-manifest.txt")"

for round in 1 2; do
    echo "== round $round install =="
    PZ_SERVERFILES="$TMP_SERVER" bash "$ROOT/dist/install.sh"
    cmp "$ROOT/dist/agent-manifest.txt" "$TMP_SERVER/mdc-javapatch/agent-manifest.txt"
    live_sha="$(sha256sum "$TMP_SERVER/mdc-javapatch/mdc-javapatch-agent.jar" | cut -d' ' -f1)"
    [ "$live_sha" = "$agent_sha" ] || { echo "[失敗] install 後 agent jar SHA 不符" >&2; exit 1; }
    [ "$(find "$TMP_SERVER/java" -name '*.class' | wc -l)" = 0 ] || { echo "[失敗] install 在 java/ 放了 loose class" >&2; exit 1; }

    echo "== round $round uninstall =="
    PZ_SERVERFILES="$TMP_SERVER" bash "$ROOT/dist/uninstall.sh"
    [ ! -e "$TMP_SERVER/mdc-javapatch" ] || { echo "[失敗] uninstall 後安裝目錄仍在" >&2; exit 1; }
    parked="$(find "$TMP_ROOT" -maxdepth 1 -name 'patch-disabled-*-uninstall*' | wc -l)"
    [ "$parked" = "$round" ] || { echo "[失敗] uninstall 後應有 $round 個搬走的目錄，實得 $parked" >&2; exit 1; }
done

echo "install-roundtrip OK  兩輪 install/uninstall 均完整且可回復"
