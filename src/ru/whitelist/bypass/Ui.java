package ru.whitelist.bypass;

import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/**
 * Отступы под системные панели.
 *
 * С Android 15 приложение с targetSdk 35+ рисует под статус-баром, камерой и панелью
 * навигации, поэтому корневому экрану нужно самому добавить отступы — иначе страница
 * браузера залезает под камеру и кнопки телефона.
 */
public final class Ui {
    private Ui() {}

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
