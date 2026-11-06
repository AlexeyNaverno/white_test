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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Единственный режим работы: VPN (Xray) + при необходимости DPI-обход линии (ciadpi).
 *
 * Порядок запуска (важно):
 *   1) поднимаем и проверяем ядро на 127.0.0.1:10811 — интернет в это время не трогаем;
 *   2) только после успешной проверки создаём TUN;
 *   3) поднимаем hev-socks5-tunnel (TUN → SOCKS 10811).
 * Поэтому неудачная попытка больше не оставляет телефон без связи.
 */
public class BypassVpnService extends VpnService {

    public static final String ACTION_START = "ru.whitelist.bypass.action.START";
    public static final String ACTION_STOP = "ru.whitelist.bypass.action.STOP";
    /** Кнопка в уведомлении: сменить конфиг, не отключая туннель. */
    public static final String ACTION_SWITCH = "ru.whitelist.bypass.action.SWITCH";
    public static final String ACTION_STATUS = "ru.whitelist.bypass.action.STATUS";
    public static final String EXTRA_STATE = "state";    // off | connecting | on | error
    public static final String EXTRA_DETAIL = "detail";

    private static final String CHANNEL_ID = "bypass_vpn";
    private static final int NOTIFICATION_ID = 1001;
    private static final String TAG = "BypassVpnService";

    /** Локальный SOCKS ядра Xray. */
    private static final int XRAY_PORT = 10811;
    /** Сколько конфигов из пула пробуем за одно подключение. */
    private static final int MAX_ATTEMPTS = 6;
    /** Для скольких первых конфигов пробуем DPI-обход линии. */
    private static final int DESYNC_ATTEMPTS = 3;
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
    private static volatile int currentSocksPort = -1;

    public static boolean isRunning() { return running.get(); }
    /** Идёт попытка подключения (сервис занят поиском/проверкой). */
    public static boolean isConnecting() { return connectingFlag; }
    public static String currentDetail() { return currentDetail; }
    public static int currentSocksPort() { return currentSocksPort; }

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private ParcelFileDescriptor tun;
    private ProxyConfig currentConfig;
    private ByeDpiProxy desyncProxy;
    private Thread monitorThread;
    private PowerManager.WakeLock wakeLock;
    private volatile String lastError = "";

    // ------------------------------------------------------------------ жизненный цикл

