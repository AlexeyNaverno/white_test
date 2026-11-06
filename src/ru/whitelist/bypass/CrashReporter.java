package ru.whitelist.bypass;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Локальный сборщик аварийных стектрейсов (без сети).
 * При любом непойманном исключении пишет трассировку в filesDir/last_crash.txt.
 * Пользователь видит её на следующем запуске в диалоге — можно прислать разработчику.
 */
public final class CrashReporter {

    public static final String FILE = "last_crash.txt";
    private static final String TAG = "CrashReporter";

    private CrashReporter() {}

    /** Поставить глобальный обработчик. Вызывать ПЕРВЫМ делом в onCreate. */
    public static void install(final Context ctx) {
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    pw.println("Thread: " + t.getName());
                    e.printStackTrace(pw);
                    pw.flush();
                    FileOutputStream fos = ctx.openFileOutput(FILE, Context.MODE_PRIVATE);
                    fos.write(sw.toString().getBytes(StandardCharsets.UTF_8));
                    fos.close();
                    Log.e(TAG, "crash captured", e);
                } catch (Throwable ignored) {
                    // нечего делать — умираем молча
                }
                if (old != null) {
                    old.uncaughtException(t, e);
                } else {
                    System.exit(2);
                }
            }
        });
    }

    /** Если был аварийный лог — вернуть его текст и удалить файл. */
    public static String consumeIfPresent(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            if (!f.exists()) return null;
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            fis.close();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
