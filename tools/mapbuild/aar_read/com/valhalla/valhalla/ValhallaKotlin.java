package com.valhalla.valhalla;

/**
 * The JNI surface of valhalla-mobile 0.6.4's libvalhalla-wrapper.so, as javap reads it from the AAR's classes.jar (see
 * ../../../../aar_probe.py). The class name and package are fixed by the symbol names; the second createActor argument is the
 * library's ValhallaHttpClient, always null here: tiles come from the tar.
 */
public class ValhallaKotlin {
    public native long createActor(String configPath, Object httpClient);

    public native void deleteActor(long handle);

    public native byte[] route(long handle, byte[] request);

    public native byte[] traceRoute(long handle, byte[] request);

    public native byte[] traceAttributes(long handle, byte[] request);

    public native byte[] height(long handle, byte[] request);

    public native byte[] matrix(long handle, byte[] request);
}
