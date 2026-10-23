package com.github.gcd888.guoju.ui.tv.focus;

import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.github.gcd888.guoju.R;

/**
 * Apple TV 风格焦点缩放：聚焦放大 + 抬升阴影。
 * 海报卡等面块用 POSTER_SCALE(1.10)，文字/按钮类用默认 1.06。
 * 手机端同样生效（触摸点击时获焦瞬间缩放 180ms 感知极小），
 * 便于无 TV 环境下用遥控/模拟器验证焦点动效。
 */
public final class FocusScaleHelper {

    // Apple TV 式抬升：轻微放大、快进慢收、无回弹
    private static final float DEFAULT_SCALE = 1.06f;
    // 海报卡放大档（tvOS 海报聚焦实际观感约 1.10，配合 translationZ 抬升阴影）
    public static final float POSTER_SCALE = 1.10f;
    private static final long DURATION_MS = 180;

    private FocusScaleHelper() {
    }

    public static void bind(View view) {
        bind(view, DEFAULT_SCALE);
    }

    /**
     * 绑定焦点缩放动效。注意：会覆盖 view 原有的 OnFocusChangeListener，
     * 需要自定义焦点回调的调用方应在回调末尾自行处理缩放。
     */
    public static void bind(View view, float scale) {
        if (view == null) {
            return;
        }
        view.setOnFocusChangeListener((v, hasFocus) -> {
            float target = hasFocus ? scale : 1.0f;
            v.animate().scaleX(target).scaleY(target)
                    .setDuration(DURATION_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            v.setTranslationZ(hasFocus
                    ? v.getResources().getDimensionPixelSize(R.dimen.atv_focus_elevation)
                    : 0f);
        });
    }
}
