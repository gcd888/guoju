package com.github.gcd888.guoju.util;

import android.content.Context;
import android.content.res.Resources;
import android.widget.TextView;

import com.github.gcd888.guoju.R;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * Hero 信息区动态排版（点播主页 VodHomeFragment / 短剧主页 DramaHomeFragment 共用）。
 *
 * 排版模型（替代写死 mm 值，任何屏比/字号下都成立）：
 *   顶部导航栏：marginTop=vs_20(20mm)，高度=导航字号(ts_nav) em × 1.4（中文字体行高，写死容器会裁字）
 *   剧名顶距：导航栏底边 + INFO_GAP_MM（0.86 版基线 105mm 过低压简介，收紧为 65mm）
 *   简介行数：不再限 2 行——可用高度 = tab 行顶 − 简介顶 − 底部留白 20mm，按 ts_20 行高折算，至少 1 行
 *
 * 进程级缓存：同屏常量只算一次。HeroBannerView 双层 layer 在不同时机 bind（AutoSize 适配、
 * 布局阶段存在密度/度量差异），各自实时计算会得到不同 topMargin——表现就是轮播各页剧名
 * 高度不齐。缓存后所有页、所有次 bind 都用同一常量，天然对齐。
 */
public final class HeroInfoLayout {
    /** 导航栏 marginTop（vs_20）。 */
    private static final float NAV_MARGIN_MM = 20f;
    /** 导航栏底边到剧名的间距（0.86 版 105mm 过低压简介，收紧为 65mm 上移剧名）。 */
    private static final float INFO_GAP_MM = 65f;
    /** 简介最后一行到底部 tab 行的留白。 */
    private static final float DESC_GAP_MM = 20f;
    /** 中文字体行高系数（TextView 行高 ≈ em × 1.4）。 */
    private static final float LINE_FACTOR = 1.4f;

    /** 进程级缓存（同屏恒定；进程存活期内屏参不变）。 */
    private static volatile int cachedNavHPx = -1;
    private static volatile int cachedInfoTopPx = -1;
    private static volatile float cachedTabTopPx = -1f;

    private HeroInfoLayout() {
    }

    /** 导航栏动态高度：导航字号 ts_nav em × 1.4（与 HomeActivity navBarHeightPx 同源同值）。 */
    public static int navHeightPx(Context ctx) {
        if (cachedNavHPx <= 0) {
            cachedNavHPx = (int) (ctx.getResources().getDimensionPixelSize(R.dimen.ts_nav) * LINE_FACTOR);
        }
        return cachedNavHPx;
    }

    /** Hero 信息区（剧名容器）距 Hero 顶部的 topMargin（缓存，bind 时机无关）。 */
    public static int infoTopPx(Context ctx) {
        if (cachedInfoTopPx <= 0) {
            cachedInfoTopPx = AutoSizeUtils.mm2px(ctx, NAV_MARGIN_MM)
                    + navHeightPx(ctx)
                    + AutoSizeUtils.mm2px(ctx, INFO_GAP_MM);
        }
        return cachedInfoTopPx;
    }

    /**
     * 简介动态行数。tabTopPx：底部 tab 行顶边（窗口坐标，与 Hero 同一全屏坐标系）；
     * 未测得传 -1，按 0.86 基线（tab 行 300mm）兜底。tabTop 有效值进程级缓存：
     * 各页轮播共用同一行数基准，避免不同 bind 时机测得不同 tab 行位置；
     * 缓存仅在测量值与缓存差超过 40mm（大版式变化，如首页多行开关使 tab 行大幅升降）
     * 时刷新，动画中间值（差值小）不会污染缓存，排版切换后也能收敛到新位置。
     * 返回值可为 0（一行都放不下，调用方应隐藏简介）。
     */
    public static int descMaxLines(Context ctx, int descTopPx, float tabTopPx) {
        if (tabTopPx > 0 && (cachedTabTopPx <= 0
                || Math.abs(tabTopPx - cachedTabTopPx) > AutoSizeUtils.mm2px(ctx, 40f))) {
            cachedTabTopPx = tabTopPx;
        }
        if (cachedTabTopPx <= 0) {
            cachedTabTopPx = ctx.getResources().getDimensionPixelSize(R.dimen.atv_hero_h);
        }
        Resources res = ctx.getResources();
        float lineH = res.getDimensionPixelSize(R.dimen.ts_20) * LINE_FACTOR;
        int avail = (int) (cachedTabTopPx - AutoSizeUtils.mm2px(ctx, DESC_GAP_MM) - descTopPx);
        return Math.max(0, (int) (avail / lineH));
    }