    @Override
    public void onCreate() {
        super.onCreate();
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
                sendStatus("connecting", "Переключаю конфиг…");
                Thread switcher = new Thread(new Runnable() {
                    @Override public void run() { switchToNext(); }
                }, "bypass-switch");
                switcher.setDaemon(true);
                switcher.start();
            } else {
                sendStatus("off", "Туннель не запущен");
            }
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            if (running.get() || tun != null) resetEngine(); // повторный старт = перезапуск
            dpiAutotuned = false;
            stopRequested.set(false);
            ConfigRepository.clearCancel();
            lastError = "";
            startForegroundCompat();
            sendStatus("connecting", "Запуск…");
            Thread starter = new Thread(new Runnable() {
                @Override public void run() { startWorker(); }
            }, "bypass-start");
            starter.setDaemon(true);
            starter.start();
        }
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------ запуск

    private void startWorker() {
        try {
            List<ProxyConfig> candidates = connectCandidates(MAX_ATTEMPTS);
            if (candidates.isEmpty()) {
                sendStatus("connecting", "Пул пуст — ищу первый рабочий конфиг…");
                candidates = quickFind();
            }
            if (candidates.isEmpty()) {
                fail("Рабочих конфигов нет. Нажмите «Собрать пул» (обновит списки и замерит скорость).");
                return;
            }

            boolean up = false;
            int attempt = 0;
            for (ProxyConfig cfg : candidates) {
                if (stopRequested.get() || ConfigRepository.isCancelled()) {
                    fail("Остановлено пользователем");
                    return;
                }
                attempt++;
                currentConfig = cfg;
                sendStatus("connecting", "Попытка " + attempt + "/" + candidates.size()
                        + ": " + truncate(cfg.name, 42));
                if (activate(cfg, false)) { up = true; break; }
                if (Prefs.desyncLine(this) && attempt <= DESYNC_ATTEMPTS) {
                    sendStatus("connecting", "Попытка " + attempt + " через DPI-обход линии…");
                    if (activate(cfg, true)) { up = true; break; }
                }
            }
            if (!up) {
                fail(("Не удалось подключиться ни к одному из " + attempt
                        + " конфигов. " + (lastError.isEmpty() ? "" : lastError)).trim());
                return;
            }

            // Ядро ответило и прошло проверку скорости — только теперь трогаем трафик.
            tun = establishTun();
            if (tun == null) {
                fail("Не удалось создать TUN (VPN не разрешён?)");
                return;
            }
            startTunnel(XRAY_PORT);
            running.set(true);
            acquireWakeLock();
            startMonitor();
            sendStatus("on", currentDetail);
        } catch (Throwable t) {
            Log.e(TAG, "старт не удался", t);
            fail(t.getMessage() != null ? t.getMessage() : "Ошибка запуска");
        }
    }

    /** Кандидаты из пула: сверху самые быстрые. */
    private List<ProxyConfig> connectCandidates(int max) {
        List<ProxyConfig> out = new ArrayList<>();
        for (Pool.Entry e : Pool.load(this)) {
            if (out.size() >= max) break;
            ProxyConfig c = e.cfg();
            if (c != null) out.add(c);
        }
        return out;
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
                found.add(cfg);
                if (found.size() >= 2) break;
            }
        }
        return found;
    }

    /**
     * Поднять конфиг на локальном порту 10811 и проверить скорость.
     * withDesync — линия до сервера идёт через локальный ciadpi (DPI-обход).
     */
    private boolean activate(ProxyConfig cfg, boolean withDesync) {
        int desyncPort = 0;
        boolean ok = false;
        try {
            if (!withDesync) stopDesync();
            if (withDesync) {
                desyncPort = startDesync(cfg);
                if (desyncPort <= 0) {
                    lastError = "DPI-обход линии не поднялся";
                    return false;
                }
            }
            String json = XrayEngine.buildConfigJson(cfg, XRAY_PORT, false,
                    Prefs.vpnDns(this), desyncPort);
            XrayEngine.start(this, json, new XrayEngine.Callback() {
                @Override public void onReady() { }
                @Override public void onError(String message) { Log.w(TAG, "xray: " + message); }
                @Override public void onLog(String line) { Log.i(TAG, line); }
            });
            if (!Probe.waitSocks(XRAY_PORT, Probe.SOCKS_WAIT_MS)) {
                lastError = "ядро не открыло локальный SOCKS";
                XrayEngine.stop();
                return false;
            }
            long kbps = Probe.speedKbps(this, XRAY_PORT, Prefs.gateTimeoutMs(this));
            long need = Math.max(1L, (long) (Prefs.minSpeedKbps(this) * MIN_SPEED_TOLERANCE));
            if (kbps < need) {
                lastError = "скорость " + (kbps < 0 ? "не измерена" : Prefs.speedText(kbps))
                        + " ниже порога " + Prefs.minSpeedText(this);
                Log.i(TAG, "конфиг " + cfg.name + ": " + lastError);
                XrayEngine.stop();
                return false;
            }
            Prefs.setCurrentConfig(this, cfg.raw, cfg.name, cfg.host, cfg.port);
            Pool.remember(this, cfg.raw, kbps, cfg.latency);
            currentConfig = cfg;
            currentSocksPort = XRAY_PORT;
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
        }
    }

    /** Локальный ciadpi, через который идёт линия до VPN-сервера. 0 — не поднялся. */
    private int startDesync(ProxyConfig cfg) {
        stopDesync();
        try {
            String sni = cfg != null ? cfg.tlsServerName() : null;
            if (sni == null || sni.isEmpty()) {
                sni = (cfg != null && cfg.host != null && !cfg.host.isEmpty()) ? cfg.host : "www.youtube.com";
            }
            // Один раз за сессию: если разрешено, ищем стратегию, которая реально пробивает
            // блокировки у текущего провайдера, и подставляем её вместо сохранённой вручную.
            if (Prefs.dpiAutotune(this) && !dpiAutotuned) {
                dpiAutotuned = true;
                sendStatus("connecting", "Подбираю стратегию обхода DPI…");
                Log.i(TAG, "DPI: автоподбор, стартовая — " + DpiStrategies.shorten(Prefs.dpiStrategy(this)));
                String chosen = DpiStrategies.autotune(this, sni, new DpiStrategies.Log() {
                    @Override public void line(String message) {
                        Log.i(TAG, message);
                        sendStatus("connecting", message);
                    }
                });
                Log.i(TAG, "DPI: выбрана " + DpiStrategies.shorten(chosen));
                sendStatus("connecting", "Стратегия обхода: " + DpiStrategies.shorten(chosen));
            }
            int port = Probe.freePort();
            if (port <= 0) return 0;
            String[] args = DpiArgs.build(Prefs.dpiStrategy(this), port, sni);
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
            Log.w(TAG, "не удалось исключить себя из TUN", e);
        }
        return builder.establish();
    }

    private void startTunnel(final int socksPort) {
        if (tun == null) throw new IllegalStateException("TUN не создан");
        final String yaml = "tunnel:\n" +
                "  mtu: 8500\n" +
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
                        switchToNext();
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
        int ok = NetChecker.countAliveThroughSocks(urls, "127.0.0.1", XRAY_PORT, 6000);
        if (ok == 0) {
            Log.w(TAG, "канал не отвечает ни на одну из " + urls.size() + " целей проверки");
        }
        return ok > 0;
    }

    /** Переключение на следующий по скорости конфиг из пула. TUN и hev не перезапускаем. */
    private void switchToNext() {
        List<ProxyConfig> list = connectCandidates(6);
        String currentRaw = currentConfig != null ? currentConfig.raw : "";
        int tried = 0;
        for (ProxyConfig cfg : list) {
            if (stopRequested.get()) return;
            if (cfg.raw.equals(currentRaw)) continue;
            if (tried >= 3) break;
            tried++;
            sendStatus("connecting", "Переключаюсь: " + truncate(cfg.name, 42) + "…");
            if (activate(cfg, false)) {
                sendStatus("on", currentDetail);
                return;
            }
        }
        sendStatus("error", "Канал не отвечает, других рабочих конфигов в пуле нет. "
                + "Нажмите «Собрать пул».");
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
        try {
            XrayEngine.stop();
        } catch (Throwable ignored) {
        }
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

    /** Уведомление с двумя действиями: «Остановить» и «Переключить». */
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
                .addAction(R.drawable.ic_stat_stop, "Остановить", stop)
                .addAction(R.drawable.ic_stat_switch, "Переключить", swtch);
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
        sendBroadcast(i);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
