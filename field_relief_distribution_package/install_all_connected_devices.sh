#!/usr/bin/env bash
# ==============================================================================
# DisasterManagement v1.2.0 - Batch Field ADB Installer
# Automatically detects and installs the release APK on all attached Android devices.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_FILE="${SCRIPT_DIR}/DisasterManagement-v1.2.0-signed.apk"

echo "=================================================================="
echo "🛡️ DisasterManagement Batch Field Device Deployment"
echo "=================================================================="

if [ ! -f "$APK_FILE" ]; then
    echo "❌ Error: APK binary not found at $APK_FILE"
    exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
    echo "❌ Error: 'adb' tool is not installed or not in PATH."
    echo "Please install Android Platform Tools on this workstation."
    exit 1
fi

echo "Scanning for connected Android devices via USB / Wi-Fi..."
devices=$(adb devices | awk 'NR>1 {print $1}' | grep -v 'offline' | grep -v '^$' || true)

if [ -z "$devices" ]; then
    echo "⚠️  No authorized Android devices detected."
    echo "Ensure devices are connected via USB with 'USB Debugging' enabled."
    exit 0
fi

count=0
for dev in $devices; do
    echo "------------------------------------------------------------------"
    echo "📲 Deploying to device: $dev"
    if adb -s "$dev" install -r -d "$APK_FILE"; then
        echo "✅ Successfully installed on $dev"
        count=$((count + 1))
    else
        echo "❌ Failed to install on $dev"
    fi
done

echo "=================================================================="
echo "🎯 Deployment complete. Total devices successfully flashed: $count"
echo "=================================================================="
