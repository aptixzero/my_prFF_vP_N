package com.v2ray.ang.service

/**
 * JNI bridge to libhev-socks5-tunnel.so (tun2socks).
 *
 * IMPORTANT: the native library registers its JNI methods (via RegisterNatives
 * in JNI_OnLoad) against the *exact* class name
 *   com/v2ray/ang/service/TProxyService
 * with methods TProxyStartService / TProxyStopService / TProxyGetStats.
 * That is why this single class lives in the com.v2ray.ang.service package —
 * it must match the symbols baked into the prebuilt .so. The rest of the app
 * lives under com.neonvpn.app.
 */
object TProxyService {

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    /** No-op that just forces the native library (and its JNI_OnLoad) to load
     *  eagerly — called from the splash screen so the first connect is warm. */
    @JvmStatic
    fun touch() { /* loading happens in the init block above */ }

    /** Starts the tun2socks loop. Blocks until the tunnel stops. */
    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int)

    @JvmStatic
    external fun TProxyStopService()

    @JvmStatic
    external fun TProxyGetStats(): LongArray?
}
