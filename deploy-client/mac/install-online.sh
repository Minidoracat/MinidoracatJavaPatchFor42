#!/bin/bash
# Minidoracat client patches for macOS, one-line installer: downloads this release's package, checks its SHA-256, runs it.
#   /bin/bash -c "$(curl -fsSL https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases/latest/download/install-mac.sh)"
# build-client.ps1 fills in the @...@ values; the zip and this script come from the same build.
# Everything is inside main and only the last line calls it, so a download cut off halfway runs nothing.
main() {
    # /bin/bash -c "$(curl ...)" menu puts the first word in $0 (also an option such as --lang)
    case "$0" in -* | menu | status | install | uninstall | quick) set -- "$0" "$@" ;; esac
    [ $# -eq 0 ] && set -- quick
    VERSION='@PACKAGE_VERSION@'
    ZIP='@ZIP_NAME@'
    SHA='@ZIP_SHA256@'
    URL="https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases/download/client-v$VERSION/$ZIP"
    fail() { echo; echo "[錯誤] $1"; echo "[Error] $2"; exit 6; }
    echo "Minidoracat Client Patches $VERSION (Project Zomboid @GAME_VERSION@)"
    T="$(mktemp -d)" || fail "無法建立暫存資料夾。" "Cannot create a temporary folder."
    trap 'rm -rf "$T"' EXIT
    echo "下載中 / Downloading $ZIP"
    curl -fsSL -o "$T/$ZIP" "$URL" || fail "下載失敗，請檢查網路後再貼一次指令。" "Download failed. Check your connection and paste the command again."
    if command -v shasum > /dev/null 2>&1; then GOT="$(shasum -a 256 "$T/$ZIP" | awk '{print $1}')"
    else GOT="$(sha256sum "$T/$ZIP" 2> /dev/null | awk '{print $1}')"; fi
    [ -n "$GOT" ] || fail "這台電腦沒有 shasum，無法檢查下載的檔案。請改用發布頁的 zip 安裝。" "shasum is missing, so the download cannot be checked. Use the zip from the release page instead."
    [ "$GOT" = "$SHA" ] || fail "下載的檔案不完整或和這一版不符（SHA-256 $GOT，應為 $SHA）。過幾分鐘再試一次。" \
        "The download is incomplete or does not belong to this release (SHA-256 $GOT, expected $SHA). Try again in a few minutes."
    unzip -q "$T/$ZIP" -d "$T/pkg" || fail "解壓失敗。" "Cannot extract the package."
    bash "$T/pkg/Install-Patches.command" "$@"
    CODE=$?
    if [ "$CODE" = 0 ] && [ "$1" = quick ]; then
        echo; echo "要移除、看狀態或改選項：同一行指令最後加一個空格和 menu。"
        echo "To remove, see the status or change options: add a space and menu to the end of the same command."
    fi
    exit "$CODE"
}
main "$@"
