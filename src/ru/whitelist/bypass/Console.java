package ru.whitelist.bypass;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Общий буфер консоли приложения (тот же текст, что видно в «Консоли» на главном экране).
 *
 * Зачем отдельный класс: консоль нужно уметь сохранить в файл из настроек, а настройки —
 * отдельный экран и к экземпляру MainActivity доступа не имеет. Здесь лежит копия лога,
 * которую можно забрать в любой момент и отдать в .json.
 */
public final class Console {

    /** Больше 60 000 символов не держим: файл для разбора не должен пухнуть. */
    private static final int MAX_CHARS = 60000;

    private static final StringBuilder BUF = new StringBuilder();
    private static int lines;

    private Console() {
    }

    /** Добавить строку в общий лог (вызывается из MainActivity.log). */
    public static synchronized void line(String message) {
        String s = message == null ? "" : message;
        if (BUF.length() > 0) BUF.append('\n');
        BUF.append(s);
        lines++;
        if (BUF.length() > MAX_CHARS) {
            int cut = BUF.indexOf("\n", BUF.length() - MAX_CHARS);
            BUF.delete(0, cut > 0 ? cut + 1 : BUF.length() - MAX_CHARS);
        }
    }

    /** Весь текст консоли как есть. */
    public static synchronized String text() {
        return BUF.toString();
    }

    public static synchronized int lineCount() {
        return lines;
    }

    public static synchronized void clear() {
        BUF.setLength(0);
        lines = 0;
    }

    /** Строки консоли списком — для JSON. */
    public static synchronized List<String> linesList() {
        List<String> out = new ArrayList<>();
        String all = BUF.toString();
        if (all.isEmpty()) return out;
        for (String s : all.split("\n", -1)) out.add(s);
        return out;
    }

    /** Время в ISO-подобном виде — чтобы файл было видно, когда снят. */
    public static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date());
    }
}
