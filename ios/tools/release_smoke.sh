#!/bin/sh
# The IPA is a Release build, with none of the test hooks, and nothing else ever runs that configuration before his phone does. So: a
# Release build for the simulator starts, stays up, and shows its home with the real MapKit search and a real sweep of this machine's
# own small networks. Run from ios/ after capture_simulator.py, on its simulator.
set -eu

UDID=$(cat evidence/simulator-udid.txt)
BUNDLE=com.abdllh.amri

xcodebuild -project Amri.xcodeproj -scheme Amri -configuration Release -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath DerivedDataRelease CODE_SIGNING_ALLOWED=NO build > evidence/release-build.txt 2>&1 || {
    grep -E 'error:|BUILD ' evidence/release-build.txt | head -20
    exit 1
  }
APP=DerivedDataRelease/Build/Products/Release-iphonesimulator/Amri.app
test -d "$APP/PlugIns/AmriShare.appex"
if strings "$APP/Amri" | grep -q -- '-fakeUnit'; then
  echo "a test hook is in the Release build"
  exit 1
fi

xcrun simctl terminate "$UDID" "$BUNDLE" 2>/dev/null || true
xcrun simctl uninstall "$UDID" "$BUNDLE"
xcrun simctl install "$UDID" "$APP"
xcrun simctl privacy "$UDID" grant location "$BUNDLE"
xcrun simctl launch "$UDID" "$BUNDLE" -AppleLanguages "(ar)" -AppleLocale ar_SA
sleep 25
xcrun simctl spawn "$UDID" launchctl list | grep "$BUNDLE"
xcrun simctl io "$UDID" screenshot --type=png evidence/screens/release-home.png
echo "The Release build runs."
