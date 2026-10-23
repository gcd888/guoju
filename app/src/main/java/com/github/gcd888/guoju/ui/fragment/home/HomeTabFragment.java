package com.github.gcd888.guoju.ui.fragment.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.gcd888.guoju.R;
import com.github.gcd888.guoju.base.BaseLazyFragment;
import com.github.gcd888.guoju.util.HawkConfig;
import com.github.gcd888.guoju.util.HeroInfoLayout;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 内容主页公共基类（点播 VodHomeFragment / 短剧 DramaHomeFragment / 音乐 MusicHomeFragment）。
 * 收敛各主页共性：mediaType 隔离标识、Hero 信息区动态排版、货架单行/多行布局。
 * 底部 tab 行 / 历史收藏房间 / 二级网格全由 HomeActivity 统一管线管理，基类不重复实现。
 */
public abstract class HomeTabFragment extends BaseLazyFragment {

    /** 当前主页内容类型：对应 RoomDataManger.MEDIA_TYPE_*（用于历史/收藏数据隔离）。 */
    public abstract int getMediaType();

    /** 存活的主页 tab 实例（首页多行开关即时生效分发用；视图创建/销毁维护）。 */
    private static final List<HomeTabFragment> ACTIVE = new ArrayList<>();

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (!ACTIVE.contains(this)) ACTIVE.add(this);
    }

    @Override
    public void onDestroyView() {
        ACTIVE.remove(this);
        super.onDestroyView();
    }

    /** 首页多行开关变化（设置页切换后广播）：通知所有存活主页 tab 即时重排，无需切 tab/重启。 */
    public static void notifyRecStyleChanged() {
        for (HomeTabFragment f : new ArrayList<>(ACTIVE)) {
            if (f.isAdded()) f.onRecStyleChanged();
        }
    }

    /** 子类重排钩子：重排货架布局 + tab 行贴底对齐 + 重绑 Hero（文字区显隐/排版按新开关即时重算）。 */
    protected void onRecStyleChanged() {
    }

    /**
     * Hero 信息区动态排版（导航栏底距、简介动态行数），见 HeroInfoLayout。
     * 首页多行模式：货架占满下半屏，Hero 区所有文字（剧名/影视信息/简介）整体隐藏，
     * 只留海报底图，避免文字被多行货架盖住或挤压。
     *
     * @param infoBox  剧名/信息/简介共同容器
     * @param tabTopPx 底部 tab 行顶边窗口坐标，未测得传 -1
     */
    protected void applyHeroLayout(View infoBox, TextView tvTitle, TextView tvMeta,
                                   TextView tvDesc, float tabTopPx) {
        if (Hawk.get(HawkConfig.HOME_REC_STYLE, false)) {
            infoBox.setVisibility(View.GONE);
            return;
        }
        infoBox.setVisibility(View.VISIBLE);
        HeroInfoLayout.apply(mContext, infoBox, tvTitle, tvMeta, tvDesc, tabTopPx);
    }

    /**
     * 货架布局（设置-首页多行）：关=一行多列（横向线性，高度自适应内容）；
     * 开=多行多列（V7GridLayoutManager）+ 固定高度两行，网格内部随焦点滚动翻页，
     * 货架不再整体撑高顶走 tab 行/盖住 Hero 信息区。点播/短剧/音乐三主页共用。
     *
     * @param shelf     货架 TvRecyclerView
     * @param spanCount 多行模式列数（调用方可按站点推荐 style 动态计算）
     */
    protected void applyShelfLayout(TvRecyclerView shelf, int spanCount) {
        boolean multiRow = Hawk.get(HawkConfig.HOME_REC_STYLE, false);
        shelf.setVisibility(View.VISIBLE);
        shelf.setHasFixedSize(multiRow);
        if (multiRow) {
            shelf.setLayoutManager(new V7GridLayoutManager(mContext, spanCount));
        } else {
            shelf.setLayoutManager(new V7LinearLayoutManager(mContext, V7LinearLayoutManager.HORIZONTAL, false));
        }
        int horizontal = -shelf.mHorizontalSpacingWithMargins / 2
                + getResources().getDimensionPixelSize(R.dimen.atv_safe_side);
        int padTop = getResources().getDimensionPixelSize(R.dimen.vs_10);
        int padBottom = getResources().getDimensionPixelSize(R.dimen.vs_10);
        shelf.setPadding(horizontal, padTop, horizontal, padBottom);
        ViewGroup.LayoutParams lp = shelf.getLayoutParams();
        if (multiRow) {
            lp.height = multiRowShelfHeight(shelf, padTop, padBottom);
        } else {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        shelf.setLayoutParams(lp);
    }

    /**
     * 多行货架固定高度：两行卡片 + 行间距 + 上下内边距（行高用模板 item 实测，
     * 海报/字号调整后无需同步改这里）。高度不再随数据量增长——网格内部滚动。
     */
    private int multiRowShelfHeight(TvRecyclerView shelf, int padTop, int padBottom) {
        final int rows = 2;
        int rowHeight = measureShelfRowHeight();
        int spacing = getResources().getDimensionPixelSize(R.dimen.vs_10);
        int height = padTop + padBottom + rows * rowHeight + (rows - 1) * spacing;
        // 兜底：模板测量失败（rowHeight<=0）时按海报+标题行高估算，保证货架仍有内容高度
        if (rowHeight <= 0) {
            height = padTop + padBottom + rows
                    * (getResources().getDimensionPixelSize(R.dimen.atv_home_poster_h)
                    + getResources().getDimensionPixelSize(R.dimen.vs_35))
                    + (rows - 1) * spacing;
        }
        return height;
    }

    /** 实测单行卡片高度：按卡片宽度 inflate 模板 item 后 measure（标题一行、海报定高）。 */
    private int measureShelfRowHeight() {
        try {
            View item = LayoutInflater.from(mContext).inflate(R.layout.item_user_hot_vod, null);
            int width = getResources().getDimensionPixelSize(R.dimen.atv_home_poster_w);
            item.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            return item.getMeasuredHeight();
        } catch (Throwable th) {
            return 0;
        }
    }
}
