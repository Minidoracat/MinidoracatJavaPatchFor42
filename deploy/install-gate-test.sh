#!/usr/bin/env bash
# install.sh / uninstall.sh 的閘門行為驗證（改動這兩個腳本後必跑）。
#
# 用 dist\ 的產物與 work\projectzomboid.jar 在暫存目錄搭一個假的 serverfiles，
# 跑四個情境。重點是 C：**舊 patch-manifest.txt 遺失、但上一版的 loose class 還在**——
# 退役項目（如 2n 的 IsoGridSquare）若只殘留改道版 caller 而 helper 已隨新版刪除，
# chunk 載入路徑會 NoClassDefFoundError，所以第 3 閘必須 fail-closed 而不是只警告。
#
#   bash deploy/install-gate-test.sh
#
# 需要 unzip / sha256sum（Git Bash 與 WSL 皆有）。先跑過 build.ps1 產生 dist\。
set -u
R="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)"
TMP="${TMPDIR:-/tmp}/pzpatch-install-gate-test"
pass=0; fail=0

check() {  # check "名稱" 期望值 實際值
    if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass+1))
    else echo "FAIL  $1（期望 $2，實際 $3）"; fail=$((fail+1)); fi
}

[ -f "$R/dist/install.sh" ] || { echo "[中止] 找不到 $R/dist——請先跑 build.ps1" >&2; exit 1; }
[ -f "$R/work/projectzomboid.jar" ] || { echo "[中止] 找不到 $R/work/projectzomboid.jar" >&2; exit 1; }

rm -rf "$TMP"; mkdir -p "$TMP/serverfiles/java"
cp "$R/work/projectzomboid.jar" "$TMP/serverfiles/java/projectzomboid.jar"
export PZ_SERVERFILES="$TMP/serverfiles"
expected=$(grep -c . "$R/dist/manifest.txt")

echo "== A) 乾淨安裝 =="
bash "$R/dist/install.sh" >/dev/null 2>&1; check "乾淨安裝成功" 0 $?
check "安裝後 manifest 筆數＝建置產物" "$expected" \
      "$(grep -c . "$TMP/serverfiles/java/patch-manifest.txt" 2>/dev/null || echo 0)"
[ -e "$TMP/serverfiles/java/zombie/iso/IsoGridSquare.class" ] && r=1 || r=0
check "已退役的 IsoGridSquare.class 不在安裝產物內" 0 "$r"

echo "== B) uninstall 還原 =="
bash "$R/dist/uninstall.sh" >/dev/null 2>&1; check "uninstall 成功" 0 $?
check "uninstall 後零殘留 class" 0 "$(find "$TMP/serverfiles/java" -name '*.class' | wc -l)"

echo "== C) 舊 manifest 遺失、退役 class 殘留（第 3 閘應 fail-closed）=="
mkdir -p "$TMP/serverfiles/java/zombie/iso"
echo "stale-redirected-caller" > "$TMP/serverfiles/java/zombie/iso/IsoGridSquare.class"
bash "$R/dist/install.sh" >/dev/null 2>&1; check "殘留退役 class 時中止" 1 $?
[ -e "$TMP/serverfiles/java/patch-manifest.txt" ] && r=1 || r=0
check "中止後未寫入 manifest（沒有部分安裝）" 0 "$r"

echo "== D) ALLOW_FOREIGN_LOOSE=1 明示放行 =="
ALLOW_FOREIGN_LOOSE=1 bash "$R/dist/install.sh" >/dev/null 2>&1
check "明示放行後安裝成功" 0 $?

rm -rf "$TMP"
echo "---- $pass passed, $fail failed ----"
[ "$fail" -eq 0 ]
