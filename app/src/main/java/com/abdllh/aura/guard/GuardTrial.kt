package com.abdllh.aura.guard

import android.content.Context
import com.abdllh.aura.BuildConfig
import java.io.File

/**
 * How far this version has come on this unit (files/guard-trial): starts that went on working for minutes, seconds of
 * use in such starts, and whether any start has reached OK at all. A version is on trial until it has shown three healthy
 * starts and half an hour of use. Only the guard writes the file (it holds its own copy in memory); the main process reads
 * it to know whether this version has earned its place as the way back.
 */
class GuardTrial private constructor(var healthy: Int, var seconds: Int, var lastOkBoot: String, var lastOkPid: Int, var sawOk: Boolean) {
    val neverOk get() = !sawOk
    val passed get() = healthy >= STARTS && seconds >= secondsWanted()

    fun save(c: Context) {
        try {
            File(c.filesDir, FILE).writeText("${BuildConfig.VERSION_CODE} $healthy $seconds $lastOkBoot $lastOkPid ${if (sawOk) 1 else 0}\n")
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val FILE = "guard-trial"
        private const val STARTS = 3
        private const val SECONDS = 30 * 60

        private fun secondsWanted() =
            if (BuildConfig.DEBUG) GuardTest.trialMinutes()?.let { it * 60 } ?: SECONDS else SECONDS

        /** This version's entry; a file left by another version counts for nothing. */
        fun load(c: Context): GuardTrial {
            try {
                val p = File(c.filesDir, FILE).readText().trim().split(' ')
                if (p.size >= 5 && p[0].toIntOrNull() == BuildConfig.VERSION_CODE) {
                    val healthy = p[1].toInt()
                    return GuardTrial(healthy, p[2].toInt(), p[3], p[4].toInt(), healthy > 0 || p.getOrNull(5) == "1")
                }
            } catch (_: Throwable) {
            }
            return GuardTrial(0, 0, "", 0, false)
        }
    }
}
