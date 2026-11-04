package com.abdllh.aura.nav.pack

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PackVerifyTest {
    private fun tar(name: String, magic: String = "ustar", length: Int = 1024): File {
        val b = ByteArray(length)
        name.toByteArray().copyInto(b)
        if (length >= 262) magic.toByteArray().copyInto(b, 257)
        return File.createTempFile("pack", ".tar").apply { writeBytes(b); deleteOnExit() }
    }

    @Test fun aRouteTarStartsWithItsIndex() {
        assertNull(PackVerify.tar(tar("index.bin")))
        assertNull(PackVerify.tar(tar("./index.bin")))
    }

    @Test fun anythingElseIsNot() {
        assertNotNull(PackVerify.tar(tar("0/000/001.gph")))
        assertNotNull(PackVerify.tar(tar("index.bin", magic = "xxxxx")))
        assertNotNull(PackVerify.tar(tar("index.bin", length = 100)))
    }
}