    /** 供外部（排版验证/日志）读取当前生效的 tab 行顶边缓存。 */
    public static float cachedTabTop() {
        return cachedTabTopPx;
    }

    /**
     * 应用 Hero 信息区动态排版：设置剧名容器 topMargin + 简介动态行数。
     *
     * @param infoBox  剧名/信息/简介的共同容器（view_hero_item 内的垂直 LinearLayout）
     * @param tvTitle  剧名（ts_26）
     * @param tvMeta   信息行（ts_20）
     * @param tvDesc   简介（ts_20）
     * @param tabTopPx tab 行顶边窗口坐标；未测得传 -1
     */
    public static void apply(Context ctx, android.view.View infoBox, TextView tvTitle,
                             TextView tvMeta, TextView tvDesc, float tabTopPx) {
        if (infoBox == null || tvTitle == null || tvDesc == null) {
            return;
        }
        int infoTop = infoTopPx(ctx);
        // 兼容任意父容器（FrameLayout/LinearLayout/ConstraintLayout…），
        // 非边距型 LayoutParams 时用拷贝构造升级为 MarginLayoutParams
        android.view.ViewGroup.LayoutParams base = infoBox.getLayoutParams();
        android.view.ViewGroup.MarginLayoutParams lp =
                base instanceof android.view.ViewGroup.MarginLayoutParams
                        ? (android.view.ViewGroup.MarginLayoutParams) base
                        : new android.view.ViewGroup.MarginLayoutParams(base);
        lp.topMargin = infoTop;
        infoBox.setLayoutParams(lp);
        // 简介顶部 = 信息区顶 + 剧名行高 + 间距(vs_6) + 信息行高 + 间距(vs_4)
        int descTop = infoTop
                + (int) (ctx.getResources().getDimensionPixelSize(R.dimen.ts_26) * LINE_FACTOR)
                + ctx.getResources().getDimensionPixelSize(R.dimen.vs_6)
                + (int) (tvMeta != null && tvMeta.getVisibility() == android.view.View.VISIBLE
                        ? ctx.getResources().getDimensionPixelSize(R.dimen.ts_20) * LINE_FACTOR : 0)
                + ctx.getResources().getDimensionPixelSize(R.dimen.vs_4);
        int descLines = descMaxLines(ctx, descTop, tabTopPx);
        if (descLines < 1) {
            // tab 行空间被压缩（如首页多行货架）：一行简介都放不下时隐藏，避免文字压到 tab 行
            tvDesc.setVisibility(android.view.View.GONE);
        } else {
            tvDesc.setMaxLines(descLines);
            tvDesc.setVisibility(android.view.View.VISIBLE);
        }
    }

    /**
     * 剧名焦点态：白色不变色（hero_title_color 已统一白色），以缩放 5% 表达焦点（TV 惯例）。
     * pivotX 固定左缘：默认中心锚点的放大会让文字左半向左扩展，首字（如"给阿嬷的情书"的"给"）
     * 左移出可视区被裁——表现为选中后首字消失。左缘锚定后放大只向右扩展，左边缘纹丝不动。
     */
    public static void applyTitleFocus(android.view.View v, boolean hasFocus) {
        float s = hasFocus ? 1.05f : 1f;
        v.setPivotX(0f);
        v.animate().scaleX(s).scaleY(s).setDuration(150).start();
    }
}
