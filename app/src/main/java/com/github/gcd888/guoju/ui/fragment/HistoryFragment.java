package com.github.gcd888.guoju.ui.fragment;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.gcd888.guoju.R;
import com.github.gcd888.guoju.api.ApiConfig;
import com.github.gcd888.guoju.base.BaseLazyFragment;
import com.github.gcd888.guoju.bean.Movie;
import com.github.gcd888.guoju.bean.SourceBean;
import com.github.gcd888.guoju.bean.VodInfo;
import com.github.gcd888.guoju.cache.RoomDataManger;
import com.github.gcd888.guoju.event.RefreshEvent;
import com.github.gcd888.guoju.ui.activity.DetailActivity;
import com.github.gcd888.guoju.ui.activity.FastSearchActivity;
import com.github.gcd888.guoju.ui.activity.HomeActivity;
import com.github.gcd888.guoju.ui.activity.SearchActivity;
import com.github.gcd888.guoju.ui.adapter.HistoryAdapter;
import com.github.gcd888.guoju.util.FastClickCheckUtil;
import com.github.gcd888.guoju.util.HawkConfig;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页「观看历史」tab：与分类网格同一套两层交互
 * （一级态一行预览，下键/上滑进入二级全屏多行网格）。
 */
public class HistoryFragment extends BaseLazyFragment implements HomeGridPage {
    public static HistoryAdapter historyAdapter;
    private final List<VodInfo> records = new ArrayList<>();
    private TvRecyclerView mGridView;
    private boolean isLoad = false;
    private boolean pendingFocus = false;
    private boolean eventBusRegistered = false;
    /** 数据隔离类型：RoomDataManger.MEDIA_TYPE_*（点播 0 / 短剧 2 / 音乐 3）。 */
    private int mediaType = RoomDataManger.MEDIA_TYPE_VOD;

    public static HistoryFragment newInstance(int mediaType) {
        HistoryFragment f = new HistoryFragment();
        f.mediaType = mediaType;
        return f;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_grid;
    }

    @Override
    protected void init() {
        mGridView = findViewById(R.id.mGridView);
        mGridView.setHasFixedSize(true);
        mGridView.setLayoutManager(new V7GridLayoutManager(mContext, isBaseOnWidth() ? 5 : 6));
        historyAdapter = new HistoryAdapter();
        historyAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                VodInfo vodInfo = historyAdapter.getData().get(position);
                if (vodInfo == null) return;
                // 删除模式下点击=删除该记录
                if (HawkConfig.hotVodDelete) {
                    historyAdapter.remove(position);
                    records.remove(position);
                    RoomDataManger.deleteVodRecord(vodInfo.sourceKey, vodInfo);
                    Toast.makeText(mContext, "已删除当前记录", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (ApiConfig.get().getSource(vodInfo.sourceKey) != null) {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", vodInfo.id);
                    bundle.putString("sourceKey", vodInfo.sourceKey);
                    bundle.putString("title", vodInfo.name);
                    bundle.putString("picture", vodInfo.pic);
                    jumpActivity(DetailActivity.class, bundle);
                } else {
                    Bundle bundle = new Bundle();
                    bundle.putString("title", vodInfo.name);
                    if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
                        jumpActivity(FastSearchActivity.class, bundle);
                    } else {
                        jumpActivity(SearchActivity.class, bundle);
                    }
                }
            }
        });
        historyAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                // 长按切换删除模式（与首页推荐货架一致）
                HawkConfig.hotVodDelete = !HawkConfig.hotVodDelete;
                historyAdapter.notifyDataSetChanged();
                return true;
            }
        });
        mGridView.setAdapter(historyAdapter);
        setLoadSir2(mGridView);
        mGridView.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View focused) {
                if (direction == View.FOCUS_UP) {
                    // 二级全屏网格第一行按上键回 tab 行（焦点停在当前 tab，可右键切换/下键再进网格）
                    HomeActivity.focusTabRow();
                }
                return false;
            }
        });
        EventBus.getDefault().register(this);
        eventBusRegistered = true;
        if (isLoad) {
            historyAdapter.setNewData(new ArrayList<>(records));
            if (records.isEmpty()) showEmpty();
            else showSuccess();
        } else {
            loadData();
        }
        if (pendingFocus) {
            pendingFocus = false;
            mGridView.post(new Runnable() {
                @Override
                public void run() {
                    if (mGridView != null) mGridView.requestFocus();
                }
            });
        }
    }

    @Override
    protected void onFragmentResume() {
        super.onFragmentResume();
        // 回到本页时刷新（观看记录可能已变化）
        if (isLoad) loadData();
    }

    /** 从本地数据库装载观看记录（同步快速查询，按 mediaType 隔离，与点播历史页同机制）。 */
    private void loadData() {
        List<VodInfo> all = RoomDataManger.getAllVodRecord(100, mediaType);
        records.clear();
        for (VodInfo vodInfo : all) {
            if (vodInfo.playNote != null && !vodInfo.playNote.isEmpty())
                vodInfo.note = "上次看到" + vodInfo.playNote;
            records.add(vodInfo);
        }
        isLoad = true;
        if (historyAdapter != null) {
            historyAdapter.setNewData(new ArrayList<>(records));
            if (records.isEmpty()) showEmpty();
            else showSuccess();
        }
        // 一级态若正预览本 tab，通知首页刷新货架
        HomeActivity.onGridDataLoaded();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_HISTORY_REFRESH && isLoad) {
            loadData();
        }
    }

    public static void resetDeleteMode() {
        if (historyAdapter != null) historyAdapter.notifyDataSetChanged();
    }

    @Override
    public boolean isLoad() {
        return isLoad;
    }

    /** 一级态预览快照：转成货架通用 Movie.Video。 */
    @Override
    public List<Movie.Video> getGridData() {
        List<Movie.Video> list = new ArrayList<>();
        for (VodInfo vodInfo : records) {
            Movie.Video vod = new Movie.Video();
            vod.id = vodInfo.id;
            vod.sourceKey = vodInfo.sourceKey;
            vod.name = vodInfo.name;
            vod.pic = vodInfo.pic;
            if (vodInfo.playNote != null && !vodInfo.playNote.isEmpty())
                vod.note = "上次看到" + vodInfo.playNote;
            list.add(vod);
        }
        return list;
    }

    @Override
    public void focusGrid() {
        if (rootView == null || mGridView == null) {
            pendingFocus = true;
            return;
        }
        mGridView.post(new Runnable() {
            @Override
            public void run() {
                if (mGridView != null) mGridView.requestFocus();
            }
        });
    }

    @Override
    public boolean shouldReloadOnSelect() {
        return !isLoad;
    }

    @Override
    public void forceRefresh() {
        loadData();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (eventBusRegistered) {
            EventBus.getDefault().unregister(this);
            eventBusRegistered = false;
        }
        historyAdapter = null;
    }
}
