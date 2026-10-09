package com.abdllh.aura.system

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs a shell command through the unit's own adbd (TCP 5555, root, no authorisation on this firmware: see the ROM
 * notes). Used for the few things a privileged app cannot do through APIs after an in-app update, e.g. disabling the
 * stock music app (privileged permissions added by an update are not granted until the ROM copy requests them).
 * Minimal ADB protocol: CNXN, OPEN "shell:cmd", WRTE/OKAY, CLSE. Blocking: call it off the main thread.
 */
object LocalAdb {
    private const val TAG = "AuraAdb"
    private const val A_CNXN = 0x4E584E43
    private const val A_AUTH = 0x48545541
    private const val A_OPEN = 0x4E45504F
    private const val A_OKAY = 0x59414B4F
    private const val A_CLSE = 0x45534C43
    private const val A_WRTE = 0x45545257
    private const val VERSION = 0x01000000
    private const val MAXDATA = 256 * 1024

    /** Debug builds may point at a test double (fake_adbd.py through the emulator's 10.0.2.2 host alias). */
    @Volatile var host = "127.0.0.1"
    @Volatile var port = 5555

    class Result(val ok: Boolean, val output: String)

    fun run(cmd: String, timeoutMs: Int = 15000): Result {
        var s: Socket? = null
        return try {
            s = Socket()
            s.connect(InetSocketAddress(host, port), 2000)
            s.soTimeout = timeoutMs
            val out = s.getOutputStream()
            val inp = DataInputStream(s.getInputStream())
            out.write(packet(A_CNXN, VERSION, MAXDATA, "host::\u0000".toByteArray()))
            out.flush()
            var msg = read(inp)
            while (msg.cmd != A_CNXN) {
                if (msg.cmd == A_AUTH) return Result(false, "adbd asks for authorisation")
                msg = read(inp)
            }
            val local = 1
            out.write(packet(A_OPEN, local, 0, "shell:$cmd\u0000".toByteArray()))
            out.flush()
            val text = ByteArrayOutputStream()
            var remote = 0
            while (true) {
                val m = read(inp)
                when (m.cmd) {
                    A_OKAY -> remote = m.arg0
                    A_WRTE -> {
                        text.write(m.data)
                        out.write(packet(A_OKAY, local, m.arg0, ByteArray(0)))
                        out.flush()
                    }
                    A_CLSE -> {
                        // closed before it was ever accepted (OKAY): adbd refused to open the shell
                        if (remote == 0) return Result(false, "adbd refused the shell")
                        out.write(packet(A_CLSE, local, remote, ByteArray(0)))
                        out.flush()
                        return Result(true, text.toString("UTF-8").replace("\r\n", "\n").trim())
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") Result(false, "")
        } catch (t: Throwable) {
            Log.w(TAG, "adb '$cmd': $t")
            Result(false, t.toString())
        } finally {
            try { s?.close() } catch (_: Throwable) { }
        }
    }

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun read(inp: DataInputStream): Msg {
        val h = ByteArray(24)
        inp.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int
        val a0 = b.int
        val a1 = b.int
        val len = b.int
        if (len < 0 || len > MAXDATA * 4) throw IllegalStateException("bad length $len")
        val data = ByteArray(len)
        inp.readFully(data)
        return Msg(cmd, a0, a1, data)
    }

    private fun packet(cmd: Int, a0: Int, a1: Int, data: ByteArray): ByteArray {
        var sum = 0
        for (x in data) sum += (x.toInt() and 0xFF)
        val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(sum).putInt(cmd.inv())
        b.put(data)
        return b.array()
    }
}
