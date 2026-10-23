package com.github.gcd888.guoju.ui.widget;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Hero 全屏底图：无轮播样式，双层视图定时交叉淡入淡出（自然切换）。
 * 数据由调用方提供（当前站源热播前 N），焦点进入时停住、移出后恢复。
 */
public class HeroBannerView extends FrameLayout {

    /** 页面视图绑定器，由调用方决定每层布局与内容。 */
    public interface Binder<T> {
        View onCreateView(@NonNull Context context, @NonNull ViewGroup container);

        void onBind(@NonNull View itemView, T item, int position);

        /** 该条目底图 URL，供提前预加载下一张（无网络图返回 null）。 */
        @Nullable
        String coverUrl(@NonNull T item);
    }

    private static final long SWITCH_INTERVAL_MS = 12000L;
    private static final long CROSSFADE_DURATION_MS = 900L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable switchRunnable = new Runnable() {
        @Override
        public void run() {
            // 定时链永续：不活跃（焦点停留/未附着）时只跳过本次切换，不断链
            boolean active = isSwitchActive();
            com.github.gcd888.guoju.util.LogWriter.i(logTag + " tick pos=" + currentPos + " active=" + active
                    + " paused=" + focusPaused + " attached=" + isAttachedToWindow()
                    + " count=" + itemCount);
            if (active && itemCount > 1) {
                switchToNext();
            }
            mainHandler.postDelayed(this, SWITCH_INTERVAL_MS);
        }
    };

    private final View[] layers = new View[2];
    private Binder binder;
    private List items;
    private int itemCount;
    private int currentPos;
    private int activeLayer;
    private long lastSwitchAt;
    private boolean autoScrollEnabled = true;
    /** 日志实例标识：若出现两个实例并行调度（旧 view 未及时 detach），日志会以不同 tag 交替出现。 */
    private final String logTag = "hero@" + Integer.toHexString(hashCode());
    /** 焦点停留在 Hero 上时暂停自然切换（需求行为），移出后恢复。 */
    private boolean focusPaused = false;

    public HeroBannerView(@NonNull Context context) {
        this(context, null);
    }

