package ru.whitelist.bypass;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.util.Log;

import ru.whitelist.bypass.jni.ByeDpiProxy;
import ru.whitelist.bypass.jni.TProxyService;

import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VPN-сервис «Обход Белых Списков».
 * Режимы:
 *   dpi — локальный DPI-обход (hufrea/byedpi) → hev-socks5-tunnel → TUN;
 *   vpn — VLESS/Trojan/Shadowsocks/VMess через Xray-core → hev → TUN;
 *   tor — Tor (tor-android) с мостами → hev → TUN.
 * Все движки работают ВНУТРИ процесса приложения, поэтому трафик движков
 * не попадает обратно в TUN (приложение исключается из туннеля).
 */
public class BypassVpnService extends VpnService {

    public static final String ACTION_START = "ru.whitelist.bypass.action.START";
    public static final String ACTION_STOP = "ru.whitelist.bypass.action.STOP";
    public static final String ACTION_STATUS = "ru.whitelist.bypass.action.STATUS";
    public static final String ACTION_SWITCHED = "ru.whitelist.bypass.action.SWITCHED";

    public static final String EXTRA_STATE = "state";     // off | connecting | on | error
    public static final String EXTRA_DETAIL = "detail";
    public static final String EXTRA_MODE = "mode";

    private static final String CHANNEL_ID = "bypass_vpn";
    private static final int NOTIFICATION_ID = 1001;
    private static final String TAG = "BypassVpnService";

    private static final int XRAY_SOCKS_PORT = 10811;
    private static final int DPI_PROBE_PORT_BASE = 10910;

    // Допроверка запуска: обязательны 1 МБ ≤ 2000 мс; < 500 мс — «быстрый канал»
    // (в фоне молча докачивается один текстовый файл: VPN — конфиги, Tor — мосты).
    private static final long GATE_MS = 2000;
    private static final long FAST_MS = 500;
    private static final long GATE_MAX_BYTES = 1024L * 1024L;
    private static final long GATE_MIN_BYTES = 512L * 1024L; // не считать страницу ошибки «успешной» загрузкой
    private static final int DPI_MAX_ATTEMPTS = 12;

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile String currentDetail = "";
    private static volatile int currentSocksPort = -1;

    public static boolean isRunning() { return running.get(); }
    public static String currentDetail() { return currentDetail; }
    public static int currentSocksPort() { return currentSocksPort; }

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private ParcelFileDescriptor tun;
    private String mode = "dpi";
    private ProxyConfig currentConfig;
    private String currentStrategy = "";
    private ByeDpiProxy byedpi;
    private ByeDpiProxy desyncProxy;
    /** Победитель «гонки топ-3» (отдельный Xray-инстанс, не singleton). */
    private volatile libv2ray.CoreController raceWinner;
    private static final int RACE_PORT_BASE = 18610;
    private Thread monitorThread;
    private PowerManager.WakeLock wakeLock;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public void onRevoke() {
        Log.i(TAG, "VPN revoked by system");
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
        if (ACTION_START.equals(action)) {
            if (running.get() || tun != null) {
                // повторный старт = перезапуск
                stopEverythingInternal(false);
            }
            stopRequested.set(false);
            ConfigRepository.clearCancel();
            mode = intent.getStringExtra(EXTRA_MODE);
            if (mode == null) mode = Prefs.mode(this);
            startForegroundCompat();
            sendStatus("connecting", "Запуск режима " + modeName(mode), mode);
            Thread starter = new Thread(this::startEngineSafely, "bypass-start");
            starter.setDaemon(true);
            starter.start();
        }
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------ старт

    private void startEngineSafely() {
        try {
            // 1) TUN-интерфейс
            tun = establishTun();
            if (tun == null) {
                fail("Не удалось создать TUN (VPN не разрешён?)");
                return;
            }
            // 2) движок по режиму
            boolean started;
            switch (mode) {
                case "vpn":
                    currentEngine = "vpn";
                    started = startXray();
                    break;
                case "tor":
                    currentEngine = "tor";
                    started = startTor();
                    break;
                case "auto":
                    currentEngine = "vpn";
                    autoStage = 0;
                    sendStatus("connecting", "Авто: пробую VPN (конфиги)…", mode);
                    started = startXray();
                    break;
                case "dpi":
                default:
                    currentEngine = "dpi";
                    started = startDpi(false);
                    break;
            }
            if (!started) return; // startEngineSafely НЕ продолжаем после отказа
            running.set(true);
            acquireWakeLock();
            startMonitor();
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            fail(e.getMessage() != null ? e.getMessage() : "Ошибка запуска");
        }
    }

    private ParcelFileDescriptor establishTun() {
        Builder builder = new Builder();
        builder.setSession("ОбходБелыхСписков");
        builder.addAddress("10.10.10.10", 32);
        builder.addRoute("0.0.0.0", 0);
        if (Prefs.ipv6(this)) {
            builder.addAddress("fd00::1", 128);
            builder.addRoute("::", 0);
        }
        String dns = Prefs.vpnDns(this);
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
            Log.w(TAG, "cannot disallow self", e);
        }
        return builder.establish();
    }

