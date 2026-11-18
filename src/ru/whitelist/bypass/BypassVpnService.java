package ru.whitelist.bypass;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.util.Log;

import ru.whitelist.bypass.jni.ByeDpiProxy;
import ru.whitelist.bypass.jni.TProxyService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Единственный режим работы: VPN (Xray) + при необходимости DPI-обход линии (ciadpi).
 *
 * Порядок запуска (важно):
 *   1) поднимаем и проверяем ядро на 127.0.0.1:<свободный порт> — интернет не трогаем;
 *   2) только после успешной проверки создаём TUN;
 *   3) поднимаем hev-socks5-tunnel (TUN → SOCKS этого же порта).
 *
 * С 0.1.17 порт свободный, а не фиксированный 10811, и у каждого запуска есть номер
 * поколения: поток, переживший «Остановить», не может поднять TUN заново.
 * Поэтому неудачная попытка больше не оставляет телефон без связи.
 */
public class BypassVpnService extends VpnService {

    public static final String ACTION_START = "ru.whitelist.bypass.action.START";
    public static final String ACTION_STOP = "ru.whitelist.bypass.action.STOP";
    /** Кнопка в уведомлении: сменить конфиг, не отключая туннель. */
    public static final String ACTION_SWITCH = "ru.whitelist.bypass.action.SWITCH";
    /**
     * Кнопка в уведомлении: пауза. Туннель снимается целиком, трафик идёт с хоста
     * напрямую — это именно «на минуту», а не «выключить»: ядро остаётся поднятым,
     * и продолжение не требует новой проверки конфига.
     */
    public static final String ACTION_PAUSE = "ru.whitelist.bypass.action.PAUSE";
    /** Кнопка в уведомлении: продолжить с того же конфига (или поднять заново). */
    public static final String ACTION_RESUME = "ru.whitelist.bypass.action.RESUME";
    public static final String ACTION_STATUS = "ru.whitelist.bypass.action.STATUS";
    public static final String EXTRA_STATE = "state";    // off | connecting | on | error
    public static final String EXTRA_DETAIL = "detail";
    /** Каким каналом поднят туннель: xray (конфиг из пула) или tor (резервные мосты). */
    public static final String EXTRA_MODE = "mode";      // xray | tor | dpi
    public static final String MODE_XRAY = "xray";
    public static final String MODE_TOR = "tor";
    /** Режим «только DPI»: локальный обход без VPN-сервера (YouTube, Discord). */
    public static final String MODE_DPI = "dpi";
    /** Каким режимом стартовать: по умолчанию — обычный VPN (xray). */
    public static final String EXTRA_START_MODE = "start_mode";
    /** Подключаться ровно к этому конфигу, а не перебирать пул (выбор в списке). */
    public static final String EXTRA_FORCE_RAW = "force_raw";
    /** Подключиться к конкретному конфигу из списка рабочих. */
    public static final String ACTION_CONNECT_RAW = "ru.whitelist.bypass.action.CONNECT_RAW";

    private static final String CHANNEL_ID = "bypass_vpn";
    private static final int NOTIFICATION_ID = 1001;
    private static final String TAG = "BypassVpnService";

    /** Локальный SOCKS ядра Xray. */
    private static final int XRAY_PORT = 10811;
    /** Сколько конфигов из пула пробуем за одно подключение. */
    private static final int MAX_ATTEMPTS = 6;
    /** Попыток за одно нажатие «Переключить»; курсор продолжает со следующего нажатия. */
    private static final int MAX_SWITCH_TRIES = 3;
    /** Допуск при повторной проверке скорости: 80% от порога (сеть «дышит»). */
    private static final double MIN_SPEED_TOLERANCE = 0.8;
    /** Сколько конфигов проверяем «вживую» при быстром поиске. */
    private static final int QUICK_PROBES = 12;
    /** Больше 300 конфигов пинговать перед быстрым поиском нет смысла. */
    private static final int QUICK_PING_CAP = 300;

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile boolean connectingFlag = false;
    private static volatile String currentDetail = "";
    /** Автоподбор стратегии DPI делаем один раз за подключение, а не на каждую попытку. */
    private boolean dpiAutotuned = false;
    /** Пауза: TUN снят, трафик идёт напрямую, ядро при этом живо. */
    private static volatile boolean paused = false;
    private static volatile int currentSocksPort = -1;
    /** Туннель поднят через Tor-мосты (резервный режим), а не через конфиг из пула. */
    private volatile boolean torMode = false;
    private static volatile String currentMode = MODE_XRAY;
    /** Локальный SOCKS, в который смотрит hev-socks5-tunnel (ядро на своём порту или Tor 9050). */
    private static volatile int localSocksPort = XRAY_PORT;
    /**
     * Порт, на котором ядро реально поднялось в этом подключении.
     *
     * Раньше все подключения шли на фиксированный 10811. Если предыдущее ядро ещё не
     * отпустило порт (stopLoop асинхронный), новое не могло забиндиться, SOCKS не
     * открывался — и свежезамеренный конфиг «не проходил проверку при подключении»,
     * хотя при замере (там порт свободный) работал. Теперь порт берётся свободный.
     */
    private static volatile int activeSocksPort = XRAY_PORT;

    public static boolean isRunning() { return running.get(); }
    /** xray | tor — каким каналом сейчас работает туннель. */
    public static String currentMode() { return currentMode; }
    public static boolean isTorMode() { return MODE_TOR.equals(currentMode); }
    /** Сейчас работает режим «только DPI» (без VPN-сервера). */
    public static boolean isDpiMode() { return MODE_DPI.equals(currentMode); }
    /** Идёт попытка подключения (сервис занят поиском/проверкой). */
    public static boolean isConnecting() { return connectingFlag; }
    /** Туннель на паузе: VPN снят, трафик идёт напрямую. */
    public static boolean isPaused() { return paused; }
    public static String currentDetail() { return currentDetail; }
    public static int currentSocksPort() { return currentSocksPort; }

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    /**
     * Номер запуска. «Остановить» и любой новый запуск его увеличивают, поэтому поток,
     * который поднимал прошлый канал, видит, что он больше не «свой», и не создаёт TUN.
     * Без этого стоп во время подключения возвращал VPN обратно: поток доходил до
     * establishTun уже после stopSelf, и интерфейс оставался поднятым.
     */
    private final AtomicLong run = new AtomicLong();
    /** Поток, который сейчас поднимает канал: «Остановить» прерывает его, не дожидаясь таймаутов. */
    private volatile Thread workerThread;
    private ParcelFileDescriptor tun;
    private ProxyConfig currentConfig;
    private ByeDpiProxy desyncProxy;
    private Thread monitorThread;
    private PowerManager.WakeLock wakeLock;
    private volatile String lastError = "";
    /** Подключение к конкретному конфигу (выбор пользователя): пул не перебираем. */
    private volatile String forcedRaw = null;
    /** Режим «только DPI»: TUN смотрит в локальный ciadpi, ядро Xray не запускается. */
    private volatile boolean dpiOnly = false;
    /** Запуск «Tor как VPN»: поднят по кнопке, а не как резерв после отказа. */
    private volatile boolean torRequested = false;
    /** Курсор переключения: каждое нажатие «Переключить» идёт дальше по пулу. */
    private int switchCursor = 0;
    /** Порт локального SOCKS ciadpi в режиме «только DPI». */
    private volatile int dpiOnlyPort = 0;

