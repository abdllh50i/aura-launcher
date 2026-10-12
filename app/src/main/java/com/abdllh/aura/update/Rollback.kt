package com.abdllh.aura.update

import android.content.Context
import com.abdllh.aura.music.BtTiming
import com.abdllh.aura.system.LocalAdb
import java.io.File

/** The beta carries the preceding signed APK, so returning to it also works without internet. */
object Rollback {
    const val VERSION = "1.6.0"
    const val SHA256 = "ee1d60c3be1a949a9e5237856dfced6f533595d10230e1c29d7ef9ae88d95caf"
    private const val ASSET = "rollback/aura-1.6.0.apk"
    private const val DIR = "/data/local/tmp/amri-rollback"

    fun available(ctx: Context) = try {
        ctx.assets.open(ASSET).use { }
        UpdateManager.currentVersion > Version.parse(VERSION)!!
    } catch (_: Throwable) { false }

    /** Extract and flush the packaged backup. Signature/package/version are checked by UpdateManager as well. */
    fun extract(ctx: Context): File {
        val f = File(ctx.filesDir, "rollback-$VERSION.apk")
        if (!f.isFile || GitHub.sha256(f) != SHA256) {
            if (ctx.filesDir.usableSpace < 20L * 1024 * 1024) throw UpdateException(UpdateException.Kind.STORAGE, "Rollback needs 20 MB")
            ctx.assets.open(ASSET).use { input -> f.outputStream().use { out -> input.copyTo(out); out.fd.sync() } }
        }
        if (GitHub.sha256(f) != SHA256) throw UpdateException(UpdateException.Kind.HASH, "Rollback APK hash mismatch")
        return f
    }

    /** Detach the install from this process: replacing the APK kills AMRI, but must not kill the installer. */
    fun install(ctx: Context, apk: File) {
        BtTiming.prepareForRollback()
        val script = File(ctx.filesDir, "rollback-install.sh")
        script.writeText("""
            #!/system/bin/sh
            pm install -r -d $DIR/previous.apk > $DIR/result 2>&1
            code=${'$'}?
            echo "EXIT=${'$'}code" >> $DIR/result
            if [ "${'$'}code" = 0 ]; then
                sync
                sleep 1
                reboot
                # If firmware refuses the reboot, still open the installed stable app.
                am start -n com.abdllh.aura/.home.HomeActivity >/dev/null 2>&1
            fi
        """.trimIndent() + "\n")
        val r = LocalAdb.run("""
            [ "${'$'}(id -u)" = 0 ] || exit 1
            mkdir -p $DIR || exit 1
            cp '${apk.absolutePath}' $DIR/previous.apk && chmod 644 $DIR/previous.apk || exit 1
            [ "${'$'}(sha256sum $DIR/previous.apk | cut -d ' ' -f 1)" = '$SHA256' ] || exit 1
            cp '${script.absolutePath}' $DIR/install.sh && chmod 700 $DIR/install.sh || exit 1
            rm -f $DIR/result
            nohup sh $DIR/install.sh > $DIR/runner.log 2>&1 < /dev/null &
            echo started
        """.trimIndent(), 15000)
        if (!r.ok || !r.output.endsWith("started")) throw UpdateException(UpdateException.Kind.INSTALL, r.output)
        // Success replaces this process. If it remains, surface a failed install instead of spinning forever.
        repeat(90) {
            Thread.sleep(1000)
            val result = LocalAdb.run("cat $DIR/result 2>/dev/null", 5000).output
            if (result.contains("EXIT=")) {
                if (result.contains("EXIT=0")) return
                throw UpdateException(UpdateException.Kind.INSTALL, result.take(300))
            }
        }
        throw UpdateException(UpdateException.Kind.TIMEOUT, "Rollback install timed out")
    }
}
