package com.github.gcd888.guoju.ui.fragment.home;

import android.view.View;
import android.widget.Toast;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.gcd888.guoju.R;
import com.github.gcd888.guoju.cache.RoomDataManger;
import com.github.gcd888.guoju.ui.activity.HomeActivity;
import com.github.gcd888.guoju.ui.adapter.HomeHotVodAdapter;
import com.github.gcd888.guoju.ui.tv.widget.LoadMoreView;
import com.github.gcd888.guoju.ui.widget.HeroBannerView;
import com.github.gcd888.guoju.util.FastClickCheckUtil;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

/**
 * 音乐主页（HomeActivity ViewPager 房间 0，与点播/短剧主页同机制）：
 * Hero + 底部货架（单行/多行随设置）+ gridOverlay 二级叠加层，结构完全对齐短剧主页；
 * 底部 tab 行 / 观看历史 / 我的收藏 / 双击二级 / 一级预览全由 HomeActivity 统一管线管理。
 * 音乐数据源暂未开放：货架/网格为空（无数据），数据源就绪后仅需在此填充推荐数据。
 */
public class MusicHomeFragment extends HomeTabFragment {

    @Override
    public int getMediaType() {
        return RoomDataManger.MEDIA_TYPE_MUSIC;
    }

    private static MusicHomeFragment instance;
    private static TvRecyclerView tvShelfList;
    private HomeHotVodAdapter shelfAdapter;
    private TvRecyclerView gridOverlay;
    private HomeHotVodAdapter gridOverlayAdapter;
    private HeroBannerView heroBanner;

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_music_home;
    }

    @Override
    protected void onFragmentResume() {
        super.onFragmentResume();
        // 货架布局（单行/多行）与点播/短剧同机制：多行=V7GridLayoutManager+定高两行
        applyShelfLayout(tvShelfList, 5);
    }

    /** 首页多行开关变化（设置页切换后广播）：货架即时重排 + Hero 文字区重绑（显隐/排版重算）。 */
    @Override
    protected void onRecStyleChanged() {
        applyShelfLayout(tvShelfList, 5);
        HomeActivity.alignTabToShelf(tvShelfList);
        if (heroBanner != null) heroBanner.rebindCurrent();
    }

    @Override
    protected void init() {
        instance = this;
        initView();
    }

    private void initView() {
        // Hero 轮播：音乐数据源未开放，保持布局默认 gone；接入数据后按短剧 initHeroBanner 同款接入
        heroBanner = findViewById(R.id.heroBanner);

        // 底部货架（与点播/短剧同机制：单行横向，多行模式由 onFragmentResume 切网格）
        tvShelfList = findViewById(R.id.mGridView);
        tvShelfList.setLayoutManager(new V7LinearLayoutManager(mContext, V7LinearLayoutManager.HORIZONTAL, false));
        shelfAdapter = new HomeHotVodAdapter(null, "音乐");
        tvShelfList.setAdapter(shelfAdapter);
        // 货架高度/位置变化 → tab 行贴底重算（数据为空时不触发，tab 行保持初始位置）
        tvShelfList.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or2, int ob) {
                if (shelfAdapter != null && !shelfAdapter.getData().isEmpty()) {
                    HomeActivity.alignTabToShelf(tvShelfList);
                }
            }
        });
        shelfAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                jumpDetail();
            }
        });

        // 二级叠加层（与点播/短剧同机制：双击推荐 tab 展开；无数据时 enterGrid 门槛不放行）
        gridOverlay = findViewById(R.id.gridOverlay);
        gridOverlay.setLayoutManager(new V7GridLayoutManager(mContext, isBaseOnWidth() ? 5 : 6));
        gridOverlayAdapter = new HomeHotVodAdapter(null, "音乐");
        gridOverlay.setAdapter(gridOverlayAdapter);
        gridOverlayAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                jumpDetail();
            }
        });
        gridOverlay.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View focused) {
                if (direction == View.FOCUS_UP) {
                    HomeActivity.focusTabRow();
                }
                return false;
            }
        });
        gridOverlayAdapter.setLoadMoreView(new LoadMoreView());
    }

    /** 音乐点击：数据源未开放只提示（货架/网格无数据，正常情况不可达）。 */
    private void jumpDetail() {
        Toast.makeText(mContext, "音乐源暂未开放，敬请期待", Toast.LENGTH_LONG).show();
    }

    /** 收起二级叠加层（切页时统一调用；无数据常态不可达，防御残留）。 */
    public static void hideMusicRecGrid() {
        if (instance != null && instance.gridOverlay != null) {
            instance.gridOverlay.setVisibility(View.GONE);
        }
    }
}
