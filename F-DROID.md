# F-Droid: что сделано и что осталось

Разбор соответствия Inclusion Policy и текущее состояние репозитория.

## Было / стало

| Требование F-Droid | Было (0.1.4) | Стало |
|---|---|---|
| Свободная лицензия | GPL-3.0 ✅ | без изменений |
| Публичный репозиторий | ✅ | без изменений |
| **Нет предсобранных бинарников** | `jniLibs/<abi>/libbyedpi.so` (4 ABI) ❌ | файлы удалены; `libbyedpi.so` собирается из исходников (`native/`) |
| **Исходники нативных библиотек в репозитории** | были «вне репозитория» (`../native-src`) ❌ | git-сабмодули `native/upstream/byedpi`, `native/upstream/hev-socks5-tunnel` (+ вложенные `lwip`, `yaml`, `hev-task-system`, `src/core`), `native/libv2ray` |
| **Стандартная система сборки** | `build.sh` (aapt2 + javac + d8) ❌ | Gradle 9.8.2 + AGP 9.4.1 (`build.gradle`, `settings.gradle`), нативное — ndk-build из Gradle |
| **Зависимости из репозиториев** | `tools/fetch_deps.sh` качал 5 файлов во время сборки ❌ | `tor-android`, `jtorctl`, `kotlin-stdlib` — Maven Central; `localbroadcastmanager` — Google Maven; Xray-core — из исходников (`tools/build-libv2ray.sh`) или готовым AAR для своих сборок |
| Метаданные (описания, иконки, скриншоты) | отсутствовали ❌ | `fastlane/metadata/android/{ru-RU,en-US}/` + `metadata/ru.whitelist.bypass.yml` |
| Версия в одном месте | `AndroidManifest.xml` | `gradle.properties` (читают Gradle, CI и рецепт) |

## Как теперь устроена нативная сборка

```
native/
  jni.c                    JNI-обёртка: jniStartProxy/jniStopProxy, startTunnel/stopTunnel
  Android.mk               ndk-build: hev (yaml+lwip+task-system) → static, byedpi → static,
                           оба в libbyedpi.so
  Application.mk           android-24, -O3, 16 КБ страницы
  upstream/byedpi          ciadpi (DPI-обход), main() переименован в byedpi_main
  upstream/hev-socks5-tunnel   tun2socks + вложенные сабмодули
  libv2ray                 AndroidLibXrayLite (Go) — источник для libv2ray.aar
```

Ни одного бинарника в дереве: и `libbyedpi.so`, и `libgojni.so` (из AAR Xray), и
`libtor.so` получаются на этапе сборки.

## Как проверить, что это F-Droid-совместимо

```bash
git clone --recursive https://github.com/AlexeyNaverno/white_test.git
cd white_test
export ANDROID_NDK_HOME=$ANDROID_SDK/ndk/26.3.11579264
tools/build-libv2ray.sh      # Xray-core из исходников (Go + gomobile)
gradle assembleRelease
# в APK: lib/*/libbyedpi.so (собран здесь же), libgojni.so, libtor.so
git ls-files | grep -Ei '\.(so|aar|jar|apk)$'   # пусто — бинарников в дереве нет
```

## Что осталось сделать перед отправкой в fdroiddata

1. **Тулчейн на сборочном сервере.** Сборке нужны AGP 9.4.1 + Gradle 9.8.2 и
   `platforms;android-37.1`: `libv2ray.aar` и `tor-android:0.4.9.13.2` объявляют
   в aar-metadata требование `compileSdk 37.1`. Если в fdroiddata платформы 37.1 ещё нет,
   сборка упадёт на `checkReleaseAarMetadata` — тогда либо ждём обновления образа,
   либо откатываем `tor-android` на версию, собранную против 37.0 (и `compileSdk 37`).
2. **Рецепт `metadata/ru.whitelist.bypass.yml`** — черновик лежит в репозитории. Для
   fdroiddata его нужно проверить `fdroid lint` и `fdroid build --server` (или обычной
   сборкой): основной риск — версия Go, нужная Xray-core (`native/libv2ray/go.mod`),
   и шаг `prebuild`, который ставит gomobile.
3. **`geoip.dat`/`geosite.dat`.** `tools/build-libv2ray.sh` скачивает их скриптом
   `gen_assets.sh download` из апстрима Xray. Это данные, а не код; если мейнтейнеры
   F-Droid посчитают их «скачиванием бинарных данных при сборке», эти файлы нужно
   положить в отдельный сабмодуль (`Xray-core` уже содержит генераторы geodata) или
   собирать их из `geofiles` в репозитории Xray.
4. **Версия Go.** В образе сборочных серверов F-Droid Go может быть старше, чем требует
   `go.mod` Xray; тогда в `prebuild` добавляется установка Go нужной версии с golang.org
   (в рецепте это место помечено).
5. **Скриншоты.** В `fastlane/.../images/phoneScreenshots/` лежат два снимка (главный экран
   и раздел «Скорость»). Если UI поменяется — обновить их.
6. **Отправка:** форк `fdroiddata`, файл `metadata/ru.whitelist.bypass.yml` из этого
   репозитория, merge request в `fdroid/fdroiddata`; при первом включении мейнтейнеры
   проверяют сборку и, как правило, просят пару правок (чаще всего — версия Gradle/AGP).

## Что уже точно соответствует политике

- Лицензия GPL-3.0-or-later, полный текст в `LICENSE`.
- Никакой телеметрии, аналитики и «своего» сервера: только публичные списки конфигов и
  серверы из этих списков.
- Нет рекламы, нет проприетарных зависимостей: Xray-core (MPL-2.0), hev-socks5-tunnel
  (MIT), byedpi (MIT), tor-android (BSD-3-Clause), jtorctl (BSD), lwip (BSD), yaml (MIT),
  hev-task-system (MIT).
- Сборка воспроизводима из тега `v*`; артефакт подписан ключом из репозитория.
