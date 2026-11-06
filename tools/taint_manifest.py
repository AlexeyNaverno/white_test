#!/usr/bin/env python3
# Отладочный фильтр манифеста для поэтапных тестовых сборок (заменяет EL-иконки/сервисы).
# Использование: taint_manifest.py LEVEL < AndroidManifest.xml > build/AndroidManifest.test.xml
# Уровни: 1=только UI, 2=+DPI, 3=+VPN(Xray), 4=полная сборка (без изменений).
import re
import sys

level = int(sys.argv[1])
src = sys.stdin.read()

def drop_permission(src, name):
    return re.sub(r'\s*<uses-permission[^>]*android:name="' + re.escape(name) + r'"[^>]*/>', '', src)

def drop_service_by_name(src, name_fragment):
    # блок <service name=... ...>...</service> ИЛИ самозакрытый <service name=... .../>
    pattern = re.compile(
        r'\s*<service\b[^>]*?' + re.escape(name_fragment) + r'[^>]*?(?:/>|>.*?</service>)',
        re.DOTALL)
    return pattern.sub('', src)

if level == 1:
    # все сервисы + receiver + VPN/boot/fgs разрешения
    src = re.sub(r'\s*<service\b[^>]*?(?:/>|>.*?</service>)', '', src, flags=re.DOTALL)
    src = re.sub(r'\s*<receiver\b[^>]*?(?:/>|>.*?</receiver>)', '', src, flags=re.DOTALL)
    src = drop_permission(src, 'android.permission.FOREGROUND_SERVICE')
    src = drop_permission(src, 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE')
    src = drop_permission(src, 'android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED')
    src = drop_permission(src, 'android.permission.RECEIVE_BOOT_COMPLETED')
    src = drop_permission(src, 'android.permission.WAKE_LOCK')
    src = drop_permission(src, 'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS')
elif level in (2, 3):
    # Tor исключён из тестирования до этапа 4
    src = drop_service_by_name(src, 'TorService')
else:
    pass  # level 4 — без изменений

# пометить версию в тестовых сборках (видно в системе/установщике)
if level != 4:
    src = src.replace('android:versionName="1.0"', 'android:versionName="1.0-test%d"' % level)

sys.stdout.write(src)
