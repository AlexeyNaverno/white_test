package ru.whitelist.bypass;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Дизайн-система приложения: токены и готовые элементы интерфейса.
 *
 * Зачем отдельный класс. Раньше каждый экран собирал вьюхи сам, и одинаковые вещи
 * выглядели по-разному: у кнопок были случайные отступы и радиусы, «карточки» — просто
 * прямоугольники без границ, а размеры шрифтов подбирались на месте (10, 11, 12, 13, 22…).
 * Теперь есть один набор решений, и экран только выбирает роль:
 *
 *   цвета      — BG (фон) → SURFACE (карточка) → SURFACE_2 (блок внутри карточки);
 *   отступы    — шкала 4 dp: S1…S6 (4, 8, 12, 16, 24, 32);
 *   радиусы    — R_SM 10, R_MD 16, R_LG 20, R_PILL для «таблеток»;
 *   шрифт      — h1/h2 (заголовки), body, caption (подпись), mono (адреса и лог);
 *   поверхности— card() с границей и скруглением, chip() для состояния;
 *   действия   — primary (главное), secondary (обычное), ghost (тихое), danger.
 *
 * Правила, по которым это собрано (Material Design 3, тёмная тема):
 *  - поверхности темнее фона не бывают: карточка СВЕТЛЕЕ фона, иначе она «проваливается»;
 *  - один визуальный акцент на экран — главная кнопка; остальное — вторично;
 *  - минимальная цель нажатия 48 dp; текст на акценте — контрастный тёмный;
 *  - текст ≥ 12 sp, заголовки 18–26 sp, длинные тексты без «капса» и с межстрочным
 *    интервалом, чтобы читалось на телефоне одной рукой;
 *  - границы вместо теней: на почти чёрном фоне тень не видна, а 1 dp обводки даёт
 *    чёткое разделение карточек.
 */
public final class Ui {
    private Ui() {}

    // ------------------------------------------------------------------ цвета (роли)

    /** Фон экрана. */
    public static final int BG = 0xFF0B0F14;
    /** Карточка (уровень 1): светлее фона. */
    public static final int SURFACE = 0xFF131A24;
    /** Блок внутри карточки (уровень 2). */
    public static final int SURFACE_2 = 0xFF1B2532;
    /** Граница карточек и полей. */
    public static final int STROKE = 0xFF243040;
    /** Главный акцент: «работает», основное действие. */
    public static final int ACCENT = 0xFF3DDC84;
    /** Текст на акцентной заливке. */
    public static final int ACCENT_INK = 0xFF04210F;
    public static final int TEXT = 0xFFE9F0F7;
    public static final int TEXT_DIM = 0xFF93A6BA;
    public static final int DANGER = 0xFFFF6B6B;
    public static final int WARN = 0xFFFFB020;
    public static final int INFO = 0xFF64B5F6;
    /** Резервный режим Tor. */
    public static final int TOR = 0xFFB388FF;

    // ------------------------------------------------------------------ отступы и радиусы

    public static final int S1 = 4;
    public static final int S2 = 8;
    public static final int S3 = 12;
    public static final int S4 = 16;
    public static final int S5 = 24;
    public static final int S6 = 32;

    public static final int R_SM = 10;
    public static final int R_MD = 16;
    public static final int R_LG = 20;
    public static final int R_PILL = 999;

    /** Минимальная цель нажатия по гайдлайнам. */
    public static final int TOUCH = 48;

