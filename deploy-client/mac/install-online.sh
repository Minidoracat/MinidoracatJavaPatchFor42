#!/bin/bash
# Minidoracat client patches for macOS, one-line installer: downloads this release's package, checks its SHA-256 and opens
# the installer's menu (install, remove, status; [L] switches the language):
#   /bin/bash -c "$(curl -fsSL https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases/latest/download/install-mac.sh)"
# Words after the closing parenthesis go to the installer instead (status, uninstall --all, --lang en).
# build-client.ps1 fills in the @...@ values; the zip and this script come from the same build.
# Everything is inside main and only the last line calls it, so a download cut off halfway runs nothing.
main() {
    # /bin/bash -c "$(curl ...)" status puts the first word in $0 (also an option such as --lang)
    case "$0" in -* | menu | status | install | uninstall | quick) set -- "$0" "$@" ;; esac
    [ $# -eq 0 ] && set -- menu
    # same language as the installer: Java takes it from the first preferred language; --lang zh/en wins
    case "$(defaults read -g AppleLanguages 2> /dev/null | sed -n 2p)" in *zh*) ZH=1 ;; *) ZH= ;; esac
    PREV=
    for A in "$@"; do
        if [ "$PREV" = --lang ]; then case "$A" in zh) ZH=1 ;; en) ZH= ;; esac; fi
        PREV="$A"
    done
    say() { if [ -n "$ZH" ]; then echo "$1"; else echo "$2"; fi; }
    VERSION='@PACKAGE_VERSION@'
    ZIP='@ZIP_NAME@'
    SHA='@ZIP_SHA256@'
    URL="https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases/download/client-v$VERSION/$ZIP"
    fail() { echo; say "[錯誤] $1" "[Error] $2"; exit 6; }
    echo "Minidoracat Client Patches $VERSION (Project Zomboid @GAME_VERSION@)"
    T="$(mktemp -d)" || fail "無法建立暫存資料夾。" "Cannot create a temporary folder."
    trap 'rm -rf "$T"' EXIT
    say "下載中：$ZIP" "Downloading $ZIP"
    curl -fsSL -o "$T/$ZIP" "$URL" || fail "下載失敗，請檢查網路後再貼一次指令。" "Download failed. Check your connection and paste the command again."
    if command -v shasum > /dev/null 2>&1; then GOT="$(shasum -a 256 "$T/$ZIP" | awk '{print $1}')"
    else GOT="$(sha256sum "$T/$ZIP" 2> /dev/null | awk '{print $1}')"; fi
    [ -n "$GOT" ] || fail "這台電腦沒有 shasum，無法檢查下載的檔案。請改用發布頁的 zip 安裝。" "shasum is missing, so the download cannot be checked. Use the zip from the release page instead."
    [ "$GOT" = "$SHA" ] || fail "下載的檔案不完整或和這一版不符（SHA-256 $GOT，應為 $SHA）。過幾分鐘再試一次。" \
        "The download is incomplete or does not belong to this release (SHA-256 $GOT, expected $SHA). Try again in a few minutes."
    unzip -q "$T/$ZIP" -d "$T/pkg" || fail "解壓失敗。" "Cannot extract the package."
    bash "$T/pkg/Install-Patches.command" "$@"
    exit $?
}
main "$@"