    /** Сколько конфигов пробовать подряд, пока один не откроет SOCKS. */
    private static final int MAX_VPN_CONNECT_ATTEMPTS = 8;

    private List<ProxyConfig> buildVpnCandidates() {
        // Приоритет: выбранный → «Вершина» → прошедшие живой тест → остальные.
        // Ничего не отбрасываем наглухо: в каждом ведре — по возрастанию пинга.
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<ProxyConfig> out = new ArrayList<>();
        String raw = Prefs.currentConfigRaw(this);
        if (raw != null && !raw.trim().isEmpty()) {
            ProxyConfig c = ProxyConfig.parse(raw, "выбранный", false);
            if (c != null && c.supported && seen.add(c.raw)) out.add(c);
        }
        java.util.Set<String> towerRaw = new java.util.HashSet<>();
        for (String line : ConfigRepository.loadTower(this)) {
            ProxyConfig c = ProxyConfig.parse(line, "вершина", false);
            if (c != null) towerRaw.add(c.raw);
        }
        java.util.Set<String> dataOk = ConfigRepository.loadDataOk(this);
        List<ProxyConfig> ranked = new ArrayList<>(ConfigRepository.activeList(this));
        ranked.sort((a, b) -> {
            int ba = bucketOf(a, towerRaw, dataOk), bb = bucketOf(b, towerRaw, dataOk);
            if (ba != bb) return Integer.compare(ba, bb);
            long la = a.latency < 0 ? Long.MAX_VALUE : a.latency;
            long lb = b.latency < 0 ? Long.MAX_VALUE : b.latency;
            return Long.compare(la, lb);
        });
        for (ProxyConfig p : ranked) {
            if (out.size() >= MAX_VPN_CONNECT_ATTEMPTS) break;
            if (!p.supported || !seen.add(p.raw)) continue;
            out.add(p);
        }
        return out;
    }

    private static int bucketOf(ProxyConfig p, java.util.Set<String> towerRaw,
                                java.util.Set<String> dataOk) {
        if (towerRaw.contains(p.raw)) return 0;
        if (dataOk.contains(ConfigRepository.configHash(p.raw))) return 1;
        return 2;
    }

    private boolean startXray() {
        List<ProxyConfig> candidates = buildVpnCandidates();
        if (candidates.isEmpty()) {
            engineFailed("Нет подходящих конфигов. Нажмите «Обновить конфиги» — источники могут быть недоступны напрямую, но скачаются через зеркало.");
            return false;
        }
        // «Автогонка»: топ-3 одновременно; кто первый пройдёт — тот и туннель.
        if (Prefs.vpnRace(this) && candidates.size() >= 2) {
            if (tryRaceTop3(candidates)) return true;
        }
        final boolean desyncWanted = Prefs.vpnDesync(this);
        int attempt = 0;
        for (ProxyConfig cfg : candidates) {
            attempt++;
            currentConfig = cfg;
            currentDetail = cfg.shortLabel();
            currentSocksPort = XRAY_SOCKS_PORT;
            // эксперимент: DPI-обход на линии до VPN-сервера (линия идёт через локальный ByeDPI).
            // Порядок: с обходом → без обхода; канал мог быть жив всегда, просто сайт пробитый.
            boolean desyncTried = false;
            if (desyncWanted) {
                int desyncPort = startDesyncProxy(cfg);
                if (desyncPort > 0) {
                    desyncTried = true;
                    if (tryCandidate(cfg, attempt, true, desyncPort)) return true;
                }
                stopDesyncProxy();
            }
            if (tryCandidate(cfg, attempt, false, 0)) return true;
            if (desyncTried) {
                Log.i(TAG, "кандидат " + attempt + " не вышел ни с обходом, ни без него");
            }
        }
        stopDesyncProxy();
        engineFailed("Не удалось подключиться ни к одному из " + attempt
                + " конфигов. Обновите список: «Обновить конфиги».");
        return false;
    }

