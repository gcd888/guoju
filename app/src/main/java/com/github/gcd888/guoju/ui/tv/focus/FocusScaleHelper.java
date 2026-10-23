package com.github.gcd888.guoju.ui.tv.focus;

import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.github.gcd888.guoju.R;
import com.github.gcd888.guoju.util.ScreenUtils;

/**
 * Apple TV 风格焦点缩放：聚焦放大 + 抬升阴影。
 * 仅在 TV 端生效，手机端为 no-op（按项目约定运行时 ScreenUtils.isTv() 分流）。
 */
public final class FocusScaleHelper {

    // Apple TV 式抬升：轻微放大、快进慢收、无回弹
    private static final float DEFAULT_SCALE = 1.06f;
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
        if (view == null || !ScreenUtils.isTv(view.getContext())) {
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
