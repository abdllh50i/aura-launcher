package com.abdllh.aura.system

import android.content.Context
import android.util.Log

/**
 * Debug builds only (R8 drops all of this from a release): the data rule of the way back, checked instead of hoped for.
 *
 * Going back to 1.19 means 1.19's code starts on what the newer version left. `Prefs.init` reads "accent" with getInt in
 * AuraApp.onCreate: the same key stored as anything else throws there, on every start, and the way back has bricked the
 * home screen. So a key in these four files never changes type. New settings go in new files, and may be added here.
 *
 * The table is what 1.19.0 reads and writes (scripts/prefkeys.py lists it from the sources of any tree). A debug build
 * that finds a stored value of another type stops at once, with the key in the message: fails on the bench, or never.
 * Keys that are not in the table only get a line in the log (old versions left some, and 1.19 does not read them).
 */
object PrefTypes {
    private const val TAG = "AuraPrefTypes"

    private val TABLE: Map<String, Map<String, String>> = mapOf(
        "aura_crash" to mapOf("crashes" to "s", "crashDisabled" to "b"),
        "aura_mapguard" to mapOf("fails" to "i", "starting" to "b"),
        "aura_music" to mapOf("shuffle" to "b", "repeat" to "s", "keyRouting" to "b", "queue" to "s", "index" to "i", "pos" to "l"),
        "aura" to table(
            "b" to "adbBridge amriOn amriSpeak askedLocation auraRing betaLeftoversGone btCoverOnline btDelayHinted builtInMaps callCard " +
                "circlesOff1 clock24 dockLabels fullOffOnAccOff handbrakeFake hideAndroidNotices hideStatusBar lyricsListen lyricsWords " +
                "markCameraButton musicKeysAura musicLyrics musicLyricsFull navVoice removedRequested1 replaceStockMusic safeModeNotice " +
                "pressTick screenAnimations setupBannerDismissed setupDone showMessageText showPhoneMessages stockMusicWasDisabled updateAuto " +
                "updateBeta volumeHud wifiKeeper wifiOnStart zlinkGuard zlinkWifiWasOn",
            "i" to "accOffModeSent accent amriMic amriSens bootAnimCheckedFor btDelayMs circlesOffTries handbrakeSeen removedRequestedTries " +
                "zlinkOrigStyle",
            "l" to "lastUpdateAttempt lastUpdateCheck mapExtrasFetched offlineMapUpdated ofmTileTemplateAt powerOffAskedAt",
            "f" to "lastLat lastLon",
            "s" to "apiBase auraRingInstalled availableTag barsReplacedStatus bootAnimInstalledMd5 bootAnimTries callWindowStockOff coverNote " +
                "dockPins homeAddress homeGuardNote lang lyricsHeard lyricsServer lyricsServerKey musicKeyNote musicKeyRulesOut musicSource " +
                "musicSourceOwn navPackage nwdBootId offlineMapState ofmTileTemplate phoneLinkNote placeHome placeRecents placeWork " +
                "powerOffAskedBoot releaseCache stockMusicApplied theme updateRepo volumeHudStockOff wifiOnStartBoot workAddress ytNote zlinkSession",
            "ss" to "barsAdded hiddenApps"
        )
    )

    private fun table(vararg groups: Pair<String, String>): Map<String, String> {
        val m = HashMap<String, String>()
        for ((type, keys) in groups) for (k in keys.split(' ')) m[k] = type
        return m
    }

    private fun typeOf(v: Any?): String = when (v) {
        is Boolean -> "b"
        is Int -> "i"
        is Long -> "l"
        is Float -> "f"
        is String -> "s"
        is Set<*> -> "ss"
        else -> "?"
    }

    /** Throws when a stored value does not have the type 1.19 reads it with. */
    fun check(c: Context) {
        for ((file, types) in TABLE) {
            for ((key, value) in c.getSharedPreferences(file, Context.MODE_PRIVATE).all) {
                val want = types[key]
                if (want == null) { Log.i(TAG, "$file: '$key' is not one of 1.19's keys"); continue }
                val have = typeOf(value)
                if (have != want) {
                    throw IllegalStateException("Pref '$key' in $file is stored as '$have' but 1.19 reads it as '$want': " +
                        "1.19 would crash at its start after a rollback. Existing keys never change type (docs/DEV.md).")
                }
            }
        }
    }
}
