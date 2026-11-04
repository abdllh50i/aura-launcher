package com.valhalla.valhalla

/**
 * The JNI surface of valhalla-mobile 0.6.4's libvalhalla-wrapper.so, read from the AAR (tools/mapbuild/aar_read): UTF-8 JSON
 * in and out. The library's symbols fix the package, the class and the method names, so nothing here may be renamed
 * (proguard-rules.pro). Only the library is taken from the AAR: its Kotlin brings Moshi and AndroidX, which this app has not.
 * The second argument of createActor is the library's HTTP client, always null: the tiles come from the tar.
 * Answers that are errors are JSON too ({"code":171,...}), not exceptions. One actor at a time, on one thread.
 */
internal class ValhallaKotlin {
    external fun createActor(configPath: String, httpClient: Any?): Long
    external fun deleteActor(handle: Long)
    external fun route(handle: Long, request: ByteArray): ByteArray
    external fun traceRoute(handle: Long, request: ByteArray): ByteArray
    external fun traceAttributes(handle: Long, request: ByteArray): ByteArray
    external fun height(handle: Long, request: ByteArray): ByteArray
    external fun matrix(handle: Long, request: ByteArray): ByteArray

    companion object {
        /** Apart from the class, so a caller can time it and catch what it throws: only ever in :route ([com.abdllh.aura.nav.route.RouteProcess]). */
        fun load() = System.loadLibrary("valhalla-wrapper")
    }
}
