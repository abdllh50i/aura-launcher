# K2501 Bluetooth timing experiment (1.6.1 beta)

The NWD Bluetooth UI (`com.nwd.bt.music`) gets its data from BC03
(`com.bt.bc03`), which uses `/system/bin/gocsdk_8800` on this unit. The stock
AIC8800 daemon implements AVRCP GetPlayStatus responses, but its AT#VI handler
only returns cached status and its MQ report template is missing. The UI APK
alone cannot fix the request.

`query.S` replaces AT#VI with an AV/C STATUS / AVRCP GetPlayStatus request
(PDU 0x30). It also supplies `MQ[playstatus_data]` to the existing reply sender.
BC03 receives duration and position in milliseconds, then delivers whole
seconds through BTPIMCallback transaction 4. Unknown AVRCP values are discarded
before BC03 can round them to zero. AMRI prefers this report over estimated
timing and polls every two seconds while the phone is connected.
The original VI cached-state callback and acknowledgement still run after the request.

## Supported driver and recovery

Only this stock SHA-256 is accepted; a different driver is left untouched:

`8e2fbf23d24021e5b57ef0dc5df851570ccad621b0f8cb82433d3090ce010ce7`

The JSON delta is bundled. AMRI prepares a copy under `/data`, verifies its
digest, bind mounts it over the system executable and restarts Bluetooth once.
The executable on the system partition is not overwritten. Turning off
**Settings → Software update → Song time from the phone** removes the mount
and restarts the original daemon. Reboot clears the mount; AMRI reapplies it
only when the switch is on. Three consecutive command failures disable the
experiment and attempt restoration.

**Return to 1.6.0** removes the overlay before a root `pm install -r -d` of
the previous APK. Backup digest, package, signing certificate and version are
checked first. Installation is detached because replacing AMRI ends its
process. Data is retained, and beta updates are switched off on return.
The preceding APK is included, so internet is unnecessary. Both operations
require the root local ADB service already used by this ROM.

## Build

```powershell
py -3 tools/bt-timing/make_patch.py --stock PATH_TO_ORIGINAL_GOC --ndk PATH_TO_NDK
py -3 tools/bt-timing/prepare_rollback.py # or --apk PATH_TO_SIGNED_1.6.0_APK
.\gradlew.bat :app:assembleRelease '-PappVersionName=1.6.1-beta.1'
```

The generator uses Windows LLVM tools from Android NDK. The delta is committed;
the manufacturer's executable and preceding APK are not. The beta's Gradle
build fails if the verified backup is missing or modified.

## Verification and limits

```powershell
py -3 -m pip install --target PATH_TO_TEST_DEPS unicorn pyelftools
py -3 tools/bt-timing/test_patch.py --stock PATH_TO_ORIGINAL_GOC --deps PATH_TO_TEST_DEPS
```

The ARM test executes the actual request hook and original AV/C packet builder
at two ASLR bases. It checks the outgoing packet, disconnected guard, response
adapter, MQ duration/position, unknown values and binary digests. Allocation,
message delivery and UI string helpers are substituted. It does not simulate
the physical radio or an iPhone. Device compatibility, response behavior and
Bluetooth reconnection still need a test on the owner's unit.
