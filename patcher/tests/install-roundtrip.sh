#!/usr/bin/env bash
# 在隔離的臨時 serverfiles 上驗證 install -> uninstall，可重複兩輪且不接觸正式路徑。
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
TMP_BASE="${TMPDIR:-/tmp}"
TMP_BASE="${TMP_BASE%/}"
TMP_SERVER="$(mktemp -d "$TMP_BASE/mdc-pz-patch-roundtrip.XXXXXX")"

case "$TMP_SERVER" in
    "$TMP_BASE"/mdc-pz-patch-roundtrip.*) ;;
    *)
        echo "[中止] mktemp 路徑不在預期範圍：$TMP_SERVER" >&2
        exit 1
        ;;
esac
[ "$TMP_SERVER" != "/" ] || { echo "[中止] 拒絕使用根目錄" >&2; exit 1; }
[ "$TMP_SERVER" != "/home/pzserver/serverfiles" ] || { echo "[中止] 拒絕使用正式路徑" >&2; exit 1; }

cleanup() {
    case "$TMP_SERVER" in
        "$TMP_BASE"/mdc-pz-patch-roundtrip.*)
            rm -rf -- "$TMP_SERVER"
            ;;
        *)
            echo "[警告] cleanup 路徑驗證失敗，未移除：$TMP_SERVER" >&2
            ;;
    esac
}
trap cleanup EXIT

mkdir -p "$TMP_SERVER/java"
cp "$ROOT/work/projectzomboid.jar" "$TMP_SERVER/java/projectzomboid.jar"

for round in 1 2; do
    echo "== round $round install =="
    PZ_SERVERFILES="$TMP_SERVER" bash "$ROOT/dist/install.sh"
    cmp "$ROOT/dist/manifest.txt" "$TMP_SERVER/java/patch-manifest.txt"
    while IFS=$'\t' read -r entry _orig_sha patched_sha _rest; do
        [ -n "$entry" ] || continue
        [ -f "$TMP_SERVER/java/$entry" ] || {
            echo "[失敗] install 後缺少 $entry" >&2
            exit 1
        }
        live_sha="$(sha256sum "$TMP_SERVER/java/$entry" | cut -d' ' -f1)"
        [ "$live_sha" = "$patched_sha" ] || {
            echo "[失敗] install 後 $entry SHA 不符" >&2
            exit 1
        }
    done < "$ROOT/dist/manifest.txt"

    echo "== round $round uninstall =="
    PZ_SERVERFILES="$TMP_SERVER" bash "$ROOT/dist/uninstall.sh"
    [ ! -e "$TMP_SERVER/java/patch-manifest.txt" ] || {
        echo "[失敗] uninstall 後 patch-manifest.txt 仍存在" >&2
        exit 1
    }
    while IFS=$'\t' read -r entry _rest; do
        [ -n "$entry" ] || continue
        [ ! -e "$TMP_SERVER/java/$entry" ] || {
            echo "[失敗] uninstall 後仍存在 $entry" >&2
            exit 1
        }
    done < "$ROOT/dist/manifest.txt"
done

echo "install-roundtrip OK  兩輪 install/uninstall 均完整且可回復"