    // ------------------------------------------------------------------ жизненный цикл

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.migrate(this);
        createChannel();
    }

    @Override
    public void onRevoke() {
        Log.i(TAG, "VPN отозван системой");
        stopEverything();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopEverything();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopEverything();
            return START_NOT_STICKY;
        }
        if (ACTION_SWITCH.equals(action)) {
            if (running.get()) {
                if (paused) {
                    sendStatus("paused", "Пауза · трафик идёт напрямую");
                } else {
                    sendStatus("connecting", "Переключаю конфиг…");
                    Thread switcher = new Thread(new Runnable() {
                        @Override public void run() { switchToNext(false); }
                    }, "bypass-switch");
                    switcher.setDaemon(true);
                    workerThread = switcher;
                    switcher.start();
                }
            } else {
                sendStatus("off", "Туннель не запущен");
            }
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            pauseTunnel();
            return START_NOT_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            resumeTunnel();
            return START_NOT_STICKY;
        }
        if (ACTION_CONNECT_RAW.equals(action)) {
            if (running.get() || tun != null) resetEngine();
            final String raw = intent.getStringExtra(EXTRA_FORCE_RAW);
            if (raw == null || raw.trim().isEmpty()) {
                sendStatus("error", "Пустой конфиг — нечего подключать");
                return START_NOT_STICKY;
            }
            prepareStart(null, raw.trim());
            Thread starter = new Thread(new Runnable() {
                @Override public void run() {
                    // Один конкретный конфиг: ни перебора пула, ни резерва Tor.
                    forcedRaw = raw.trim();
                    startWorker();
                }
            }, "bypass-connect-raw");
            starter.setDaemon(true);
            workerThread = starter;
            starter.start();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            final String startMode = intent.getStringExtra(EXTRA_START_MODE);
            final String raw = intent.getStringExtra(EXTRA_FORCE_RAW);
            prepareStart(startMode, raw);
            if (MODE_DPI.equals(startMode)) {
                sendStatus("connecting", "Поднимаю DPI-обход…");
            } else if (MODE_TOR.equals(startMode)) {
                sendStatus("connecting", "Поднимаю Tor-мосты как VPN…");
            } else {
                sendStatus("connecting", "Запуск…");
            }
            Thread starter = new Thread(new Runnable() {
                @Override public void run() {
                    if (torRequested) startTorWorker();
                    else if (dpiOnly) startDpiWorker();
                    else startWorker();
                }
            }, "bypass-start");
            starter.setDaemon(true);
            workerThread = starter;
            starter.start();
        }
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------ запуск

    /** Общая подготовка запуска: сброс прошлого состояния, режим, принудительный конфиг. */
    private void prepareStart(String startMode, String raw) {
        if (running.get() || tun != null) resetEngine();   // повторный старт = перезапуск
        // Новый запуск: у прошлых потоков отбираем право поднимать канал. Порядок важен —
        // сначала новый номер, потом снятие stopRequested, иначе старый поток успел бы
        // проскочить проверку и создать второй TUN.
        run.incrementAndGet();
        dpiAutotuned = false;
        dpiOnly = MODE_DPI.equals(startMode);
        torRequested = MODE_TOR.equals(startMode);
        forcedRaw = raw;
        torMode = false;
        currentMode = torRequested ? MODE_TOR : (dpiOnly ? MODE_DPI : MODE_XRAY);
        localSocksPort = XRAY_PORT;
        activeSocksPort = XRAY_PORT;
        stopRequested.set(false);
        ConfigRepository.clearCancel();
        lastError = "";
        startForegroundCompat();
    }

    /**
     * Не пора ли прекратить подъём канала: нажали «Остановить» (или сервис гасят) либо
     * начался другой запуск и этот поток больше не нужен.
     */
    private boolean aborted(long myRun) {
        return myRun != run.get() || stopRequested.get();
    }

    /**
     * Режим «только DPI»: без VPN-сервера, силами одного ciadpi.
     *
     * Зачем: у оператора часть сервисов не заблокирована, а замедлена (YouTube) или
     * обрезана (Discord). Для них не нужен чужой сервер — достаточно развалить DPI
     * на первых пакетах TLS. TUN ведём в локальный SOCKS ciadpi, имена резолвит он же
     * (mapdns, как в режиме Tor), чтобы не зависеть от UDP.
     */
    private void startDpiWorker() {
        final long myRun = run.get();
        try {
            String strategy = Prefs.dpiOnlyStrategy(this);
            sendStatus("connecting", "DPI-обход: " + DpiStrategies.shorten(strategy));
            int port = DesyncLine.start(strategy, "www.youtube.com");
            if (port <= 0) {
                fail("DPI-обход не поднялся. Проверьте стратегию на вкладке «Только DPI»");
                return;
            }
            dpiOnlyPort = port;
            boolean answered = false;
            for (String url : Prefs.dpiOnlyUrls(this)) {
                if (stopRequested.get()) break;
                if (NetChecker.httpCheckThroughSocks(url, "127.0.0.1", port,
                        Prefs.gateTimeoutMs(this))) {
                    answered = true;
                    break;
                }
            }
            if (!answered) {
                DesyncLine.stop();
                fail("Через DPI-обход не открылся ни один адрес. Подберите другую стратегию "
                        + "(вкладка «Только DPI»).");
                return;
            }
            tun = establishTun(TorTunnel.DNS);
            if (tun == null) {
                DesyncLine.stop();
                fail("Не удалось создать TUN (VPN не разрешён?)");
                return;
            }
            if (aborted(myRun)) {
                teardownTunnel();
                DesyncLine.stop();
                dpiOnlyPort = 0;
                running.set(false);
                releaseWakeLock();
                Log.i(TAG, "«только DPI»: остановлено сразу после создания TUN");
                return;
            }
            localSocksPort = port;
            currentSocksPort = port;
            startTunnel(port, true);
            running.set(true);
            acquireWakeLock();
            currentDetail = "Только DPI · " + DpiStrategies.shorten(strategy);
            sendStatus("on", currentDetail);
            Log.i(TAG, "режим «только DPI» поднят на порту " + port);
        } catch (Throwable t) {
            Log.e(TAG, "режим «только DPI» не запустился", t);
            fail(t.getMessage() != null ? t.getMessage() : "Ошибка запуска");
        }
    }

    /**
     * «Tor как VPN»: канал сразу через мосты, без перебора конфигов.
     *
     * Отличие от резервного режима только в причине запуска: там Tor поднимается после
     * того, как отказали все конфиги, здесь — по кнопке в настройках. Дальше всё общее:
     * проверка скорости через Tor, TUN, монитор.
     */
    private void startTorWorker() {
        sendStatus("connecting", "Поднимаю Tor-мосты как VPN…");
        if (!bringUpTor("Tor-мост включён как VPN", true)) {
            String e = lastError;
            if (e == null || e.isEmpty()) e = "Tor-мосты не поднялись";
            fail(e);
        }
    }

    private void startWorker() {
        final long myRun = run.get();
        try {
            final boolean forced = forcedRaw != null && !forcedRaw.trim().isEmpty();
            List<Pool.Entry> candidates;
            if (forced) {
                candidates = candidatesForced(forcedRaw.trim());
                sendStatus("connecting", "Подключаюсь к выбранному конфигу…");
            } else {
                candidates = connectCandidates(MAX_ATTEMPTS);
                if (candidates.isEmpty()) {
                    sendStatus("connecting", "Пул пуст — ищу первый рабочий конфиг…");
                    candidates = entriesOf(quickFind());
                }
            }
            if (candidates.isEmpty()) {
                if (forced) {
                    fail("Выбранный конфиг не разобрался — строка повреждена или схема не "
                            + "поддерживается ядром.");
                    return;
                }
                if (tryTorFallback("Рабочих конфигов нет")) return;
                fail("Рабочих конфигов нет. Нажмите «Собрать пул» (обновит списки и замерит скорость).");
                return;
            }

            boolean up = false;
            int attempt = 0;
            int total = candidates.size();
            for (Pool.Entry entry : candidates) {
                if (aborted(myRun) || ConfigRepository.isCancelled()) {
                    // Не fail(): остановку уже показали как «Отключено», а «ошибка»
                    // после неё выглядела бы как сбой приложения.
                    Log.i(TAG, "подключение отменено пользователем");
                    return;
                }
                ProxyConfig cfg = entry.cfg();
                if (cfg == null) {
                    Pool.remove(this, entry.raw);   // строка пула битая — ей там не место
                    continue;
                }
                attempt++;
                currentConfig = cfg;
                // Порядок повторяет то, как конфиг попал в пул. Замерен через DPI-обход —
                // сначала пробуем РОВНО ту стратегию, что дала канал; замерен напрямую —
                // сначала напрямую. Вторая попытка всегда одна и «другая».
                if (!entry.dpi.isEmpty()) {
                    sendStatus("connecting", "Попытка " + attempt + "/" + total
                            + " через DPI-обход (как при замере): " + truncate(cfg.name, 34));
                    up = activate(cfg, entry.dpi, myRun);
                    if (!up) {
                        if (aborted(myRun)) {
                            Log.i(TAG, "подключение отменено пользователем");
                            return;
                        }
                        sendStatus("connecting", "Попытка " + attempt + "/" + total
                                + " напрямую: " + truncate(cfg.name, 34));
                        up = activate(cfg, null, myRun);
                    }
                } else {
                    sendStatus("connecting", "Попытка " + attempt + "/" + total
                            + ": " + truncate(cfg.name, 40));
                    up = activate(cfg, null, myRun);
                    if (!up && aborted(myRun)) {
                        Log.i(TAG, "подключение отменено пользователем");
                        return;
                    }
                    if (!up && Prefs.desyncLine(this)) {
                        sendStatus("connecting", "Попытка " + attempt + "/" + total
                                + " с DPI-обходом (один раз): " + truncate(cfg.name, 30));
                        up = activate(cfg, Prefs.dpiStrategyFor(this), myRun);
                    }
                }
                if (up) break;
                if (forced) {
                    // Пользователь выбрал конфиг руками: не наказываем его за это —
                    // ни из пула, ни в чёрный список. Просто честно сообщаем причину.
                    fail("Выбранный конфиг не поднялся: "
                            + (lastError.isEmpty() ? "нет данных" : lastError));
                    return;
                }
                // Ни напрямую, ни с обходом — значит он больше не рабочий:
                // в урну (из пула) и в чёрный список, чтобы неделю его не трогать.
                discard(cfg, lastError);
            }
            if (!up) {
                String why = ("Не удалось подключиться ни к одному из " + attempt
                        + " конфигов. " + (lastError.isEmpty() ? "" : lastError)).trim();
                // Резерв: пустить трафик через Tor-мосты (если разрешено настройками).
                if (tryTorFallback(why)) return;
                fail(why);
                return;
            }

            // Ядро ответило и прошло проверку скорости — только теперь трогаем трафик.
            // Но если за это время нажали «Остановить», трафик трогать уже нельзя: именно
            // на этом месте VPN раньше возвращался к жизни после остановки.
            if (aborted(myRun)) {
                XrayEngine.stop();
                Log.i(TAG, "остановлено до создания TUN — канал не поднимаю");
                return;
            }
            tun = establishTun();
            if (tun == null) {
                fail("Не удалось создать TUN (VPN не разрешён?)");
                return;
            }
            if (aborted(myRun)) {
                teardownTunnel();
                XrayEngine.stop();
                running.set(false);
                releaseWakeLock();
                Log.i(TAG, "остановлено сразу после создания TUN — TUN закрыт");
                return;
            }
            int upPort = activeSocksPort > 0 ? activeSocksPort : XRAY_PORT;
            localSocksPort = upPort;
            currentMode = MODE_XRAY;
            startTunnel(upPort, false);
            running.set(true);
            acquireWakeLock();
            startMonitor();
            sendStatus("on", currentDetail);
        } catch (Throwable t) {
            Log.e(TAG, "старт не удался", t);
            fail(t.getMessage() != null ? t.getMessage() : "Ошибка запуска");
        }
    }

    /**
     * Резервный запуск: весь трафик через Tor-мосты.
     *
     * Порядок тот же, что и у обычного запуска: сначала проверяем канал (мосты поднялись,
     * скорость выше порога), и только потом создаём TUN. Поэтому неудачная попытка
     * не оставляет телефон без связи.
     *
     * true — резерв поднят, сервис уже в режиме «включено» (дальше вызывающему делать
     * нечего); false — резерв недоступен, причину пишем в {@link #lastError}.
     */
    /** Резервный Tor после отказа всех конфигов. */
    private boolean tryTorFallback(String why) {
        return bringUpTor(why, false);
    }

    /**
     * Поднять Tor с мостами как канал.
     *
     * @param manual true — нажали «Tor как VPN» в настройках: переключатель «резерв Tor»
     *               не проверяем, человек сам этого попросил. false — резерв после того,
     *               как ни один конфиг не ответил (тогда нужен tor_fallback).
     */
    private boolean bringUpTor(String why, boolean manual) {
        final long myRun = run.get();
        if (!manual && !Prefs.torFallback(this)) {
            lastError = why + ". Резерв Tor выключен в настройках";
            return false;
        }
        if (stopRequested.get() || ConfigRepository.isCancelled()) {
            lastError = "Остановлено пользователем";
            return false;
        }
        Log.i(TAG, manual ? ("Включаю Tor как VPN: " + why)
                : ("VPN не отвечает (" + why + ") — пробую Tor-мосты"));
        // Кнопка становится фиолетовой сразу, как только начали пробовать резерв:
        // пользователь должен видеть, что сейчас происходит не обычное подключение.
        currentMode = MODE_TOR;
        sendStatus("connecting", why + " — пробую Tor-мосты…");

        // Tor с мостами поднимается долго (до 90 с), а «Остановить» должно срабатывать
        // сразу: поднимаем в отдельном потоке и ждём, поглядывая на stopRequested.
        final AtomicBoolean torUp = new AtomicBoolean(false);
        Thread torStarter = new Thread(new Runnable() {
            @Override public void run() {
                torUp.set(TorTunnel.start(BypassVpnService.this));
            }
        }, "tor-fallback");
        torStarter.setDaemon(true);
        torStarter.start();
        long torDeadline = System.currentTimeMillis() + TorTunnel.startTimeoutMs(this) + 8000;
        while (torStarter.isAlive() && System.currentTimeMillis() < torDeadline) {
            if (stopRequested.get() || ConfigRepository.isCancelled()) {
                TorTunnel.stop(this);
                lastError = "Остановлено пользователем";
                currentMode = MODE_XRAY;
                return false;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                break;
            }
        }
        if (!torUp.get()) {
            String e = TorEngine.lastError();
            lastError = why + ". Tor не поднялся" + (e == null || e.isEmpty() ? "" : ": " + e);
            Log.w(TAG, lastError);
            TorTunnel.stop(this);
            currentMode = MODE_XRAY;
            return false;
        }
        if (stopRequested.get() || ConfigRepository.isCancelled()) {
            TorTunnel.stop(this);
            lastError = "Остановлено пользователем";
            currentMode = MODE_XRAY;
            return false;
        }

        sendStatus("connecting", "Tor-мосты подняты, замеряю скорость…");
        long kbps = TorTunnel.measureKbps(this, TorTunnel.SPEED_TIMEOUT_MS);
        if (!TorTunnel.fastEnough(this, kbps)) {
            lastError = why + ". Через Tor-мосты "
                    + (kbps <= 0 ? "скорость не измерилась" : Prefs.speedText(kbps) + " — ниже порога")
                    + " (" + Prefs.minSpeedText(this) + ")";
            Log.w(TAG, lastError);
            TorTunnel.stop(this);
            currentMode = MODE_XRAY;
            return false;
        }

        // TUN создаём только теперь, когда резервный канал доказанно живой.
        if (aborted(myRun)) {
            TorTunnel.stop(this);
            lastError = "Остановлено пользователем";
            currentMode = MODE_XRAY;
            return false;
        }
        teardownTunnel();
        tun = establishTun(TorTunnel.DNS);
        if (tun == null) {
            TorTunnel.stop(this);
            lastError = why + ". Не удалось создать TUN (VPN не разрешён?)";
            currentMode = MODE_XRAY;
            return false;
        }
        if (aborted(myRun)) {
            teardownTunnel();
            TorTunnel.stop(this);
            running.set(false);
            releaseWakeLock();
            currentMode = MODE_XRAY;
            lastError = "Остановлено пользователем";
            Log.i(TAG, "Tor-резерв: остановлено сразу после создания TUN");
            return false;
        }
        torMode = true;
        currentMode = MODE_TOR;
        localSocksPort = TorTunnel.SOCKS_PORT;
        currentConfig = null;
        currentSocksPort = TorTunnel.SOCKS_PORT;
        currentDetail = "Tor-мосты · " + Prefs.speedText(kbps);
        startTunnel(TorTunnel.SOCKS_PORT, true);
        running.set(true);
        acquireWakeLock();
        startMonitor();
        sendStatus("on", currentDetail);
        Log.i(TAG, "резервный режим включён: " + currentDetail);
        return true;
    }

    /** Погасить hev и TUN, но не трогать сервис и ядро (для перехода в другой режим). */
    private void teardownTunnel() {
        try {
            new TProxyService().stopTunnel();
        } catch (Throwable ignored) {
        }
        try {
            if (tun != null) tun.close();
        } catch (Exception ignored) {
        }
        tun = null;
    }

    /** Кандидаты из пула: сверху самые быстрые, вместе со стратегией DPI-обхода. */
    private List<Pool.Entry> connectCandidates(int max) {
        List<Pool.Entry> out = new ArrayList<>();
        for (Pool.Entry e : Pool.load(this)) {
            if (out.size() >= max) break;
            if (e.cfg() != null) out.add(e);
        }
        return out;
    }

    /**
     * Один конкретный конфиг (выбор в списке рабочих) в виде кандидата.
     *
     * Берём запись из пула текущей сети, чтобы сохранить стратегию DPI, с которой этот
     * конфиг был замерен. Если записи нет (конфиг подключили из буфера), собираем
     * кандидата из самой строки без стратегии.
     */
    private List<Pool.Entry> candidatesForced(String raw) {
        List<Pool.Entry> out = new ArrayList<>();
        String key = ConfigRepository.canonicalKey(raw);
        if (key != null) {
            // Ищем в обоих пулах: конфиг могли выбрать на вкладке другой сети. Сначала
            // текущая сеть — у неё может быть свежий замер, — потом вторая.
            String mine = Net.key(this);
            String other = Net.WIFI.equals(mine) ? Net.MOBILE : Net.WIFI;
            for (String net : new String[]{mine, other}) {
                for (Pool.Entry e : Pool.load(this, net)) {
                    String k = ConfigRepository.canonicalKey(e.raw);
                    if (key.equals(k)) {
                        out.add(e);
                        return out;
                    }
                }
            }
        }
        ProxyConfig cfg = ProxyConfig.parse(raw, "выбор", false);
        if (cfg != null) {
            out.add(new Pool.Entry(cfg.raw, 1, cfg.latency, System.currentTimeMillis()));
        }
        return out;
    }

    /** Найденные «быстрым поиском» конфиги — в вид записей пула (стратегии пока нет). */
    private static List<Pool.Entry> entriesOf(List<ProxyConfig> list) {
        List<Pool.Entry> out = new ArrayList<>();
        for (ProxyConfig c : list) {
            if (c != null) out.add(new Pool.Entry(c.raw, 1, c.latency, System.currentTimeMillis()));
        }
        return out;
    }

    /**
     * Конфиг не поднялся ни напрямую, ни с DPI-обходом: убираем его из пула («в урну»)
     * и ставим в чёрный список. Держать в рабочих то, что не подключается, бессмысленно —
     * оно занимает попытку у каждого следующего подключения.
     */
    private void discard(ProxyConfig cfg, String why) {
        if (cfg == null) return;
        // Из обоих пулов: сломанный конфиг не должен оставаться и в пуле другой сети.
        String mine = Net.key(this);
        boolean removed = Pool.remove(this, cfg.raw);
        removed |= Pool.remove(this, Net.WIFI.equals(mine) ? Net.MOBILE : Net.WIFI, cfg.raw);
        // Конфиг, который ядро не примет в принципе (нет TLS, а ретранслятор выключен),
        // из пула убираем — он занимает попытку, — но в чёрный список не пишем: это
        // ограничение настроек, а не мёртвый сервер.
        if (XrayEngine.compatProblem(cfg, Prefs.allowNoTls(this)) != null) {
            Log.i(TAG, "конфиг несовместим с ядром при текущих настройках: " + cfg.name);
            return;
        }
        Blacklist.add(this, cfg, why);
        String text = "Убран из рабочих" + (removed ? "" : " (его там уже не было)")
                + " и в чёрный список: " + truncate(cfg.name, 30)
                + (why == null || why.isEmpty() ? "" : " · " + why);
        Log.i(TAG, text);
        sendStatus("connecting", text);
    }

    /**
     * «Одна кнопка» без подготовки: если пул пуст — быстро находим рабочие конфиги
     * (пинг → реальная проверка через Xray) и кладём их в пул.
     */
    private List<ProxyConfig> quickFind() {
        List<ProxyConfig> all = ConfigRepository.all(this);
        if (all.isEmpty()) {
            sendStatus("connecting", "Списков нет — скачиваю источники…");
            ConfigRepository.updateAll(this, new ConfigRepository.Progress() {
                @Override public void onMessage(String message) {
                    sendStatus("connecting", message);
                }
            });
            all = ConfigRepository.all(this);
        }
        if (all.isEmpty()) return new ArrayList<>();
        if (all.size() > QUICK_PING_CAP) all = new ArrayList<>(all.subList(0, QUICK_PING_CAP));

        NetChecker.PingStats net = NetChecker.pingAll(Prefs.pingTargets(this),
                Prefs.pingTimeoutMs(this));
        sendStatus("connecting", "Сеть: " + net.text());

        // Быстрый отсев: TCP-connect по серверам — до полного пинга с TLS.
        all = Blacklist.filter(this, all);
        sendStatus("connecting", "Быстрый отсев: " + all.size() + " конфигов…");
        FastPing.Outcome fast = FastPing.filter(this, all, null);
        if (fast.cancelled) return new ArrayList<>();
        all = fast.alive;
        sendStatus("connecting", "Быстрый отсев: " + fast.summary());
        if (all.isEmpty()) return new ArrayList<>();

        sendStatus("connecting", "Пингую " + all.size() + " конфигов…");
        ConfigTester.Outcome ping = ConfigTester.test(all, Prefs.pingThreads(this),
                Prefs.pingTimeoutMs(this), null);
        if (ping.cancelled || ping.alive.isEmpty()) {
            sendStatus("connecting", "Пинг: " + ping.summary());
            return new ArrayList<>();
        }
        sendStatus("connecting", "Пинг: " + ping.summary());

        List<ProxyConfig> found = new ArrayList<>();
        int probes = Math.min(ping.alive.size(), QUICK_PROBES);
        for (int i = 0; i < probes; i++) {
            if (stopRequested.get() || ConfigRepository.isCancelled()) break;
            ProxyConfig cfg = ping.alive.get(i);
            sendStatus("connecting", "Проверка " + (i + 1) + "/" + probes
                    + ": " + truncate(cfg.name, 30) + " · " + cfg.latency + " мс");
            Probe.Result r = Probe.measureConfig(this, cfg, Prefs.gateTimeoutMs(this));
            sendStatus("connecting", "  → " + r.text());
            if (r.ok && r.kbps > 0) {
                Pool.remember(this, cfg.raw, r.kbps, cfg.latency);
                Blacklist.forget(this, cfg.raw);
                found.add(cfg);
                if (found.size() >= 2) break;
            }
            // «Связность есть, скорость не измерена» и прочие жёсткие отказы —
            // в чёрный список: конфиг, который отвечает 204 и не отдаёт данные,
            // рабочим не считается.
            if (r.hard) Blacklist.add(this, cfg, r.reason);
        }
        return found;
    }

    /**
     * Поднять конфиг на свободном локальном порту и проверить скорость.
     *
     * @param dpiStrategy стратегия ciadpi для линии до сервера; null/"" — напрямую.
     *                    Если конфиг был замерен через обход, сюда передаётся ровно та
     *                    стратегия, что дала канал, — иначе обход подбирается заново.
     * @param myRun       поколение запуска: если пользователь нажал «Остановить»,
     *                    метод прекращает работу и НЕ поднимает канал.
     */
    private boolean activate(ProxyConfig cfg, String dpiStrategy, long myRun) {
        if (aborted(myRun)) {
            lastError = "Остановлено пользователем";
            return false;
        }
        boolean withDesync = dpiStrategy != null && !dpiStrategy.trim().isEmpty();
        int desyncPort = 0;
        boolean ok = false;
        try {
            if (!withDesync) stopDesync();
            if (withDesync) {
                desyncPort = startDesync(cfg, dpiStrategy);
                if (desyncPort <= 0) {
                    lastError = "DPI-обход линии не поднялся";
                    return false;
                }
            }
            // Ядро примет не всё: без TLS к публичному адресу оно откажется (Xray 26).
            // Если пользователь не разрешил ретранслятор — честно говорим об этом,
            // а не ждём «ядро не открыло локальный SOCKS».
            String bad = XrayEngine.compatProblem(cfg, Prefs.allowNoTls(this));
            if (bad != null) {
                lastError = bad;
                return false;
            }
            // Российские домены — мимо туннеля (раздельное туннелирование). Список
            // передаём только при реальном подключении: замеру он не нужен, а правила
            // маршрутизации в пробном конфиге только путали бы картину.
            java.util.List<String> direct = Prefs.splitRuDomains(this)
                    ? SplitTunnel.domains(this) : null;
            long kbps = startCoreAndMeasure(cfg, desyncPort, direct, myRun);
            if (kbps < 0 && direct != null && !direct.isEmpty() && !aborted(myRun)) {
                // Сплит — дополнительная возможность, а не условие подключения. Если с
                // правилами маршрутизации ядро не поднялось, поднимаем без них: иначе
                // включённый сплит выглядел бы как «ни один конфиг из пула не подключается».
                String splitError = lastError;
                Log.w(TAG, "со сплитом не вышло (" + splitError + ") — повторяю без него");
                kbps = startCoreAndMeasure(cfg, desyncPort, null, myRun);
                if (kbps >= 0) {
                    Log.i(TAG, "канал поднялся без правил сплита: " + splitError);
                } else if (lastError.isEmpty()) {
                    lastError = splitError;
                }
            }
            if (kbps < 0) {
                if (lastError.isEmpty()) lastError = "канал не поднялся";
                Log.i(TAG, "конфиг " + cfg.name + ": " + lastError);
                return false;
            }
            Prefs.setCurrentConfig(this, cfg.raw, cfg.name, cfg.host, cfg.port);
            // Стратегию записываем ту, с которой реально поднялось: иначе следующее
            // подключение начнёт «с чистого листа» и потеряет рабочий рецепт.
            Pool.remember(this, cfg.raw, kbps, cfg.latency, withDesync ? dpiStrategy.trim() : "");
            currentConfig = cfg;
            currentDetail = cfg.name + " · " + kbps + " КБ/с";
            ok = true;
            return true;
        } catch (IllegalArgumentException e) {
            lastError = "конфиг не собирается: " + e.getMessage();
            Log.w(TAG, lastError);
            XrayEngine.stop();
            return false;
        } catch (Throwable t) {
            lastError = "ошибка ядра: " + t.getMessage();
            Log.w(TAG, "activate failed", t);
            XrayEngine.stop();
            return false;
        } finally {
            if (!ok && desyncPort > 0) stopDesync();
            if (ok && cfg != null) {
                Blacklist.forget(this, cfg.raw);
                if (desyncPort > 0) DpiStrategies.rememberWorking(this, dpiStrategy.trim());
            }
        }
    }

    /**
     * Запуск ядра и замер скорости на СВОБОДНОМ порту.
     *
     * Порт свободный, как при замере в пуле. Фиксированный 10811 мог быть ещё занят
     * прошлым ядром (stopLoop асинхронный): новое ядро не биндилось, SOCKS не
     * открывался — и свежезамеренный конфиг «не проходил проверку при подключении»,
     * хотя секунду назад успешно измерялся. Замер — три адреса, как при сборке пула:
     * глубина проверки до и после одна и та же.
     *
     * @return измеренная скорость в КБ/с или -1; причина отказа — в {@link #lastError}
     */
    private long startCoreAndMeasure(ProxyConfig cfg, int desyncPort,
                                     java.util.List<String> direct, long myRun) {
        int port = Probe.freePort();
        if (port <= 0) port = XRAY_PORT;
        String json = XrayEngine.buildConfigJson(cfg, port, false,
                Prefs.vpnDns(this), desyncPort, direct);
        XrayEngine.start(this, json, new XrayEngine.Callback() {
            @Override public void onReady() { }
            @Override public void onError(String message) { Log.w(TAG, "xray: " + message); }
            @Override public void onLog(String line) { Log.i(TAG, line); }
        });
        Log.i(TAG, "ядро поднимается на порту " + port + (direct != null && !direct.isEmpty()
                ? " (сплит: " + direct.size() + " доменов)" : " (весь трафик в туннель)"));
        if (!Probe.waitSocks(port, Probe.SOCKS_WAIT_MS)) {
            lastError = "ядро не открыло локальный SOCKS (порт " + port + ")";
            XrayEngine.stop();
            return -1;
        }
        if (aborted(myRun)) {
            lastError = "Остановлено пользователем";
            XrayEngine.stop();
            return -1;
        }
        Probe.Speed speed = Probe.measureSpeed(this, port, Prefs.gateTimeoutMs(this), 3);
        // Ответ 204 без тела доказывает только связность. Скорости в нём нет, а без
        // скорости конфиг бесполезен: подключать «есть контакт, но качать нечего»
        // нельзя — это тот же нерабочий VPN, только выглядит живым.
        boolean onlyAlive = !speed.ok() && speed.alive();
        long kbps = speed.ok() ? speed.kbps : -1;
        long need = Math.max(1L, (long) (Prefs.minSpeedKbps(this) * MIN_SPEED_TOLERANCE));
        if (kbps < need) {
            lastError = onlyAlive
                    ? "связность есть, скорость не измерена"
                    : "скорость " + (kbps < 0 ? "не измерена" : Prefs.speedText(kbps))
                            + " ниже порога " + Prefs.minSpeedText(this)
                            + (speed.error.isEmpty() ? "" : " · " + speed.error);
            XrayEngine.stop();
            return -1;
        }
        activeSocksPort = port;
        localSocksPort = port;
        currentSocksPort = port;
        Log.i(TAG, "канал на порту " + port + ": " + Prefs.speedText(kbps));
        return kbps;
    }

    /** Локальный ciadpi, через который идёт линия до VPN-сервера. 0 — не поднялся. */
    private int startDesync(ProxyConfig cfg, String dpiStrategy) {
        stopDesync();
        try {
            String sni = cfg != null ? cfg.tlsServerName() : null;
            if (sni == null || sni.isEmpty()) {
                sni = (cfg != null && cfg.host != null && !cfg.host.isEmpty()) ? cfg.host : "www.youtube.com";
            }
            String use = dpiStrategy == null ? "" : dpiStrategy.trim();
            if (use.isEmpty()) use = Prefs.dpiStrategyFor(this);
            // Автоподбор — один раз за сессию и только для «стратегии из настроек»:
            // если пришла стратегия из пула, она уже доказала, что пробивает блокировку.
            if (Prefs.dpiAutotune(this) && !dpiAutotuned && use.equals(Prefs.dpiStrategyFor(this))) {
                dpiAutotuned = true;
                sendStatus("connecting", "Подбираю стратегию обхода DPI…");
                Log.i(TAG, "DPI: автоподбор, стартовая — " + DpiStrategies.shorten(Prefs.dpiStrategyFor(this)));
                String chosen = DpiStrategies.autotune(this, sni, new DpiStrategies.Log() {
                    @Override public void line(String message) {
                        Log.i(TAG, message);
                        sendStatus("connecting", message);
                    }
                });
                Log.i(TAG, "DPI: выбрана " + DpiStrategies.shorten(chosen));
                sendStatus("connecting", "Стратегия обхода: " + DpiStrategies.shorten(chosen));
                if (chosen != null && !chosen.trim().isEmpty()) use = chosen.trim();
            }
            int port = Probe.freePort();
            if (port <= 0) return 0;
            Log.i(TAG, "DPI: стратегия — " + DpiStrategies.shorten(use));
            String[] args = DpiArgs.build(use, port, sni);
            ByeDpiProxy proxy = new ByeDpiProxy();
            desyncProxy = proxy;
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        proxy.startProxy(args);
                    } catch (Throwable ignored) {
                    }
                }
            }, "byedpi-line");
            t.setDaemon(true);
            t.start();
            if (Probe.waitSocks(port, 4000)) return port;
            // Нативный мост может игнорировать {port} и слушать дефолтный 1080.
            if (Probe.waitSocks(1080, 1000)) {
                Log.i(TAG, "ciadpi слушает 1080 — используем его");
                return 1080;
            }
            stopDesync();
            return 0;
        } catch (Throwable t) {
            stopDesync();
            return 0;
        }
    }

    private void stopDesync() {
        ByeDpiProxy p = desyncProxy;
        desyncProxy = null;
        if (p != null) {
            try {
                p.stopProxy();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ TUN и hev

    private ParcelFileDescriptor establishTun() {
        return establishTun(null);
    }

    /**
     * @param dnsOverride если задан — этот DNS отдаём системе вместо настроенного.
     *                    В режиме Tor это адрес mapdns из hev: запросы перехватываются
     *                    на месте, а имена резолвит сам Tor (UDP у Tor нет).
     */
    private ParcelFileDescriptor establishTun(String dnsOverride) {
        Builder builder = new Builder();
        builder.setSession("ОбходБелыхСписков");
        builder.addAddress("10.10.10.10", 32);
        builder.addRoute("0.0.0.0", 0);
        if (Prefs.ipv6(this)) {
            builder.addAddress("fd00::1", 128);
            builder.addRoute("::", 0);
        }
        String dns = dnsOverride != null ? dnsOverride : Prefs.vpnDns(this);
        if (dns != null && !dns.trim().isEmpty()) {
            for (String d : dns.split("[,;\\s]+")) {
                if (!d.trim().isEmpty()) builder.addDnsServer(d.trim());
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (Exception e) {
            Log.w(TAG, "не удалось исключить себя из TUN", e);
        }
        // Раздельное туннелирование: приложения из списка ходят как без VPN — их трафик
        // в tun не попадает вообще. Это надёжнее доменных правил: банк или госуслуги
        // лезут и на CDN, и по IP, а такой трафик доменом не поймать.
        // Работает во всех режимах, включая Tor: исключённое приложение просто не видит
        // туннель и открывается с обычного канала — ровно то, что нужно банку и госуслугам.
        if (Prefs.splitRuApps(this)) {
            java.util.List<String> excluded = SplitTunnel.installedPackages(this);
            int ok = 0;
            for (String pkg : excluded) {
                try {
                    builder.addDisallowedApplication(pkg);
                    ok++;
                } catch (Throwable t) {
                    Log.w(TAG, "пакет не исключён из TUN: " + pkg, t);
                }
            }
            if (ok > 0) Log.i(TAG, "раздельное туннелирование: " + ok + " приложений вне VPN");
        }
        return builder.establish();
    }

    /**
     * @param mapdns true — включить перехват DNS (режим Tor): запросы к {@link TorTunnel#DNS}
     *                hev обрабатывает сам и отдаёт «fake IP», а имена резолвит удалённая
     *                сторона SOCKS (то есть Tor) при подключении.
     */
    private void startTunnel(final int socksPort, boolean mapdns) {
        if (tun == null) throw new IllegalStateException("TUN не создан");
        final String yaml = "tunnel:\n" +
                "  mtu: 8500\n" +
                (mapdns
                        ? "mapdns:\n" +
                          "  address: " + TorTunnel.DNS + "\n" +
                          "  port: 53\n" +
                          "  network: " + TorTunnel.NETWORK + "\n" +
                          "  netmask: " + TorTunnel.NETMASK + "\n" +
                          "  cache-size: 10000\n"
                        : "") +
                "misc:\n" +
                "  task-stack-size: 81920\n" +
                "socks5:\n" +
                "  address: 127.0.0.1\n" +
                "  port: " + socksPort + "\n" +
                "  udp: udp\n";
        final int fd = tun.getFd();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                int code = new TProxyService().startTunnel(yaml, fd);
                if (!stopRequested.get()) Log.w(TAG, "hev-socks5-tunnel завершился: " + code);
            }
        }, "hev-tunnel");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ монитор канала

    private void startMonitor() {
        monitorThread = new Thread(new Runnable() {
            @Override public void run() {
                int fails = 0;
                int intervalMs = Prefs.monitorIntervalSec(BypassVpnService.this) * 1000;
                int maxFail = Math.max(1, Prefs.switchTimeoutSec(BypassVpnService.this)
                        / Math.max(1, Prefs.monitorIntervalSec(BypassVpnService.this)));
                while (!stopRequested.get()) {
                    try {
                        Thread.sleep(intervalMs);
                    } catch (InterruptedException e) {
                        break;
                    }
                    if (stopRequested.get()) break;
                    if (checkAlive()) {
                        fails = 0;
                        continue;
                    }
                    fails++;
                    Log.w(TAG, "канал не отвечает: " + fails + "/" + maxFail);
                    if (fails >= maxFail && Prefs.autoSwitch(BypassVpnService.this)) {
                        fails = 0;
                        switchToNext(true);
                    }
                }
            }
        }, "bypass-monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    /**
     * Канал жив, если через туннель ответило ХОТЯ БЫ ОДНО из пяти мест.
     * Раньше проверялся один URL — один недоступный сайт валил весь монитор.
     */
    private boolean checkAlive() {
        List<String> urls = Prefs.healthUrls(this);
        if (urls.isEmpty()) return true;
        int ok = NetChecker.countAliveThroughSocks(urls, "127.0.0.1", localSocksPort, 6000);
        if (ok == 0) {
            Log.w(TAG, "канал не отвечает ни на одну из " + urls.size() + " целей проверки");
        }
        return ok > 0;
    }

    /** Переключение на следующий по скорости конфиг из пула. TUN и hev не перезапускаем. */
    /**
     * @param blameCurrent true — вызвано монитором: текущий конфиг перестал отвечать.
     *                     Тогда после удачного переключения он уходит из пула и в чёрный
     *                     список. Если же не поднялся никто, виновата сеть, а не конфиг —
     *                     никого не трогаем.
     */
    private void switchToNext(boolean blameCurrent) {
        final long myRun = run.get();
        if (dpiOnly) {
            // В режиме «только DPI» конфигов нет — переключать нечего.
            sendStatus("on", currentDetail.isEmpty() ? "Только DPI" : currentDetail);
            return;
        }
        ProxyConfig previous = currentConfig;
        // Весь пул, а не первые 6: «Переключить» должно постепенно проходить все конфиги.
        List<Pool.Entry> list = connectCandidates(Integer.MAX_VALUE);
        String currentRaw = previous != null ? previous.raw : "";
        boolean wasTor = torMode;

        // Идём от текущего конфига вперёд и запоминаем курсор: следующее нажатие
        // продолжит дальше, а после конца пула начнёт сначала. Иначе кнопка всегда
        // пробовала два самых быстрых — и при живом первом конфиге не переключала вовсе.
        int size = list.size();
        int start = 0;
        if (size > 0) {
            int currentIdx = -1;
            for (int i = 0; i < size; i++) {
                if (list.get(i).raw.equals(currentRaw)) {
                    currentIdx = i;
                    break;
                }
            }
            start = currentIdx >= 0 ? currentIdx + 1 : Math.min(switchCursor, size - 1);
            if (start >= size) start = 0;
        }
        int tried = 0;
        for (int k = 0; k < size && tried < MAX_SWITCH_TRIES; k++) {
            Pool.Entry entry = list.get((start + k) % size);
            ProxyConfig cfg = entry.cfg();
            if (cfg == null) continue;
            if (stopRequested.get()) return;
            if (cfg.raw.equals(currentRaw) && tried == 0) continue;   // куда переключаться — начиная со следующего
            tried++;
            switchCursor = (start + k + 1) % size;
            sendStatus("connecting", "Переключаюсь: " + truncate(cfg.name, 42) + "…");
            boolean ok;
            if (!entry.dpi.isEmpty()) {
                ok = activate(cfg, entry.dpi, myRun);          // как при замере: с обходом
                if (!ok && aborted(myRun)) return;
                if (!ok) ok = activate(cfg, null, myRun);      // и один раз напрямую
            } else {
                ok = activate(cfg, null, myRun);               // как при замере: напрямую
                if (!ok && aborted(myRun)) return;
                if (!ok && Prefs.desyncLine(this)) {
                    ok = activate(cfg, Prefs.dpiStrategyFor(this), myRun);
                }
            }
            if (ok) {
                if (wasTor) {
                    // Из резервного Tor обратно на конфиг: hev смотрел в 9050 — переводим
                    // на локальный SOCKS ядра, иначе туннель остался бы на Tor.
                    TorTunnel.stop(this);
                    torMode = false;
                    torRequested = false;
                    currentMode = MODE_XRAY;
                    teardownTunnel();
                    tun = establishTun();
                    if (tun == null) {
                        fail("Не удалось создать TUN после переключения");
                        return;
                    }
                    if (aborted(myRun)) {
                        teardownTunnel();
                        running.set(false);
                        releaseWakeLock();
                        return;
                    }
                    localSocksPort = activeSocksPort > 0 ? activeSocksPort : XRAY_PORT;
                    startTunnel(localSocksPort, false);
                }
                currentMode = MODE_XRAY;
                localSocksPort = activeSocksPort > 0 ? activeSocksPort : XRAY_PORT;
                // Переключились — значит дело было в старом конфиге, а не в сети.
                if (blameCurrent && previous != null
                        && (currentConfig == null || !previous.raw.equals(currentConfig.raw))) {
                    discard(previous, "канал перестал отвечать");
                }
                sendStatus("on", currentDetail);
                return;
            }
            discard(cfg, lastError);   // не поднялся — из пула в чёрный список
        }
        // Ни один конфиг не поднялся: пробуем резервный Tor, иначе — честная ошибка.
        if (torMode || tryTorFallback("Канал не отвечает, других рабочих конфигов нет")) return;
        sendStatus("error", "Канал не отвечает, других рабочих конфигов в пуле нет. "
                + "Нажмите «Собрать пул».");
    }

    // ------------------------------------------------------------------ пауза

    /**
     * Пауза: снимаем TUN и tun2socks — трафик идёт с хоста напрямую, как будто VPN нет.
     * Ядро Xray не гасим: продолжение не требует новой проверки конфига (и пауза честно
     * выглядит паузой, а не «выключено»). Монитор останавливаем — иначе он принял бы
     * отсутствие канала за сбой и начал бы менять конфиги прямо под паузой.
     */
    private void pauseTunnel() {
        if (!running.get()) {
            sendStatus("off", "Туннель не запущен");
            return;
        }
        if (paused) {
            sendStatus("paused", "Пауза · трафик идёт напрямую");
            return;
        }
        paused = true;
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }
        teardownTunnel();
        releaseWakeLock();
        Log.i(TAG, "пауза: TUN снят, трафик идёт напрямую");
        sendStatus("paused", "Пауза · трафик идёт напрямую");
    }

    /** Продолжить: поднять TUN на том же конфиге — без нового перебора и замера. */
    private void resumeTunnel() {
        if (!paused) return;
        paused = false;
        sendStatus("connecting", "Продолжаю…");
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                final long myRun = run.get();
                try {
                    if (!Probe.waitSocks(localSocksPort, 1500)) {
                        // Ядро всё-таки умерло (например, систему придушила память):
                        // честно проходим весь путь заново, а не делаем вид, что всё хорошо.
                        Log.w(TAG, "продолжение: локальный SOCKS не отвечает — подключаюсь заново");
                        resetEngine();
                        stopRequested.set(false);
                        startWorker();
                        return;
                    }
                    ParcelFileDescriptor fd = establishTun(
                            (torMode || dpiOnly) ? TorTunnel.DNS : null);
                    if (fd == null) {
                        fail("Не удалось создать TUN после паузы (VPN не разрешён?)");
                        return;
                    }
                    tun = fd;
                    if (aborted(myRun)) {
                        teardownTunnel();
                        running.set(false);
                        releaseWakeLock();
                        Log.i(TAG, "продолжение отменено — TUN закрыт");
                        return;
                    }
                    startTunnel(localSocksPort, torMode || dpiOnly);
                    running.set(true);
                    acquireWakeLock();
                    if (!dpiOnly) startMonitor();   // в режиме DPI переключать нечего: конфига нет
                    sendStatus("on", currentDetail);
                    Log.i(TAG, "продолжение: туннель поднят снова");
                } catch (Throwable e) {
                    Log.e(TAG, "продолжение не удалось", e);
                    fail("Не удалось продолжить: " + e.getMessage());
                }
            }
        }, "bypass-resume");
        t.setDaemon(true);
        workerThread = t;
        t.start();
    }

    // ------------------------------------------------------------------ остановка

    private void fail(String message) {
        sendStatus("error", message);
        stopEverythingInternal(false);
    }

    private void stopEverything() {
        stopEverythingInternal(true);
    }

    /** Погасить движок, TUN и монитор, но САМ сервис не останавливать (для перезапуска). */
    private void resetEngine() {
        stopRequested.set(true);
        // Любой поток, который ещё поднимает канал, теперь «чужой» — и прерывается,
        // чтобы не ждать таймаутов SOCKS и не создать TUN после остановки.
        run.incrementAndGet();
        Thread w = workerThread;
        workerThread = null;
        if (w != null && w != Thread.currentThread()) {
            try {
                w.interrupt();
            } catch (Throwable ignored) {
            }
        }
        running.set(false);
        paused = false;
        torRequested = false;
        switchCursor = 0;
        currentSocksPort = -1;
        currentDetail = "";
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }
        try {
            new TProxyService().stopTunnel();
        } catch (Throwable ignored) {
        }
        try {
            XrayEngine.stop();
        } catch (Throwable ignored) {
        }
        if (torMode) {
            torMode = false;
            TorTunnel.stop(this);
        }
        if (dpiOnly) {
            DesyncLine.stop();          // линия «только DPI» живёт вне Xray
            dpiOnly = false;
            dpiOnlyPort = 0;
        }
        forcedRaw = null;
        currentMode = MODE_XRAY;
        localSocksPort = XRAY_PORT;
        activeSocksPort = XRAY_PORT;
        currentSocksPort = -1;
        stopDesync();
        try {
            if (tun != null) tun.close();
        } catch (Exception ignored) {
        }
        tun = null;
        releaseWakeLock();
    }

    private void stopEverythingInternal(boolean sendOff) {
        resetEngine();
        // stopRequested остаётся true: монитор и стартующий поток должны завершиться
        stopRequested.set(true);
        if (sendOff) sendStatus("off", "Отключено");
        stopForegroundCompat();
        stopSelf();
        // Страховка. Поток, который поднимал канал, мог проскочить все проверки до стопа:
        // тогда сервис уже уничтожен, а созданный им TUN некому закрыть — система держит
        // VPN поднятым, и «Остановить» выглядит нерабочим. Через секунду закрываем всё
        // ещё раз, но только если за это время не начался новый запуск.
        final long stoppedRun = run.get();
        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    if (stoppedRun != run.get() || !stopRequested.get()) return;
                    try {
                        new TProxyService().stopTunnel();
                    } catch (Throwable ignored) {
                    }
                    try {
                        XrayEngine.stop();
                    } catch (Throwable ignored) {
                    }
                    try {
                        if (tun != null) tun.close();
                    } catch (Exception ignored) {
                    }
                    tun = null;
                    running.set(false);
                    paused = false;
                    if (torMode) {
                        torMode = false;
                        TorTunnel.stop(BypassVpnService.this);
                    }
                    stopDesync();
                    releaseWakeLock();
                    stopForegroundCompat();
                    stopSelf();
                    Log.i(TAG, "страховочная остановка: TUN закрыт, сервис снят");
                }
            }, 1200);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ фон

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "whitelistbypass:vpn");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(4 * 60 * 60 * 1000L);
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        wakeLock = null;
    }

    // ------------------------------------------------------------------ уведомление и статусы

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "VPN-туннель", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    /** Уведомление с тремя действиями: «Пауза/Продолжить», «Остановить», «Переключить». */
    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, BypassVpnService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent swtch = PendingIntent.getService(this, 2,
                new Intent(this, BypassVpnService.class).setAction(ACTION_SWITCH),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pause = PendingIntent.getService(this, 3,
                new Intent(this, BypassVpnService.class).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent resume = PendingIntent.getService(this, 4,
                new Intent(this, BypassVpnService.class).setAction(ACTION_RESUME),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle("Обход Белых Списков")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .addAction(paused ? R.drawable.ic_stat_play : R.drawable.ic_stat_pause,
                        paused ? "Продолжить" : "Пауза",
                        paused ? resume : pause)
                .addAction(R.drawable.ic_stat_stop, "Остановить", stop);
        // В режиме «только DPI» переключать нечего: конфига нет, линия одна.
        if (!dpiOnly) builder.addAction(R.drawable.ic_stat_switch, "Переключить", swtch);
        return builder.build();
    }

    /** Обновить текст уведомления (снимается в stopForegroundCompat). */
    private void updateNotification(String state, String detail) {
        if (!running.get() && !"connecting".equals(state)) return;
        String text;
        if ("on".equals(state)) {
            text = (detail == null || detail.isEmpty()) ? "Подключено" : detail;
        } else if ("connecting".equals(state)) {
            text = (detail == null || detail.isEmpty()) ? "Подключение…" : detail;
        } else if ("paused".equals(state)) {
            text = "Пауза · трафик идёт напрямую";
        } else if ("error".equals(state)) {
            text = "Ошибка: " + (detail == null ? "" : detail);
        } else {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(truncate(text, 110)));
        } catch (Throwable t) {
            Log.w(TAG, "не удалось обновить уведомление", t);
        }
    }

    private void startForegroundCompat() {
        Notification notification = buildNotification("Подключение…");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void stopForegroundCompat() {
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
    }

    private void sendStatus(String state, String detail) {
        connectingFlag = "connecting".equals(state);
        if (!"off".equals(state)) updateNotification(state, detail);
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_STATE, state);
        i.putExtra(EXTRA_DETAIL, detail);
        i.putExtra(EXTRA_MODE, currentMode);
        sendBroadcast(i);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
