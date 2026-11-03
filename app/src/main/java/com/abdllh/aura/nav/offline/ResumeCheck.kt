package com.abdllh.aura.nav.offline

/**
 * Do the bytes a 206 brings belong after the ones kept? `If-Range` asks the server to send the whole file if it changed, and
 * not every server listens: GitHub's CDN answers 206 to a wrong validator as well (measured on a release asset), which spliced
 * two versions of a file together without a word. So the answer's own headers are compared here, whatever the server did
 * with the question: same validator as when the file was begun, and a range that starts where the kept part ends.
 * Anything else or anything missing is a no: the whole file again costs data, a spliced one costs a broken map.
 */
internal object ResumeCheck {
    fun fits(kept: String, etag: String?, lastModified: String?, contentRange: String?, have: Long): Boolean {
        if (start(contentRange) != have) return false
        // (what was kept is the file's ETag when it had a strong one, else its Last-Modified: OfflineMaps.fetchFile)
        return kept == etag || kept == lastModified
    }

    /** The first byte of "bytes 100-199/200", -1 when it is not that. */
    private fun start(contentRange: String?): Long {
        val r = contentRange?.trim() ?: return -1L
        if (!r.startsWith("bytes ", ignoreCase = true)) return -1L
        return r.substring(6).trim().substringBefore('-').toLongOrNull() ?: -1L
    }
}
