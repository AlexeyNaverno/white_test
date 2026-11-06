package ru.whitelist.bypass.jni;

/** JNI-обёртка над локальным DPI-обходом (hufrea/byedpi, собран как libbyedpi.so). */
public class ByeDpiProxy {
    static {
        System.loadLibrary("byedpi");
    }

    private native int jniStartProxy(String[] args);
    private native int jniStopProxy();

    /** Блокирующий вызов: запускает прокси, возвращает код выхода. Вызывать из фонового потока. */
    public int startProxy(String[] args) {
        return jniStartProxy(args);
    }

    public void stopProxy() {
        jniStopProxy();
    }
}
