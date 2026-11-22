#!/bin/bash
# Minidoracat client patches for macOS: finds Project Zomboid.app and runs the installer with the game's own Java.
# Usage (Terminal): bash <drag this file here> [menu|status|install|quick|uninstall] [--memory-mb N] [--lang en] ...
# bash 3.2 (macOS default): no associative arrays, mapfile or ${x,,}.

DIR="$(cd "$(dirname "$0")" && pwd)"
INSTALLER="$DIR/mdc-mac-installer.jar"
STEAM="$HOME/Library/Application Support/Steam"

fail() {
    # $1 exit code, $2 Chinese, $3 English
    echo
    echo "[錯誤] $2"
    echo "[Error] $3"
    if [ -t 0 ] && [ -z "$HAS_ARGS" ]; then
        read -r -p "按 Return 關閉 / Press Return to close " _
    fi
    exit "$1"
}

[ $# -gt 0 ] && HAS_ARGS=1

if [ ! -f "$INSTALLER" ]; then
    fail 6 "這個檔案旁邊找不到 mdc-mac-installer.jar。請把整個 zip 重新解壓，檔案放在一起。" \
        "mdc-mac-installer.jar is missing next to this file. Extract the whole zip again and keep the files together."
fi

is_app() {
    [ -f "$1/Contents/Java/projectzomboid.jar" ] && [ -f "$1/Contents/Info.plist" ]
}

APP=""
try_app() {
    if [ -z "$APP" ] && is_app "$1"; then APP="$1"; fi
}

if [ -n "$PZ_APP" ]; then
    try_app "$PZ_APP"
    try_app "$PZ_APP/Project Zomboid.app"
fi
try_app "$STEAM/steamapps/common/ProjectZomboid/Project Zomboid.app"
VDF="$STEAM/steamapps/libraryfolders.vdf"
if [ -z "$APP" ] && [ -f "$VDF" ]; then
    while IFS= read -r LIB; do
        try_app "$LIB/steamapps/common/ProjectZomboid/Project Zomboid.app"
    done <<EOF
$(sed -n 's/^[[:space:]]*"path"[[:space:]]*"\(.*\)"[[:space:]]*$/\1/p' "$VDF" | sed 's/\\\\/\\/g')
EOF
fi

while [ -z "$APP" ]; do
    echo
    echo "找不到遊戲。請把 Project Zomboid.app（或放著它的資料夾）拖進這個視窗，再按 Return；直接按 Return 離開。"
    echo "The game was not found. Drag Project Zomboid.app (or the folder that has it) into this window and press Return; press Return alone to quit."
    echo "（也可以用 PZ_APP 環境變數指定 / or set PZ_APP to its path）"
    # stdin 讀到底（非互動執行）時 read 失敗 → 結束代碼 3
    read -r INPUT || exit 3
    [ -z "$INPUT" ] && exit 3
    # 拖進來的路徑會把空白等字元加上反斜線，也可能帶引號與結尾空白
    INPUT="$(printf '%s' "$INPUT" | sed -e 's/[[:space:]]*$//' -e "s/^'\(.*\)'\$/\1/" -e 's/^"\(.*\)"$/\1/' -e 's/\\\(.\)/\1/g')"
    try_app "$INPUT"
    try_app "$INPUT/Project Zomboid.app"
    [ -z "$APP" ] && echo "這裡不是 Project Zomboid.app / Not Project Zomboid.app: $INPUT"
done

JAVA=""
for ARCH in aarch64 x86_64; do
    CANDIDATE="$APP/Contents/PlugIns/jre-$ARCH/Contents/Home/bin/java"
    if [ -z "$JAVA" ] && [ -x "$CANDIDATE" ] && "$CANDIDATE" -version >/dev/null 2>&1; then
        JAVA="$CANDIDATE"
    fi
done
if [ -z "$JAVA" ]; then
    fail 3 "遊戲裡找不到能執行的 Java（Contents/PlugIns/jre-*）。請用 Steam 驗證遊戲檔案的完整性後再試。" \
        "No working Java was found in the game (Contents/PlugIns/jre-*). Verify the game files with Steam and try again."
fi

RAM_GB=0
MEM="$(sysctl -n hw.memsize 2>/dev/null)"
[ -n "$MEM" ] && RAM_GB=$((MEM / 1073741824))

exec "$JAVA" -jar "$INSTALLER" --app "$APP" --package-dir "$DIR" --ram-gb "$RAM_GB" "$@"
