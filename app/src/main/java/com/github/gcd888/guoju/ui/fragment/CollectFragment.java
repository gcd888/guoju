package com.github.gcd888.guoju.ui.fragment;

import android.content.Intent;
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
import com.github.gcd888.guoju.cache.RoomDataManger;
import com.github.gcd888.guoju.cache.VodCollect;
import com.github.gcd888.guoju.event.RefreshEvent;
import com.github.gcd888.guoju.ui.activity.DetailActivity;
import com.github.gcd888.guoju.ui.activity.FastSearchActivity;
import com.github.gcd888.guoju.ui.activity.HomeActivity;
import com.github.gcd888.guoju.ui.activity.SearchActivity;
import com.github.gcd888.guoju.ui.adapter.CollectAdapter;
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
 * 首页「我的收藏」tab：与分类网格同一套两层交互
 * （一级态一行预览，下键/上滑进入二级全屏多行网格）。
 */
public class CollectFragment extends BaseLazyFragment implements HomeGridPage {
    public static CollectAdapter collectAdapter;
    private final List<VodCollect> records = new ArrayList<>();
    private TvRecyclerView mGridView;
    private boolean isLoad = false;
    private boolean pendingFocus = false;
    private boolean eventBusRegistered = false;
    /** 数据隔离类型：RoomDataManger.MEDIA_TYPE_*（点播 0 / 短剧 2 / 音乐 3）。 */
    private int mediaType = RoomDataManger.MEDIA_TYPE_VOD;

    public static CollectFragment newInstance(int mediaType) {
        CollectFragment f = new CollectFragment();
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
        collectAdapter = new CollectAdapter();
        collectAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                VodCollect vodInfo = collectAdapter.getData().get(position);
                if (vodInfo == null) return;
                // 删除模式下点击=取消收藏
                if (HawkConfig.hotVodDelete) {
                    collectAdapter.remove(position);
                    records.remove(position);
                    RoomDataManger.deleteVodCollect(vodInfo.getId());
                    Toast.makeText(mContext, "已取消收藏", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (ApiConfig.get().getSource(vodInfo.sourceKey) != null) {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", vodInfo.vodId);
                    bundle.putString("sourceKey", vodInfo.sourceKey);
                    bundle.putString("title", vodInfo.name);
                    bundle.putString("picture", vodInfo.pic);
                    bundle.putBoolean("collect", true);
                    jumpActivity(DetailActivity.class, bundle);
                } else {
                    Intent newIntent = new Intent(mContext, SearchActivity.class);
                    newIntent.putExtra("title", vodInfo.name);
                    newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(newIntent);
                }
            }
        });
        collectAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                // 长按切换删除模式（与首页推荐货架一致）
                HawkConfig.hotVodDelete = !HawkConfig.hotVodDelete;
                collectAdapter.notifyDataSetChanged();
                return true;
            }
        });
        mGridView.setAdapter(collectAdapter);
        setLoadSir2(mGridView);
        mGridView.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View focused) {
                if (direction == View.FOCUS_UP) {
                    // 网格第一行按上键回 tab 行；必须消费上键，防系统搜索跨层跳级
                    HomeActivity.focusTabRow();
                    return true;
                }
                return false;
            }
        });
        EventBus.getDefault().register(this);
        eventBusRegistered = true;
        if (isLoad) {
            collectAdapter.setNewData(new ArrayList<>(records));
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
        // 回到本页时刷新（收藏可能已变化）
        if (isLoad) loadData();
    }

    /** 从本地数据库装载收藏（同步快速查询，与原收藏页一致）。 */
    private void loadData() {
        records.clear();
        records.addAll(RoomDataManger.getAllVodCollect(mediaType));
        isLoad = true;
        if (collectAdapter != null) {
            collectAdapter.setNewData(new ArrayList<>(records));
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
        if (collectAdapter != null) collectAdapter.notifyDataSetChanged();
    }

    @Override
    public boolean isLoad() {
        return isLoad;
    }

    /** 一级态预览快照：转成货架通用 Movie.Video。 */
    @Override
    public List<Movie.Video> getGridData() {
        List<Movie.Video> list = new ArrayList<>();
        for (VodCollect vodInfo : records) {
            Movie.Video vod = new Movie.Video();
            vod.id = vodInfo.vodId;
            vod.sourceKey = vodInfo.sourceKey;
            vod.name = vodInfo.name;
            vod.pic = vodInfo.pic;
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
        collectAdapter = null;
    }
}