    /**
     * «Автогонка топ-3»: поднимаем до трёх лучших конфигов ОДНОВРЕМЕННО на свободных портах;
     * первый, кто открыл SOCKS и ответил FOREIGN-запросу, забирает туннель (и остаётся жить),
     * остальные останавливаются. Ничего не получается — возвращаем false, дальше обычный перебор.
     */
    private boolean tryRaceTop3(List<ProxyConfig> candidates) {
        final int n = Math.min(3, candidates.size());
        List<libv2ray.CoreController> ctrls = new ArrayList<>();
        List<Integer> ports = new ArrayList<>();
        List<ProxyConfig> used = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ProxyConfig cfg = candidates.get(i);
            int port = allocatePort(RACE_PORT_BASE + i * 2);
            if (port <= 0) continue;
            try {
                String json = XrayEngine.buildConfigJson(cfg, port, Prefs.vpnInsecure(this),
                        Prefs.vpnDns(this), 0);
                ctrls.add(XrayEngine.startInstance(this, json));
                ports.add(port);
                used.add(cfg);
            } catch (Throwable t) {
                Log.w(TAG, "гонка: старт #" + i + " не вышел: " + t);
            }
        }
        if (ctrls.isEmpty()) return false;
        sendStatus("connecting", "Автогонка: поднимаю " + ctrls.size() + " конфига одновременно…", mode);
        long deadline = System.currentTimeMillis() + 12_000;
        String probe = Prefs.speedGateUrls(this).isEmpty()
                ? "https://speed.cloudflare.com/__down?bytes=1024"
                : Prefs.speedGateUrls(this).get(0);
        while (System.currentTimeMillis() < deadline && !stopRequested.get()) {
            if (ConfigRepository.isCancelled()) break;
            for (int i = 0; i < ports.size(); i++) {
                int port = ports.get(i);
                if (waitSocksReady("127.0.0.1", port, 200)
                        && NetChecker.httpCheckThroughSocks(probe, "127.0.0.1", port, 5000)) {
                    raceWinner = ctrls.get(i);
                    for (int j = 0; j < ctrls.size(); j++) {
                        if (j != i) {
                            try { XrayEngine.stopInstance(ctrls.get(j)); } catch (Throwable ignored) { }
                        }
                    }
                    ProxyConfig win = used.get(i);
                    currentConfig = win;
                    currentSocksPort = port;
                    currentDetail = win.shortLabel();
                    Prefs.setCurrentConfig(this, win.raw, win.name, win.host, win.port);
                    startTunnel(port);
                    sendStatus("on", "Автогонка: " + currentDetail, mode);
                    sendSwitch("Подключено гонкой топ-3: " + win.name);
                    maybeQuietOnFastLink();
                    return true;
                }
            }
            try { Thread.sleep(250); } catch (InterruptedException e) { break; }
        }
        for (libv2ray.CoreController ctl : ctrls) {
            try { XrayEngine.stopInstance(ctl); } catch (Throwable ignored) { }
        }
        sendStatus("connecting", "Автогонка не решила — перехожу к последовательному перебору…", mode);
        return false;
    }

    /** Одна попытка подключения конфигом (с DPI-обходом на линии до сервера или без). */
    private boolean tryCandidate(ProxyConfig cfg, int attempt, boolean withDesync, int desyncSocksPort) {
        final String dns = Prefs.vpnDns(this);
        final String json;
        try {
            json = XrayEngine.buildConfigJson(cfg, XRAY_SOCKS_PORT, Prefs.vpnInsecure(this), dns,
                    withDesync ? desyncSocksPort : 0);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "config build failed for " + cfg.name, e);
            sendStatus("connecting", "Xray: #" + attempt + " — конфиг не собирается ("
                    + e.getMessage() + "), пробую следующий…", mode);
            return false;
        }
        sendStatus("connecting", "Xray: попытка " + attempt + "/" + MAX_VPN_CONNECT_ATTEMPTS
                + " — " + cfg.name + (withDesync ? " (+DPI-обход)" : ""), mode);
        XrayEngine.start(this, json, new XrayEngine.Callback() {
            @Override
            public void onReady() {
                // ядро поднялось
            }

            @Override
            public void onError(String message) {
                sendStatus("error", "Xray: " + message, mode);
            }

            @Override
            public void onLog(String line) {
                Log.i(TAG, line);
            }
        });
        if (waitSocksReady("127.0.0.1", XRAY_SOCKS_PORT, 10_000)) {
            if (!speedGatePass(XRAY_SOCKS_PORT)) {
                XrayEngine.stop();
                sendStatus("connecting", "Конфиг «" + truncate(cfg.name, 40)
                        + "» — канал не отвечает на проверку, пробую другой…", mode);
                return false;
            }
            // запоминаем рабочий конфиг — следующий запуск пойдёт с него
            Prefs.setCurrentConfig(this, cfg.raw, cfg.name, cfg.host, cfg.port);
            startTunnel(XRAY_SOCKS_PORT);
            sendStatus("on", currentDetail, mode);
            if (attempt > 1) {
                sendSwitch("Подключился с попытки " + attempt + ": " + cfg.name);
            }
            maybeQuietOnFastLink();
            return true;
        }
        XrayEngine.stop();
        sendStatus("connecting", "Конфиг «" + truncate(cfg.name, 40)
                + "» не открыл локальный SOCKS — пробую следующий…", mode);
        return false;
    }

    /** Эксперимент: локальный ByeDPI для линии до VPN-сервера. Возвращает порт или -1. */
    private int startDesyncProxy(ProxyConfig cfg) {
        stopDesyncProxy();
        try {
            String sni = cfg != null ? cfg.tlsServerName() : null;
            if (sni == null || sni.isEmpty()) {
                sni = (cfg != null && cfg.host != null && !cfg.host.isEmpty())
                        ? cfg.host : "www.youtube.com";
            }
            int port = allocatePort(49220);
            String[] args = DpiArgs.build(Prefs.strategy(this), port, sni);
            ByeDpiProxy proxy = new ByeDpiProxy();
            desyncProxy = proxy;
            Thread t = new Thread(() -> {
                try {
                    proxy.startProxy(args);
                } catch (Throwable tr) {
                    Log.w(TAG, "vpn-desync завершился с ошибкой", tr);
                }
            }, "byedpi-vpn");
            t.setDaemon(true);
            t.start();
            if (waitSocksReady("127.0.0.1", port, 1_500)) {
                Log.i(TAG, "vpn-desync поднят на порту " + port + " (sni=" + sni + ")");
                return port;
            }
            Log.w(TAG, "vpn-desync не открыл SOCKS за 1.5 с — подключаемся без него");
            stopDesyncProxy();
            return -1;
        } catch (Throwable t) {
            Log.w(TAG, "vpn-desync не стартовал — подключаемся без него", t);
            stopDesyncProxy();
            return -1;
        }
    }

    private void stopRaceWinner() {
        libv2ray.CoreController w = raceWinner;
        raceWinner = null;
        if (w != null) {
            try { XrayEngine.stopInstance(w); } catch (Throwable ignored) { }
        }
    }

    private void stopDesyncProxy() {
        try {
            if (desyncProxy != null) desyncProxy.stopProxy();
        } catch (Throwable ignored) {
        }
        desyncProxy = null;
    }

    private boolean startTor() {
        sendStatus("connecting", "Запуск Tor (ждём цепочку, до 150 с)…", mode);
        currentDetail = "Tor";
        currentSocksPort = TorEngine.SOCKS_PORT;
        TorEngine.start(this, new TorEngine.Callback() {
            @Override
            public void onReady(int socksPort) {
                torEngineReady = true;
                if (!speedGatePass(socksPort)) {
                    engineFailed("Tor поднял цепочку, но канал не ответил на проверку. "
                            + "Обновите мосты в настройках («Обновить мосты»).");
                    return;
                }
                startTunnel(socksPort);
                sendStatus("on", "Tor (SOCKS " + socksPort + ")", mode);
                maybeQuietOnFastLink();
            }

            @Override
            public void onError(String message) {
                engineFailed(message);
            }

            @Override
            public void onLog(String line) {
                Log.i(TAG, line);
            }
        });
        return true;
    }

    // ------------------------------------------------------------------ DPI: перебор стратегий с допроверкой

    private boolean startDpi(boolean fromRotation) {
        List<String> candidates = buildDpiCandidates(fromRotation);
        int total = candidates.size();
        for (int i = 0; i < total; i++) {
            String strategy = candidates.get(i);
            int port = allocatePort(DPI_PROBE_PORT_BASE);
            if (port <= 0) {
                fail("Нет свободного локального порта для ByeDPI");
                return false;
            }
            if (tryDpiStrategy(strategy, port, i + 1, total)) return true;
        }
        engineFailed("ByeDPI: ни одна из " + total + " стратегий не пробила проверку YouTube. "
                + "DPI мог измениться — обновите стратегии.");
        return false;
    }

    /**
     * Список стратегий: свежий авто-скачанный (обновляется ВМЕСТЕ с конфигами VPN, < 30 дней),
     * иначе встроенный (каждый рядок прогнан через реальный ciadpi).
     */
    private List<String> dpiStrategyList() {
        java.util.Set<String> dead = ConfigRepository.loadDpiDead(this);
        try {
            long at = Prefs.dpiStrategiesAutoAt(this);
            if (at > 0 && System.currentTimeMillis() - at < 30L * 24 * 3600 * 1000) {
                File f = new File(getFilesDir(), ConfigRepository.AUTO_STRATS_FILE);
                if (f.isFile()) {
                    List<String> auto = new ArrayList<>();
                    for (String line : java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                        String t = line.trim();
                        if (!t.isEmpty() && !t.startsWith("#")
                            && !dead.contains(ConfigRepository.strategyHash(t))) auto.add(t);
                        if (auto.size() >= 150) break;
                    }
                    if (auto.size() >= 10) {
                        Log.i(TAG, "стратегии DPI: используется авто-список (" + auto.size() + ") от " + at);
                        return auto;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "авто-список стратегий не прочитан, использую встроенный", e);
        }
        List<String> builtin = readAssetLines("strategies/byedpi_strategies.txt");
        if (!dead.isEmpty()) {
            List<String> alive = new ArrayList<>();
            for (String st : builtin) {
                if (!dead.contains(ConfigRepository.strategyHash(st))) alive.add(st);
            }
            return alive;
        }
        return builtin;
    }

    /** Кандидаты DPI: сохранённая стратегия первой, затем встроенные (до DPI_MAX_ATTEMPTS). */
    private List<String> buildDpiCandidates(boolean forceAll) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        String saved = Prefs.strategy(this);
        if (saved != null && !saved.trim().isEmpty()) set.add(saved.trim());
        if (forceAll || Prefs.dpiAutoSelect(this)) {
            for (String st : dpiStrategyList()) {
                set.add(st);
                if (set.size() >= DPI_MAX_ATTEMPTS) break;
            }
        }
        if (set.isEmpty()) set.add(Prefs.DEFAULT_STRATEGY);
        return new ArrayList<>(set);
    }

    private boolean tryDpiStrategy(String strategy, int port, int attempt, int total) {
        currentSocksPort = port;
        List<String> urls = Prefs.dpiTestUrls(this);
        String[] args = DpiArgs.build(strategy, port, DpiArgs.sniFromUrls(urls));
        currentDetail = "ByeDPI: " + truncate(strategy, 60);
        sendStatus("connecting", "ByeDPI: стратегия " + attempt + "/" + total + " — проверка YouTube…", mode);

        byedpi = new ByeDpiProxy();
        final boolean[] exited = {false};
        Thread proxyThread = new Thread(() -> {
            int code;
            try {
                code = byedpi.startProxy(args);
            } catch (Throwable t) {
                code = -1;
            }
            exited[0] = true;
            Log.w(TAG, "ByeDPI попытка " + attempt + " завершилась кодом " + code);
        }, "byedpi");
        proxyThread.setDaemon(true);
        proxyThread.start();

        boolean ok = waitSocksOrExit("127.0.0.1", port, 5_000, exited) && dpiGatePass(port);
        if (!ok) {
            stopByeDpiQuiet();
            sendStatus("connecting", "Стратегия " + attempt + "/" + total
                    + " не пробила — пробую следующую…", mode);
            return false;
        }
        // стратегия рабочая — запоминаем, чтобы следующий запуск шёл с неё
        currentStrategy = strategy;
        Prefs.setStrategy(this, strategy);
        int idx = dpiStrategyList().indexOf(strategy);
        if (idx >= 0) Prefs.setDpiStrategyIndex(this, idx);
        startTunnel(port);
        sendStatus("on", currentDetail, mode);
        return true;
    }

    /** Тихо остановить ByeDPI без отправки статусов (внутри перебора стратегий). */
    private void stopByeDpiQuiet() {
        try { if (byedpi != null) byedpi.stopProxy(); } catch (Throwable ignored) {
        }
        byedpi = null;
        currentSocksPort = -1;
    }

    /** Допроверка: как waitSocksReady, но прерывается, если процесс уже умер. */
    private boolean waitSocksOrExit(String host, int port, long timeoutMs, boolean[] exited) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (exited[0]) return false;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 300);
                socket.setSoTimeout(500);
                socket.getOutputStream().write(new byte[]{0x05, 0x01, 0x00});
                InputStream in = socket.getInputStream();
                int a = in.read();
                int b = in.read();
                if (a == 5 && b == 0) return true;
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    private int allocatePort(int base) {
        for (int p = base; p < base + 20; p++) {
            try (Socket s = new Socket()) {
                s.bind(new InetSocketAddress("127.0.0.1", p));
                return p;
            } catch (Exception ignored) {
                // занят — следующий
            }
        }
        return base;
    }

    private void startTunnel(int socksPort) {
        if (tun == null) throw new IllegalStateException("TUN не создан");
        String yaml = "tunnel:\n" +
                "  mtu: 8500\n" +
                "misc:\n" +
                "  task-stack-size: 81920\n" +
                "socks5:\n" +
                "  address: 127.0.0.1\n" +
                "  port: " + socksPort + "\n" +
                "  udp: udp\n";
        final int fd = tun.getFd();
        Thread t = new Thread(() -> {
            int code = new TProxyService().startTunnel(yaml, fd);
            if (!stopRequested.get()) {
                Log.w(TAG, "hev-socks5-tunnel exited with code " + code);
            }
        }, "hev-tunnel");
        t.setDaemon(true);
        t.start();
    }

    private boolean waitSocksReady(String host, int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !stopRequested.get()) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 300);
                socket.setSoTimeout(500);
                socket.getOutputStream().write(new byte[]{0x05, 0x01, 0x00});
                InputStream in = socket.getInputStream();
                int a = in.read();
                int b = in.read();
                if (a == 5 && b == 0) return true;
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ допроверка (1 МБ ≤ 2 с) и тихий фон

    private volatile long lastGateMs = -1;

    /** VPN/Tor: обязаны скачать 1 МБ иностранных файлов ≤ 2 с (иначе конфиг/мосты нерабочие). */
    private boolean speedGatePass(int socksPort) {
        for (String url : Prefs.speedGateUrls(this)) {
            NetChecker.SpeedResult r = NetChecker.downloadThroughSocks(url, "127.0.0.1", socksPort,
                    2500, GATE_MAX_BYTES);
            Log.i(TAG, "gate[" + url + "] ms=" + r.ms + " bytes=" + r.bytes + " code=" + r.code);
            if (r.ok && r.ms <= GATE_MS && r.bytes >= GATE_MIN_BYTES) {
                lastGateMs = r.ms;
                return true;
            }
        }
        lastGateMs = -1;
        return false;
    }

    /** DPI: цели — заблокированные сайты (YouTube). Открытые CDN тут бесполезны: они доступны и с мёртвым обходом. */
    private boolean dpiGatePass(int socksPort) {
        List<String> urls = Prefs.dpiTestUrls(this);
        if (urls.isEmpty()) urls = java.util.Collections.singletonList("https://www.youtube.com");
        for (String url : urls) {
            NetChecker.SpeedResult r = NetChecker.downloadThroughSocks(url, "127.0.0.1", socksPort,
                    3000, GATE_MAX_BYTES);
            Log.i(TAG, "dpi-gate[" + url + "] ms=" + r.ms + " bytes=" + r.bytes + " code=" + r.code);
            if (!(r.ok && r.ms <= GATE_MS)) {
                lastGateMs = -1;
                return false;
            }
        }
        lastGateMs = GATE_MS; // для DPI тихого фона нет
        return true;
    }

    /** Быстрый канал (< 500 мс на 1 МБ): молча в фоне докачать ОДИН текстовый файл. */
    private void maybeQuietOnFastLink() {
        if (!Prefs.quietUpdate(this)) return;
        if (lastGateMs < 0 || lastGateMs > FAST_MS) return;
        if ("dpi".equals(currentEngine)) {
            Log.i(TAG, "быстрый канал, но DPI-режим: фоновая докачка не нужна");
            return;
        }
        final int socks = currentSocksPort;
        if (socks <= 0) return;
        final boolean tor = "tor".equals(currentEngine);
        Log.i(TAG, "быстрый канал (" + lastGateMs + " мс): фоновая докачка ("
                + (tor ? "мосты Tor" : "конфиги") + ")");
        Thread t = new Thread(() -> {
            try {
                if (tor) {
                    java.net.Proxy px = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                            new java.net.InetSocketAddress("127.0.0.1", socks));
                    int n = ConfigRepository.refreshTorBridges(this, px);
                    Log.i(TAG, "авто-мосты через туннель: сохранено " + n);
                } else {
                    int added = ConfigRepository.quietOneThroughSocks(this, "127.0.0.1", socks);
                    Log.i(TAG, "тихий апдейт через туннель: добавлено конфигов: " + added);
                }
            } catch (Throwable e) {
                Log.w(TAG, "фоновая докачка не удалась", e);
            }
        }, "quiet-fetch");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ мониторинг и автопереключение

    private void startMonitor() {
        monitorThread = new Thread(() -> {
            int failStreak = 0;
            int interval = Math.max(5, Prefs.monitorIntervalSec(this)) * 1000;
            int maxFail = Math.max(1, Prefs.switchTimeoutSec(this) / Math.max(1, Prefs.monitorIntervalSec(this)));
            while (!stopRequested.get()) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    break;
                }
                if (stopRequested.get()) break;
                boolean alive = checkAlive();
                if (alive) {
                    failStreak = 0;
                    continue;
                }
                failStreak++;
                Log.w(TAG, "канал не отвечает, подряд: " + failStreak + "/" + maxFail);
                if (failStreak >= maxFail && Prefs.autoSwitch(this)) {
                    failStreak = 0;
                    switchToNext();
                }
            }
        }, "bypass-monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    private volatile boolean torEngineReady = false;
    /** Движок, реально запущенный (в авто-режиме: vpn|tor|dpi). */
    private volatile String currentEngine = "vpn";
    /** Стадия авто-цепочки: 0=VPN, 1=Tor, 2=DPI. */
    private volatile int autoStage = 0;

    private boolean checkAlive() {
        try {
            if ("tor".equals(currentEngine) && !torEngineReady) {
                return true; // Tor ещё строит цепочку — не считаем это отказом
            }
            int port = currentSocksPort;
            if (port > 0) {
                // DPI: целим в заблокированный сайт (YouTube); VPN/Tor: целим в иностранные файлы допроверки
                List<String> urls = "dpi".equals(currentEngine) ? Prefs.dpiTestUrls(this) : Prefs.speedGateUrls(this);
                String url = (urls != null && !urls.isEmpty()) ? urls.get(0)
                        : ("dpi".equals(currentEngine) ? "https://www.youtube.com"
                            : "https://speed.cloudflare.com/__down?bytes=1024");
                return NetChecker.httpCheckThroughSocks(url, "127.0.0.1", port, 5000);
            }
        } catch (Exception e) {
            Log.w(TAG, "checkAlive failed", e);
        }
        return false;
    }

    private void switchToNext() {
        if ("auto".equals(mode)) {
            // монитор сработал на стадии цепочки — переключаемся дальше по цепочке
            if ("vpn".equals(currentEngine)) {
                sendSwitch("Канал не отвечает → Авто: пробую Tor…");
                nextAutoStage("tor");
            } else if ("tor".equals(currentEngine)) {
                sendSwitch("Канал не отвечает → Авто: пробую DPI…");
                nextAutoStage("dpi");
            } else {
                rotateDpiStrategy();
            }
            return;
        }
        if ("vpn".equals(currentEngine)) {
            switchToNextConfig();
        } else if ("dpi".equals(currentEngine)) {
            rotateDpiStrategy();
        } else {
            sendStatus("error", "Tor не отвечает — проверьте мосты (Настройки → Tor)", mode);
        }
    }

    /** Переключить стадию авто-цепочки В РАНТАЙМЕ: глушит hev и все движки, затем стартует стадию. */
    private void nextAutoStage(String stage) {
        stopRaceWinner();
        try { new TProxyService().stopTunnel(); } catch (Throwable ignored) { }
        try { XrayEngine.stop(); } catch (Throwable ignored) { }
        try { if (desyncProxy != null) desyncProxy.stopProxy(); } catch (Throwable ignored) { }
        desyncProxy = null;
        try { if (byedpi != null) byedpi.stopProxy(); } catch (Throwable ignored) { }
        byedpi = null;
        try { TorEngine.stop(this); } catch (Throwable ignored) { }
        if ("tor".equals(stage)) {
            currentEngine = "tor";
            autoStage = 1;
            torEngineReady = false;
            sendStatus("connecting", "Авто: пробую Tor через мосты…", mode);
            startTor();
        } else {
            currentEngine = "dpi";
            autoStage = 2;
            sendStatus("connecting", "Авто: пробую DPI-обход…", mode);
            startDpi(false);
        }
    }

    private void switchToNextConfig() {
        List<ProxyConfig> active = ConfigRepository.activeList(this);
        String currentRaw = currentConfig != null ? currentConfig.raw : "";
        // кандидаты: живые по кэшу, поддерживаемые, не текущий
        ProxyConfig next = null;
        for (ProxyConfig p : active) {
            if (!p.supported || p.latency < 0) continue;
            if (p.raw.equals(currentRaw)) continue;
            next = p;
            break;
        }
        if (next == null) {
            // ничего не знаем о пингах — пробуем просто следующий поддерживаемый
            for (ProxyConfig p : active) {
                if (p.supported && !p.raw.equals(currentRaw)) { next = p; break; }
            }
        }
        if (next == null) {
            sendStatus("error", "Нет другого конфига для переключения", mode);
            return;
        }
        final String old = currentConfig != null ? currentConfig.name : "?";
        final ProxyConfig target = next;
        sendSwitch("Канал не отвечает: " + old + " → " + target.shortLabel());
        try {
            stopRaceWinner();
            XrayEngine.stop();
            new TProxyService().stopTunnel();
            Prefs.setCurrentConfig(this, target.raw, target.name, target.host, target.port);
            startXray();
        } catch (Exception e) {
            sendStatus("error", "Не удалось переключиться: " + e.getMessage(), mode);
        }
    }

    private void rotateDpiStrategy() {
        List<String> strategies = dpiStrategyList();
        if (strategies.isEmpty()) {
            sendStatus("error", "Канал не отвечает, а список стратегий пуст", mode);
            return;
        }
        int idx = Prefs.dpiStrategyIndex(this);
        idx = (idx + 1) % strategies.size();
        Prefs.setDpiStrategyIndex(this, idx);
        String strategy = strategies.get(idx);
        Prefs.get(this).edit().putString(Prefs.BYEDPI_STRATEGY, strategy).apply();
        sendSwitch("Канал не отвечает → стратегия #" + (idx + 1) + ": " + truncate(strategy, 60));
        try {
            if (byedpi != null) byedpi.stopProxy();
            new TProxyService().stopTunnel();
            startDpi(true);
        } catch (Exception e) {
            sendStatus("error", "Не удалось сменить стратегию: " + e.getMessage(), mode);
        }
    }

    private List<String> readAssetLines(String asset) {
        List<String> out = new ArrayList<>();
        try (InputStream in = getAssets().open(asset)) {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------ остановка

    /** Отказ движка. В авто-режиме переключает VPN → Tor → DPI; иначе — терминальная ошибка. */
    private void engineFailed(String msg) {
        stopRaceWinner();
        if (!"auto".equals(mode)) {
            fail(msg);
            return;
        }
        String stopped = currentEngine;
        if ("vpn".equals(stopped)) {
            currentEngine = "tor";
            autoStage = 1;
            try { XrayEngine.stop(); } catch (Throwable ignored) { }
            torEngineReady = false;
            sendStatus("connecting", "Авто: VPN не вышел (" + truncate(msg, 80) + ") — пробую Tor…", mode);
            startTor();
            return;
        }
        if ("tor".equals(stopped)) {
            currentEngine = "dpi";
            autoStage = 2;
            try { TorEngine.stop(this); } catch (Throwable ignored) { }
            sendStatus("connecting", "Авто: Tor не вышел (" + truncate(msg, 80) + ") — пробую DPI…", mode);
            startDpi(false);
            return;
        }
        fail("Авто: не сработали ни VPN, ни Tor, ни DPI. Последняя ошибка: " + msg);
    }

    private void fail(String msg) {
        sendStatus("error", msg, mode);
        stopEverythingInternal(false);
    }

    private void stopEverything() {
        stopEverythingInternal(true);
    }

    private synchronized void stopEverythingInternal(boolean sendOff) {
        stopRequested.set(true);
        running.set(false);
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
        stopRaceWinner();
        try {
            XrayEngine.stop();
        } catch (Throwable ignored) {
        }
        try {
            if (byedpi != null) byedpi.stopProxy();
        } catch (Throwable ignored) {
        }
        byedpi = null;
        try {
            if (desyncProxy != null) desyncProxy.stopProxy();
        } catch (Throwable ignored) {
        }
        desyncProxy = null;
        try {
            TorEngine.stop(this);
        } catch (Throwable ignored) {
        }
        try {
            if (tun != null) tun.close();
        } catch (Exception ignored) {
        }
        tun = null;
        torEngineReady = false;
        releaseWakeLock();
        if (sendOff) sendStatus("off", "Отключено", mode);
        stopForegroundCompat();
        stopSelf();
        // stopRequested оставляем true: монитор должен завершиться.
    }

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

    // ------------------------------------------------------------------ уведомление, статусы

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "VPN-туннель", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Трафик идёт через защищённый туннель");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void startForegroundCompat() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Обход Белых Списков")
                .setContentText("Трафик идёт через защищённый туннель")
                .setOngoing(true)
                .setContentIntent(pi);
        Notification notification = builder.build();
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

    private void sendStatus(String state, String detail, String modeName) {
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_STATE, state);
        i.putExtra(EXTRA_DETAIL, detail);
        i.putExtra(EXTRA_MODE, modeName);
        sendBroadcast(i);
    }

    private void sendSwitch(String detail) {
        Intent i = new Intent(ACTION_SWITCHED);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_DETAIL, detail);
        sendBroadcast(i);
    }

    private String modeName(String m) {
        switch (m == null ? "" : m) {
            case "vpn": return "VPN (Xray)";
            case "tor": return "Tor";
            case "dpi": return "DPI-обход";
            case "auto": return "Авто";
            default: return m;
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
