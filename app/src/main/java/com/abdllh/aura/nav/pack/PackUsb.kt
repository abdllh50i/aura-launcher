package com.abdllh.aura.nav.pack

import android.content.Context
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.system.LocalAdb
import com.abdllh.aura.system.SystemProps
import java.io.File

/**
 * A stick or a card with a pack on it: `<mount>/amri-maps/manifest.json` (scripts/make-usb-pack.ps1 writes that). Where the firmware mounts them: vendor's fstab names
 * vold labels udisk, udiskh, udisk3, udisk4 and card, which land as /mnt/media_rw/udisk1, udiskh1, udisk31, udisk4, card1 (stock launcher's strings), with
 * /storage/udisk4 beside them and /mnt/udisk, /mnt/udiskh, /mnt/udisk3, /mnt/card, /mnt/mapcard as the firmware's own names (com.nwd.app.NwdDeviceConfig, props
 * ro.nwd.usb1.path ...). init.rc makes /mnt/media_rw 0777, so the folder lists, but a mount in it is normally 0770 media_rw: what the process cannot read itself, the
 * root shell looks into. Never seen on the unit: read from the firmware's files.
 */
internal object PackUsb {
    private const val TAG = "AuraPack"
    private const val FOLDER = "amri-maps"
    /** Not a stick: /mnt/media_rw/sdcard is init's link to the internal storage. */
    private val NOT_STICKS = setOf("sdcard", "emulated", "self")

    class Find(val source: PackSource, val folder: String, val manifest: PackManifest?, val refused: PackProblem?)

    /** The newest pack on anything plugged in, or null. Blocking: reads sticks, may ask the root shell (up to a few seconds). */
    fun scan(ctx: Context): Find? {
        val app = PackPaths.appCode(ctx)
        val found = ArrayList<Find>()
        val seen = HashSet<String>()
        for (m in mounts()) {
            val dir = File(m, FOLDER)
            if (!File(dir, "manifest.json").canRead()) continue
            found.add(read(FileSource(dir), dir.path, app) ?: continue)
            seen.add(dir.canonicalPath)
        }
        if (found.none { it.manifest != null }) rootFolders().forEach { d ->
            if (seen.add(d)) read(RootSource(d), d, app)?.let { found.add(it) }
        }
        return found.filter { it.manifest != null }.maxByOrNull { it.manifest!!.built } ?: found.firstOrNull()
    }

    /** The folder a stopped copy came from, if it is plugged in again (the same stick can have another mount name after a restart). */
    fun sourceFor(ctx: Context, id: String): PackSource? = scan(ctx)?.takeIf { it.manifest?.id == id }?.source

    private fun read(s: PackSource, folder: String, app: Long): Find? = try {
        Find(s, folder, PackManifest.parse(s.manifestText(), app), null)
    } catch (r: PackRefused) {
        Log.i(TAG, "$folder: refused: ${r.message}")
        Find(s, folder, null, r.why)
    } catch (t: Throwable) {
        Log.i(TAG, "$folder: $t")
        null
    }

    private fun mounts(): List<File> {
        val out = LinkedHashSet<File>()
        for (top in listOf("/mnt/media_rw", "/storage")) {
            File(top).listFiles()?.forEach { if (it.name !in NOT_STICKS) out.add(it) }
        }
        for (p in listOf("/mnt/udisk", "/mnt/udiskh", "/mnt/udisk3", "/mnt/card", "/mnt/mapcard")) out.add(File(p))
        for (k in listOf("ro.nwd.usb1.path", "ro.nwd.usb2.path", "ro.nwd.usb3.path", "ro.nwd.mediacard.path", "ro.nwd.mapcard.path")) {
            SystemProps.get(k).takeIf { it.startsWith("/") }?.let { out.add(File(it)) }
        }
        debugRoot()?.let { out.add(File(it)) }
        return out.toList()
    }

    /** A folder standing in for a mount on the emulator, one this process can read (`setprop debug.aura.pack.usb /sdcard/fakeusb`). */
    private fun debugRoot(): String? = if (BuildConfig.DEBUG) SystemProps.get("debug.aura.pack.usb").takeIf { it.startsWith("/") } else null

    private fun rootFolders(): List<String> {
        val r = LocalAdb.run("for d in /mnt/media_rw/* /storage/* /mnt/udisk* /mnt/card /mnt/mapcard; do [ -f \"\$d/$FOLDER/manifest.json\" ] && echo \"\$d/$FOLDER\"; done; true", 8000)
        if (!r.ok) return emptyList()
        return r.output.lines().map { it.trim() }.filter { p -> p.startsWith("/") && NOT_STICKS.none { p.contains("/$it/") } }
    }
}
