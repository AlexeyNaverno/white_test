# Сборка нативной библиотеки libbyedpi.so из исходников (ndk-build).
#
# Внутри одного .so:
#   • ciadpi  (hufrea/byedpi)               — DPI-обход линии, main() переименован;
#   • hev-socks5-tunnel (heiher) + lwip     — tun2socks: TUN → SOCKS5;
#   • native/jni.c                          — JNI-обёртка приложения.
#
# Исходники подключаются git-сабмодулями (native/upstream/*): в дереве репозитория нет
# ни одного предсобранного бинарника — этого требует Inclusion Policy F-Droid.

LOCAL_PATH := $(call my-dir)
NATIVE_TOP := $(LOCAL_PATH)
UPSTREAM := $(NATIVE_TOP)/upstream
BYEDPI := $(UPSTREAM)/byedpi
HEV := $(UPSTREAM)/hev-socks5-tunnel

# рекурсивный поиск файлов (в upstream-мейкфайлах объявлен так же)
rwildcard = $(foreach d,$(wildcard $1*),$(call rwildcard,$d/,$2) $(filter $(subst *,%,$2),$d))

# --------------------------------------------------------------- третьи части hev
# yaml (разбор конфига), lwip (стек TCP/IP), hev-task-system (корутины)
include $(HEV)/third-part/yaml/Android.mk
include $(HEV)/third-part/lwip/Android.mk
ENABLE_MEMALLOC_SLICE := 0
include $(HEV)/third-part/hev-task-system/Android.mk

# --------------------------------------------------------------- ядро tun2socks
include $(CLEAR_VARS)
LOCAL_PATH := $(HEV)
HEV_SOURCES := $(patsubst $(HEV)/%,%,$(filter-out $(HEV)/src/hev-jni.c,$(call rwildcard,$(HEV)/src/,*.c)))
LOCAL_MODULE := hev-socks5-tunnel-core
LOCAL_SRC_FILES := $(HEV_SOURCES)
LOCAL_C_INCLUDES := \
    $(HEV)/src \
    $(HEV)/src/misc \
    $(HEV)/src/core/include \
    $(HEV)/third-part/yaml/include \
    $(HEV)/third-part/lwip/src/include \
    $(HEV)/third-part/lwip/src/ports/include \
    $(HEV)/third-part/hev-task-system/include
LOCAL_CFLAGS := -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED -DENABLE_LIBRARY \
    -fno-stack-clash-protection -DCOMMIT_ID=\"local\"
LOCAL_STATIC_LIBRARIES := libyaml liblwip libhev-task-system
include $(BUILD_STATIC_LIBRARY)

# --------------------------------------------------------------- ciadpi (DPI-обход)
include $(CLEAR_VARS)
LOCAL_PATH := $(BYEDPI)
LOCAL_MODULE := byedpi-core
LOCAL_SRC_FILES := packets.c main.c conev.c proxy.c desync.c mpool.c extend.c
LOCAL_C_INCLUDES := $(BYEDPI)
# -Dmain=byedpi_main: точка входа переименована, иначе символ main конфликтует с JNI-обёрткой
LOCAL_CFLAGS := -D_DEFAULT_SOURCE -std=c99 -O2 -fno-stack-clash-protection -Dmain=byedpi_main
include $(BUILD_STATIC_LIBRARY)

# --------------------------------------------------------------- libbyedpi.so
include $(CLEAR_VARS)
LOCAL_PATH := $(NATIVE_TOP)
LOCAL_MODULE := byedpi
LOCAL_SRC_FILES := jni.c
LOCAL_C_INCLUDES := $(HEV)/src
LOCAL_STATIC_LIBRARIES := byedpi-core hev-socks5-tunnel-core libyaml liblwip libhev-task-system
LOCAL_GROUP_STATIC_LIBRARIES := true
LOCAL_LDLIBS := -llog -landroid
include $(BUILD_SHARED_LIBRARY)
