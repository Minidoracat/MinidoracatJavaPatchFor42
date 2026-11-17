#!/bin/bash
# Install-Patches.command 在假的 HOME、Steam 資料庫與 .app 上跑（路徑含空白、括號、中文）：
# 找遊戲的順序（PZ_APP → Steam 預設 → libraryfolders.vdf → 拖進視窗）、Java 選擇（aarch64 跑不動就換 x86_64）、
# 交給安裝器的參數，以及缺檔、找不到遊戲、找不到 Java 的結束代碼。假 java 只記下收到的參數。
# 用法：bash command-test.sh <pkg-mac 資料夾> <工作資料夾>
set -u
PKG="$1"
W="$2"
rm -rf "$W"
mkdir -p "$W"
W="$(cd "$W" && pwd)" # 從 pwsh 呼叫時是 D:\...，反斜線會被當成拖曳路徑的跳脫字元
P="$W/下載 (zip)/pkg"
mkdir -p "$P"
cp "$PKG"/* "$P/"
P="$(cd "$P" && pwd)" # 啟動腳本用 pwd 算自己的資料夾（Git Bash 下是 /d/... 形式）
ARGS_OUT="$W/args.txt"
export ARGS_OUT
FAILS=0

fail() { echo "FAIL: $*"; FAILS=$((FAILS + 1)); }

make_app() { # $1 .app 路徑、$2 aarch64 能不能跑
    mkdir -p "$1/Contents/Java" "$1/Contents/PlugIns/jre-aarch64/Contents/Home/bin" "$1/Contents/PlugIns/jre-x86_64/Contents/Home/bin"
    : > "$1/Contents/Java/projectzomboid.jar"
    : > "$1/Contents/Info.plist"
    for ARCH in aarch64 x86_64; do
        J="$1/Contents/PlugIns/jre-$ARCH/Contents/Home/bin/java"
        if [ "$ARCH" = aarch64 ] && [ "$2" = no ]; then
            printf '#!/bin/bash\nexit 1\n' > "$J"
        else
            printf '#!/bin/bash\n[ "$1" = -version ] && exit 0\nprintf "%%s\\n" "$0" "$@" > "$ARGS_OUT"\n' > "$J"
        fi
        chmod +x "$J"
    done
}

run() { # $1 期望的結束代碼，其餘是 HOME 與參數；stdin 由呼叫端給
    local want="$1" home="$2"
    shift 2
    rm -f "$ARGS_OUT"
    HOME="$home" bash "$P/Install-Patches.command" "$@" > "$W/out.txt" 2>&1
    local got=$?
    [ "$got" = "$want" ] || { fail "exit $got != $want for $*"; cat "$W/out.txt"; }
}

expect_args() { # 期望交給 java 的參數（逐行）
    local want
    want="$(printf '%s\n' "$@")"
    [ -f "$ARGS_OUT" ] || { fail "java not called"; return; }
    [ "$(tail -n +2 "$ARGS_OUT")" = "$want" ] || { fail "java args differ"; diff <(tail -n +2 "$ARGS_OUT") <(printf '%s\n' "$@"); }
}

# 1) 遊戲在 libraryfolders.vdf 列的第二個資料庫；aarch64 的 java 跑不動 → 用 x86_64
H="$W/home 家"
LIB="$W/Steam Lib (外接)"
APP="$LIB/steamapps/common/ProjectZomboid/Project Zomboid.app"
make_app "$APP" no
mkdir -p "$H/Library/Application Support/Steam/steamapps"
printf '"libraryfolders"\n{\n\t"0"\n\t{\n\t\t"path"\t\t"%s"\n\t}\n\t"1"\n\t{\n\t\t"path"\t\t"%s"\n\t\t"label"\t\t""\n\t}\n}\n' \
    "$W/nowhere" "$LIB" > "$H/Library/Application Support/Steam/steamapps/libraryfolders.vdf"
run 0 "$H" status --lang en < /dev/null
RAM="$(sed -n '9p' "$ARGS_OUT")"
expect_args -jar "$P/mdc-mac-installer.jar" --app "$APP" --package-dir "$P" --ram-gb "$RAM" status --lang en
case "$RAM" in '' | *[!0-9]*) fail "ram-gb not a number: $RAM" ;; esac
grep -q 'jre-x86_64' "$ARGS_OUT" || fail "x86_64 java not chosen"

# 2) PZ_APP（遊戲資料夾）優先於 Steam；aarch64 可用時先用它
OTHER="$W/other place/ProjectZomboid"
make_app "$OTHER/Project Zomboid.app" yes
PZ_APP="$OTHER" run 0 "$H" < /dev/null
expect_args -jar "$P/mdc-mac-installer.jar" --app "$OTHER/Project Zomboid.app" --package-dir "$P" --ram-gb "$RAM"
grep -q 'jre-aarch64' "$ARGS_OUT" || fail "aarch64 java not chosen"

# 3) 找不到遊戲：拖進視窗（Terminal 會加反斜線與結尾空白）；空輸入或讀到底 → 3
EMPTY="$W/empty home"
mkdir -p "$EMPTY"
ESCAPED="$(printf '%s' "$OTHER/Project Zomboid.app" | sed 's/[ ()]/\\&/g') "
printf 'not/a/game\n%s\n' "$ESCAPED" | run 0 "$EMPTY" install
expect_args -jar "$P/mdc-mac-installer.jar" --app "$OTHER/Project Zomboid.app" --package-dir "$P" --ram-gb "$RAM" install
grep -q 'Not Project Zomboid.app: not/a/game' "$W/out.txt" || fail "bad drag input not reported"
printf "'%s'\n" "$OTHER" | run 0 "$EMPTY"
expect_args -jar "$P/mdc-mac-installer.jar" --app "$OTHER/Project Zomboid.app" --package-dir "$P" --ram-gb "$RAM"
printf '\n' | run 3 "$EMPTY"
run 3 "$EMPTY" < /dev/null

# 4) 遊戲裡沒有能跑的 Java → 3
BROKEN="$W/broken/Project Zomboid.app"
make_app "$BROKEN" no
printf '#!/bin/bash\nexit 1\n' > "$BROKEN/Contents/PlugIns/jre-x86_64/Contents/Home/bin/java"
PZ_APP="$BROKEN" run 3 "$H" status < /dev/null

# 5) 安裝器 jar 不在旁邊 → 6
rm "$P/mdc-mac-installer.jar"
run 6 "$H" status < /dev/null

if [ "$FAILS" -ne 0 ]; then
    echo "command-test: $FAILS failure(s)"
    exit 1
fi
echo "command-test OK"
