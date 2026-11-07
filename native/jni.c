/*
 * JNI-обёртка приложения: два движка в одной библиотеке libbyedpi.so.
 *
 *   1) ciadpi (hufrea/byedpi) — DPI-обход линии. Собирается из исходников как статическая
 *      библиотека, точка входа main() переименована в byedpi_main через -Dmain=byedpi_main.
 *   2) hev-socks5-tunnel (heiher/hev-socks5-tunnel) — tun2socks: TUN → SOCKS5.
 *
 * Раньше в репозитории лежал готовый libbyedpi.so, собранный вне дерева исходников.
 * F-Droid запрещает предсобранные бинарники, поэтому обёртка теперь живёт здесь и
 * собирается вместе с приложением (ndk-build, native/Android.mk).
 *
 * Имена экспортируемых функций обязаны совпадать с нативными методами Java:
 *   ru.whitelist.bypass.jni.ByeDpiProxy  → jniStartProxy(String[]), jniStopProxy()
 *   ru.whitelist.bypass.jni.TProxyService → startTunnel(String, int), stopTunnel()
 */

#include <jni.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "hev-main.h"

/* main() из byedpi/main.c, переименованный флагом -Dmain=byedpi_main */
int byedpi_main(int argc, char **argv);

/* ------------------------------------------------------------------ DPI-обход */

static pthread_t proxy_thread;
static volatile int proxy_running;
static volatile int proxy_stop_requested;
static char **proxy_argv;
static int proxy_argc;

static void *proxy_thread_fn(void *arg)
{
    (void) arg;
    byedpi_main(proxy_argc, proxy_argv);
    proxy_running = 0;
    return NULL;
}

static void free_argv(void)
{
    int i;
    if (proxy_argv == NULL)
        return;
    for (i = 0; i < proxy_argc; i++)
        free(proxy_argv[i]);
    free(proxy_argv);
    proxy_argv = NULL;
    proxy_argc = 0;
}

JNIEXPORT jint JNICALL
Java_ru_whitelist_bypass_jni_ByeDpiProxy_jniStartProxy(JNIEnv *env, jobject thiz,
                                                       jobjectArray args)
{
    jsize count, i;
    (void) thiz;

    if (proxy_running)
        return 0;

    free_argv();
    count = args == NULL ? 0 : (*env)->GetArrayLength(env, args);
    /* argv[0] — имя программы: ciadpi его пропускает, но пустым он быть не должен */
    proxy_argv = (char **) calloc((size_t) count + 2, sizeof(char *));
    if (proxy_argv == NULL)
        return -1;
    proxy_argv[0] = strdup("ciadpi");
    proxy_argc = 1;
    for (i = 0; i < count; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        const char *utf;
        if (s == NULL)
            continue;
        utf = (*env)->GetStringUTFChars(env, s, NULL);
        if (utf != NULL) {
            proxy_argv[proxy_argc++] = strdup(utf);
            (*env)->ReleaseStringUTFChars(env, s, utf);
        }
        (*env)->DeleteLocalRef(env, s);
    }

    proxy_stop_requested = 0;
    proxy_running = 1;
    if (pthread_create(&proxy_thread, NULL, proxy_thread_fn, NULL) != 0) {
        proxy_running = 0;
        free_argv();
        return -1;
    }
    pthread_detach(proxy_thread);
    return 0;
}

JNIEXPORT jint JNICALL
Java_ru_whitelist_bypass_jni_ByeDpiProxy_jniStopProxy(JNIEnv *env, jobject thiz)
{
    int i;
    (void) env;
    (void) thiz;

    if (!proxy_running)
        return 0;

    proxy_stop_requested = 1;
    /* ciadpi ставит обработчики SIGINT/SIGTERM в run(): сигнал закрывает слушающий
       сокет, и цикл событий выходит сам. Сигнал адресуем рабочему потоку. */
    pthread_kill(proxy_thread, SIGTERM);

    for (i = 0; i < 100 && proxy_running; i++)
        usleep(20000);  /* до 2 с на корректное завершение */

    if (!proxy_running)
        free_argv();
    return 0;
}

/* ------------------------------------------------------------------ tun2socks */

JNIEXPORT jint JNICALL
Java_ru_whitelist_bypass_jni_TProxyService_startTunnel(JNIEnv *env, jobject thiz,
                                                       jstring config, jint fd)
{
    const char *text;
    int result;
    (void) thiz;

    if (config == NULL)
        return -1;
    text = (*env)->GetStringUTFChars(env, config, NULL);
    if (text == NULL)
        return -1;
    result = hev_socks5_tunnel_main_from_str((const unsigned char *) text,
                                             (unsigned int) strlen(text), (int) fd);
    (*env)->ReleaseStringUTFChars(env, config, text);
    return result;
}

JNIEXPORT void JNICALL
Java_ru_whitelist_bypass_jni_TProxyService_stopTunnel(JNIEnv *env, jobject thiz)
{
    (void) env;
    (void) thiz;
    hev_socks5_tunnel_quit();
}
