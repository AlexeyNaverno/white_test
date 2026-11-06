package ru.whitelist.bypass.jni;

/** JNI-обёртка над tun2socks (heiher/hev-socks5-tunnel, dlopen libhev-socks5-tunnel.so). */
public class TProxyService {
    static {
        System.loadLibrary("byedpi");
    }

    /** Запускает туннель TUN → SOCKS5. Блокирующий; fd — дескриптор TUN из VpnService. */
    public native int startTunnel(String config, int fd);

    public native void stopTunnel();
}
