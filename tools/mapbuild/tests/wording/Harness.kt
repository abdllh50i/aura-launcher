package com.abdllh.aura.nav

import java.io.File

/**
 * Runs the app's real Instructions.kt over manoeuvres taken from real answers (cases.tsv from run_wording.py) and writes what it says.
 * Compiled together with nav/Instructions.kt itself and a Stubs.kt cut out of nav/Router.kt, so this is the app's own wording, not a copy.
 */
fun main(args: Array<String>) {
    val out = File(args[1]).printWriter(Charsets.UTF_8)
    File(args[0]).readLines(Charsets.UTF_8).drop(1).forEach { line ->
        val c = line.split('\t')
        val s = Step(c[0], c[1], c[2].toIntOrNull() ?: 0, c[3], c[4], LatLon(0.0, 0.0), 300.0, 10.0, destinations = c[5], exits = c[6])
        val row = listOf(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7],
            Instructions.action(s, true), Instructions.action(s, false),
            Instructions.text(s, true), Instructions.text(s, false),
            Instructions.spoken(s, 300.0, true), Instructions.roadLine(s, true),
            Instructions.turn(s).name)
        out.println(row.joinToString("\t"))
    }
    out.close()
}