    // ------------------------------------------------------------------ базовое

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Скруглённый прямоугольник: заливка + обводка (0 — без обводки). */
    public static GradientDrawable shape(int fill, int strokeColor, int strokeDp, int radiusDp,
                                         Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) d.setStroke(dp(c, strokeDp), strokeColor);
        return d;
    }

    /** Кликабельная поверхность с волной нажатия: используется для карточек-кнопок. */
    public static void ripple(View v, int fill, int strokeDp, int radiusDp, int rippleColor,
                              Context c) {
        GradientDrawable content = shape(fill, STROKE, strokeDp, radiusDp, c);
        if (Build.VERSION.SDK_INT >= 21) {
            GradientDrawable mask = shape(0xFFFFFFFF, 0, 0, radiusDp, c);
            v.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask));
        } else {
            v.setBackground(content);
        }
    }

    /** Оформление карточки: фон, граница, скругление, внутренние отступы. */
    public static void cardLook(View v, Context c) {
        v.setBackground(shape(SURFACE, STROKE, 1, R_LG, c));
        int p = dp(c, S4);
        v.setPadding(p, p, p, p);
    }

    /** Новая вертикальная карточка. */
    public static LinearLayout card(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        cardLook(box, c);
        return box;
    }

    /** Вложенный блок внутри карточки — чуть светлее и с меньшим радиусом. */
    public static LinearLayout inner(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(shape(SURFACE_2, STROKE, 1, R_MD, c));
        int p = dp(c, S3);
        box.setPadding(p, p, p, p);
        return box;
    }

    public static void marginTop(Context c, View v, int dp) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) lp).topMargin = dp(c, dp);
        }
    }

    public static void marginBottom(Context c, View v, int dp) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) lp).bottomMargin = dp(c, dp);
        }
    }

    public static View divider(Context c) {
        View line = new View(c);
        line.setBackgroundColor(STROKE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        lp.topMargin = dp(c, S3);
        lp.bottomMargin = dp(c, S3);
        line.setLayoutParams(lp);
        return line;
    }

    // ------------------------------------------------------------------ текст

    private static TextView text(Context c, String value, float sizeSp, int color,
                                 boolean bold, boolean dim) {
        TextView t = new TextView(c);
        t.setText(value == null ? "" : value);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.NORMAL)
                : Typeface.create("sans-serif", Typeface.NORMAL));
        t.setLineSpacing(0f, dim ? 1.25f : 1.15f);
        return t;
    }

    /** Заголовок экрана. */
    public static TextView h1(Context c, String value) {
        TextView t = text(c, value, 24, TEXT, true, false);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    /** Заголовок карточки/секции. */
    public static TextView h2(Context c, String value) {
        return text(c, value, 16, TEXT, true, false);
    }

    /** Обычный текст. */
    public static TextView body(Context c, String value) {
        return text(c, value, 14, TEXT, false, false);
    }

    /** Подпись: объяснения, значения по умолчанию, предупреждения. */
    public static TextView caption(Context c, String value) {
        return text(c, value, 12, TEXT_DIM, false, true);
    }

    /** Надпись-«надзаголовок» секции: маленькая, разрежённая, приглушённая. */
    public static TextView overline(Context c, String value) {
        TextView t = text(c, value == null ? "" : value.toUpperCase(), 11, TEXT_DIM, true, false);
        t.setLetterSpacing(0.12f);
        return t;
    }

    /** Текст для адресов, ключей и лога. */
    public static TextView mono(Context c, String value, float sizeSp, int color) {
        TextView t = new TextView(c);
        t.setText(value == null ? "" : value);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        return t;
    }

    // ------------------------------------------------------------------ чипы и статус

    /**
     * «Таблетка» — компактная метка состояния или вкладка.
     *
     * @param filled true — заливка цветом (активная вкладка), false — только контур.
     */
    public static TextView chip(Context c, String value, int color, boolean filled) {
        TextView t = new TextView(c);
        t.setText(value == null ? "" : value);
        t.setTextSize(12);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        int px = dp(c, S3);
        int py = dp(c, 7);
        t.setPadding(px, py, px, py);
        paintChip(t, color, filled, c);
        return t;
    }

    /** Перекрасить чип (статус меняется на ходу). */
    public static void paintChip(TextView t, int color, boolean filled, Context c) {
        if (filled) {
            t.setTextColor(ACCENT_INK);
            t.setBackground(shape(color, 0, 0, R_PILL, c));
        } else {
            t.setTextColor(color);
            t.setBackground(shape(SURFACE_2, color, 1, R_PILL, c));
        }
    }

    /** Чип-вкладка: активная — залита акцентом, неактивная — контур. */
    public static TextView tab(Context c, String value) {
        TextView t = chip(c, value, TEXT_DIM, false);
        t.setTextSize(13);
        t.setPadding(dp(c, S4), dp(c, 9), dp(c, S4), dp(c, 9));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    // ------------------------------------------------------------------ кнопки

    private static Button button(Context c, String value, int fill, int textColor, int strokeColor,
                                 boolean bold) {
        Button b = new Button(c);
        b.setText(value);
        b.setTextColor(textColor);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.NORMAL)
                : Typeface.create("sans-serif", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 52));
        b.setMinimumHeight(dp(c, 52));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, S4), 0, dp(c, S4), 0);
        b.setStateListAnimator(null);
        b.setBackgroundTintList(null);
        ripple(b, fill, strokeColor == 0 ? 0 : 1, R_MD, 0x33FFFFFF, c);
        if (strokeColor != 0) {
            b.setBackground(shape(fill, strokeColor, 1, R_MD, c));
            ripple(b, fill, 1, R_MD, 0x33FFFFFF, c);
        }
        return b;
    }

    /** Главное действие на экране: одна такая кнопка, не больше. */
    public static Button primary(Context c, String value) {
        return button(c, value, ACCENT, ACCENT_INK, 0, true);
    }

    /** Обычное действие: поверхность с границей. */
    public static Button secondary(Context c, String value) {
        return button(c, value, SURFACE_2, TEXT, STROKE, false);
    }

    /** Тихое действие: без заливки (копировать, обновить). */
    public static Button ghost(Context c, String value) {
        Button b = button(c, value, 0x00000000, ACCENT, 0, false);
        b.setBackground(null);
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        return b;
    }

    /** Опасное действие: убрать, отправить в чёрный список. */
    public static Button danger(Context c, String value) {
        return button(c, value, SURFACE_2, DANGER, 0x66FF6B6B, false);
    }

    /** Компактная кнопка для строки списка. */
    public static Button small(Context c, String value) {
        Button b = button(c, value, SURFACE_2, TEXT, STROKE, false);
        b.setTextSize(12);
        b.setMinHeight(dp(c, 40));
        b.setMinimumHeight(dp(c, 40));
        b.setPadding(dp(c, S3), 0, dp(c, S3), 0);
        return b;
    }

    /** Кнопка-иконка в шапке (⚙ и подобные): квадрат 44 dp. */
    public static TextView iconButton(Context c, String glyph, int color) {
        TextView t = new TextView(c);
        t.setText(glyph);
        t.setTextSize(18);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        int size = dp(c, 44);
        t.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        t.setBackground(shape(SURFACE_2, STROKE, 1, R_MD, c));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    // ------------------------------------------------------------------ композиция

    /** Отступ под заголовком секции и сам заголовок. */
    public static LinearLayout sectionHeader(Context c, String title) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, S1), dp(c, S5), dp(c, S1), dp(c, S2));
        box.addView(overline(c, title));
        return box;
    }

    /** Пустая строка-распорка. */
    public static View spacer(Context c, int dp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, dp)));
        return v;
    }

    /**
     * Заголовок экрана с кнопкой «назад» слева: так навигация читается привычно —
     * возврат там, где его ищут, а не справа.
     */
    public static LinearLayout headerLeft(Context c, String title, String subtitle, View back) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (back != null) {
            row.addView(back);
        }
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(h1(c, title));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = caption(c, subtitle);
            s.setPadding(0, dp(c, 3), 0, 0);
            col.addView(s);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = back == null ? 0 : dp(c, S3);
        row.addView(col, lp);
        return row;
    }

    /** Заголовок окна с подписью и правым элементом (кнопка, счётчик). */
    public static LinearLayout header(Context c, String title, String subtitle, View right) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = h1(c, title);
        col.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = caption(c, subtitle);
            s.setPadding(0, dp(c, 3), 0, 0);
            col.addView(s);
        }
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (right != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(c, S3);
            row.addView(right, lp);
        }
        return row;
    }

    // ------------------------------------------------------------------ инсеты

    /**
     * Отступы под системные панели.
     *
     * С Android 15 приложение с targetSdk 35+ рисует под статус-баром, камерой и панелью
     * навигации, поэтому корневому экрану нужно самому добавить отступы — иначе страница
     * браузера залезает под камеру и кнопки телефона.
     */
    public static void applyInsets(final View root) {
        if (root == null) return;
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int left = 0, top = 0, right = 0, bottom = 0;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    left = bars.left;
                    top = bars.top;
                    right = bars.right;
                    bottom = bars.bottom;
                } else {
                    left = insets.getSystemWindowInsetLeft();
                    top = insets.getSystemWindowInsetTop();
                    right = insets.getSystemWindowInsetRight();
                    bottom = insets.getSystemWindowInsetBottom();
                    if (Build.VERSION.SDK_INT >= 28) {
                        android.view.DisplayCutout cut = insets.getDisplayCutout();
                        if (cut != null) {
                            left = Math.max(left, cut.getSafeInsetLeft());
                            top = Math.max(top, cut.getSafeInsetTop());
                            right = Math.max(right, cut.getSafeInsetRight());
                            bottom = Math.max(bottom, cut.getSafeInsetBottom());
                        }
                    }
                }
                v.setPadding(baseLeft + left, baseTop + top, baseRight + right, baseBottom + bottom);
                return insets;
            }
        });
        root.requestApplyInsets();
    }
}
