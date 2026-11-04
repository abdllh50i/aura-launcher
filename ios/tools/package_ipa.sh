#!/bin/sh
# Turns the Release build into the IPAs for Sideloadly. Compiled with CODE_SIGNING_ALLOWED=NO, as Naghm's is, so nothing in
# it carries entitlements until here. AltStore and likely Sideloadly read them from the binary's signature, so each piece is
# signed ad hoc with its entitlements. The App Group's name in those files may be rewritten by the tool: the app reads the
# real one from embedded.mobileprovision at run time.
#
# Two files come out, from the same build:
#   Amri-unsigned.ipa          asks for the App Group: the extension can read the pairing
#   Amri-nogroup-unsigned.ipa  asks for nothing, in case a free Apple ID or the tool refuses the group. The app still works:
#                              the extension sends with no token, and the unit shows a card to confirm (section 5.6).
set -eu
cd "$(dirname "$0")/.."

APP=DerivedData/Build/Products/Release-iphoneos/Amri.app
test -d "$APP" || { echo "no Release build at $APP"; exit 1; }
test -d "$APP/PlugIns/AmriShare.appex" || { echo "the share extension is not in the app"; exit 1; }

rm -rf Payload Payload-nogroup Amri-unsigned.ipa Amri-nogroup-unsigned.ipa
mkdir Payload Payload-nogroup
cp -R "$APP" Payload/
cp -R "$APP" Payload-nogroup/

codesign -f -s - --generate-entitlement-der --entitlements Support/Share.entitlements Payload/Amri.app/PlugIns/AmriShare.appex
codesign -f -s - --generate-entitlement-der --entitlements Support/App.entitlements Payload/Amri.app
codesign -f -s - Payload-nogroup/Amri.app/PlugIns/AmriShare.appex
codesign -f -s - Payload-nogroup/Amri.app

ditto -c -k --sequesterRsrc --keepParent Payload Amri-unsigned.ipa
mv Payload-nogroup Payload-tmp
mv Payload Payload-group
mv Payload-tmp Payload
ditto -c -k --sequesterRsrc --keepParent Payload Amri-nogroup-unsigned.ipa
rm -rf Payload
mv Payload-group Payload

echo "--- what the signature says"
codesign -d --entitlements - Payload/Amri.app 2>&1 | grep -E 'Executable|application-groups|group\.' || true
codesign -d --entitlements - Payload/Amri.app/PlugIns/AmriShare.appex 2>&1 | grep -E 'Executable|application-groups|group\.' || true
echo "--- the bundle"
/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' Payload/Amri.app/Info.plist
/usr/libexec/PlistBuddy -c 'Print :CFBundleDisplayName' Payload/Amri.app/Info.plist
/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' Payload/Amri.app/PlugIns/AmriShare.appex/Info.plist
/usr/libexec/PlistBuddy -c 'Print :CFBundleDisplayName' Payload/Amri.app/PlugIns/AmriShare.appex/Info.plist
/usr/libexec/PlistBuddy -c 'Print :NSExtension:NSExtensionAttributes:NSExtensionActivationRule' Payload/Amri.app/PlugIns/AmriShare.appex/Info.plist
/usr/libexec/PlistBuddy -c 'Print :NSExtension:NSExtensionPrincipalClass' Payload/Amri.app/PlugIns/AmriShare.appex/Info.plist
/usr/libexec/PlistBuddy -c 'Print :NSLocalNetworkUsageDescription' Payload/Amri.app/Info.plist
lipo -archs Payload/Amri.app/Amri
ls -l Amri-unsigned.ipa Amri-nogroup-unsigned.ipa
unzip -l Amri-unsigned.ipa | grep -E 'AmriShare$|Amri$|lproj/' | head -20