    public HeroBannerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs, 0);
    }

    /** 填充底图数据（热播前 3）；单张时静止显示，不切换。 */
    public <T> void setData(@Nullable List<T> data, @NonNull Binder<T> binder) {
        // 同一份数据重复 setData（迟到回调/重复渲染触发）不重置轮播，避免切换节律被打断
        if (this.binder == binder && sameItems(data)) {
            restartAutoSwitch();
            return;
        }
        this.binder = binder;
        this.items = data == null ? new ArrayList<>() : data;
        this.itemCount = Math.min(this.items.size(), 3);
        currentPos = 0;
        activeLayer = 0;
        removeAllViews();
        mainHandler.removeCallbacks(switchRunnable);
        if (itemCount == 0) {
            return;
        }
        for (int i = 0; i < (itemCount > 1 ? 2 : 1); i++) {
            final int layerIndex = i;
            layers[i] = binder.onCreateView(getContext(), this);
            layers[i].setLayoutParams(new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            // 需求行为：遥控焦点调到 Hero 时轮播停住，移出后恢复。
            // 双层各自可获焦，焦点在两层间跳动会先后触发两个回调——用整体状态判断，
            // 避免 paused 依赖回调顺序在 true/false 间翻转（曾致节律错乱）。
            layers[layerIndex].setOnFocusChangeListener((v, hasFocus) -> {
                com.github.gcd888.guoju.util.LogWriter.i(logTag + " focus layer=" + layerIndex
                        + " hasFocus=" + hasFocus);
                applyFocusPause();
            });
            addView(layers[i]);
        }
        binder.onBind(layers[0], (T) this.items.get(0), 0);
        layers[0].setAlpha(1f);
        if (layers[1] != null) {
            layers[1].setAlpha(0f);
            // 隐藏层不可获焦：两层几何完全叠放，若隐藏层可获焦，焦点搜索可能命中
            // 隐藏层上的片名（焦点高亮不可见、绑定数据陈旧），故随可见性切换焦点开关
            setLayerFocusable(layers[1], false);
        }
        preloadNext(0);
        restartAutoSwitch();
    }

    /** 判断新数据与当前数据是否为同一组元素引用（renderHero 复用条目对象，引用比对可靠）。 */
    private boolean sameItems(List<?> data) {
        if (items == null || data == null || items.size() != data.size()) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) != data.get(i)) {
                return false;
            }
        }
        return true;
    }

    /** 提前预加载下一张底图进内存缓存：切换时秒显，避免大图加载期间透明/旧图假象。 */
    private void preloadNext(int fromPos) {
        if (itemCount <= 1 || binder == null) {
            return;
        }
        try {
            final Object next = items.get((fromPos + 1) % itemCount);
            final String url = binder.coverUrl(next);
            if (url != null && !url.isEmpty()) {
                // URL 可能带 @User-Agent/@Referer 头（豆瓣防盗链），必须走 ImgUtil 解析成 GlideUrl
                com.bumptech.glide.Glide.with(getContext())
                        .load(com.github.gcd888.guoju.util.ImgUtil.getUrl(url))
                        .addListener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                            @Override
                            public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e,
                                                        Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                        boolean isFirstResource) {
                                com.github.gcd888.guoju.util.LogWriter.i(logTag + " preload FAIL pos=" + currentPos + " " + url);
                                return false;
                            }

                            @Override
                            public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                                                           com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                           com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                                // src=DATALOCAL/DATA_CACHE=命中缓存秒显；src=REMOTE=网络拉取（若发生在切换前即正常）
                                com.github.gcd888.guoju.util.LogWriter.i(logTag + " preload ok src=" + dataSource + " " + url);
                                return false;
                            }
                        })
                        .preload();
            }
        } catch (Throwable ignored) {
        }
    }

    public void setAutoScrollEnabled(boolean enabled) {
        autoScrollEnabled = enabled;
        restartAutoSwitch();
    }

    private boolean isSwitchActive() {
        return autoScrollEnabled && !focusPaused && isAttachedToWindow();
    }

    /** 按 Hero 整体（含片名等子 view）是否持有焦点重算暂停态；各焦点回调统一走此判定。 */
    private void applyFocusPause() {
        boolean paused = hasFocus();
        if (focusPaused != paused) {
            com.github.gcd888.guoju.util.LogWriter.i(logTag + " paused=" + paused);
            focusPaused = paused;
            restartAutoSwitch();
        }
    }

    /**
     * 供条目内子 view（片名）焦点变化时同步暂停态：
     * 子 view 获/失焦不改变层自身的焦点状态，层监听感知不到，须由 binder 显式调用。
     */
    public void refreshFocusPause() {
        applyFocusPause();
    }

    /** 隐藏层整体退出焦点搜索（自身不可获焦 + 阻断子 view），可见层恢复默认（子 view 优先）。 */
    private void setLayerFocusable(View layer, boolean focusable) {
        layer.setFocusable(focusable);
        // 层根为 inflate 出的 FrameLayout，必为 ViewGroup
        ((ViewGroup) layer).setDescendantFocusability(focusable
                ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    /**
     * 重新绑定当前两层（数据字段就地更新后调用，如横图替换 vod.pic）：
     * 不重置位置/节律，setItems 引用比对短路不会漏掉字段级变更。
     */
    public void rebindCurrent() {
        if (binder == null || items == null || itemCount == 0 || layers[0] == null) {
            return;
        }
        try {
            binder.onBind(layers[activeLayer], items.get(currentPos), currentPos);
            if (itemCount > 1 && layers[1] != null) {
                int otherPos = (currentPos + 1) % itemCount;
                binder.onBind(layers[1 - activeLayer], items.get(otherPos), otherPos);
            }
            preloadNext(currentPos);
        } catch (Throwable ignored) {
        }
    }

    private void restartAutoSwitch() {
        com.github.gcd888.guoju.util.LogWriter.i(logTag + " restart active=" + isSwitchActive()
                + " count=" + itemCount + " at=" + android.os.SystemClock.uptimeMillis());
        mainHandler.removeCallbacks(switchRunnable);
        if (isSwitchActive() && itemCount > 1) {
            mainHandler.postDelayed(switchRunnable, SWITCH_INTERVAL_MS);
        }
    }

    /** 下一张淡入覆盖当前层；交叉淡入未完成前忽略重复触发，保证切换间隔稳定。 */
    private void switchToNext() {
        if (itemCount <= 1 || binder == null || layers[0] == null || layers[1] == null) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSwitchAt < CROSSFADE_DURATION_MS) {
            return;
        }
        // 节律日志：排查切换间隔异常（正常应恒为 12000ms 左右）
        com.github.gcd888.guoju.util.LogWriter.i(logTag + " switch " + currentPos + "->" + ((currentPos + 1) % itemCount)
                + " gap=" + (lastSwitchAt == 0 ? 0 : now - lastSwitchAt) + "ms");
        lastSwitchAt = now;
        int nextPos = (currentPos + 1) % itemCount;
        int nextLayer = 1 - activeLayer;
        View outgoing = layers[activeLayer];
        binder.onBind(layers[nextLayer], items.get(nextPos), nextPos);
        // 焦点开关随可见层切换：新层先放开（绑定完成后才可获焦），旧层立即封焦
        setLayerFocusable(layers[nextLayer], true);
        setLayerFocusable(outgoing, false);
        layers[nextLayer].setAlpha(0f);
        layers[nextLayer].animate().alpha(1f).setDuration(CROSSFADE_DURATION_MS).start();
        // 关键修复：旧层必须同步淡出。此前旧层 alpha 恒为 1，当旧层恰在上层（z 序）时
        // 新层在其下方淡入完全不可见——画面隔次才变化，感知间隔 12/24s 交替。
        // 双向交叉淡出后：旧层在上→旧层淡出露出新层；旧层在下→新层淡入盖住旧层，两种 z 序均正确。
        outgoing.animate().alpha(0f).setDuration(CROSSFADE_DURATION_MS).start();
        activeLayer = nextLayer;
        currentPos = nextPos;
        preloadNext(nextPos);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        restartAutoSwitch();
    }

    @Override
    protected void onDetachedFromWindow() {
        mainHandler.removeCallbacks(switchRunnable);
        super.onDetachedFromWindow();
    }
}
