# Параметры ndk-build для libbyedpi.so
APP_OPTIM := release
APP_PLATFORM := android-24
APP_CFLAGS := -O3
# 16 КБ страницы (требование Google Play для новых устройств) — заодно требование hev
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
NDK_TOOLCHAIN_VERSION := clang
