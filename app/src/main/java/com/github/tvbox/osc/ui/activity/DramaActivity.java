package com.github.tvbox.osc.ui.activity;

import android.graphics.Color;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.ui.adapter.GridAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.adapter.SortAdapter;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.tv.focus.FocusScaleHelper;
import com.github.tvbox.osc.ui.tv.widget.LoadMoreView;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LogWriter;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.List;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 短剧主页：源切换 + 分类 tab + 竖屏海报网格 + 分页。
 * 数据全部来自 dramas 配置段指向的 Spider（复用点播 Spider 协议与通用管道），
 * 本页面不包含任何数据源逻辑。
 */
public class DramaActivity extends BaseActivity {
    private SourceViewModel sourceViewModel;
    private SortAdapter sortAdapter;
    private GridAdapter gridAdapter;
    private TvRecyclerView mTabGrid;
    private TvRecyclerView mGridView;
    private TextView tvSourceName;
    private String dramaKey = "";
    private MovieSort.SortData currentSort = null;
    private int page = 1;
    private int maxPage = 1;
    private boolean isRequesting = false;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_drama;
    }

    @Override
    protected void init() {
        initView();
        initViewModel();
        initData();
    }

    private void initView() {
        tvSourceName = findViewById(R.id.tvSourceName);
        tvSourceName.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                showDramaSwitch();
            }
        });
        tvSourceName.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                ((TextView) v).setTextColor(hasFocus ? Color.BLACK : Color.WHITE);
            }
        });
        FocusScaleHelper.bind(tvSourceName);

        mTabGrid = findViewById(R.id.mTabGrid);
        mTabGrid.setLayoutManager(new V7LinearLayoutManager(mContext, 0, false));
        mTabGrid.setSpacingWithMargins(0, AutoSizeUtils.dp2px(mContext, 6.0f));
        sortAdapter = new SortAdapter();
        mTabGrid.setAdapter(sortAdapter);
        mTabGrid.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                FastClickCheckUtil.check(itemView);
                switchSort(position);
            }
        });

        mGridView = findViewById(R.id.mGridView);
        mGridView.setLayoutManager(new V7GridLayoutManager(mContext, isBaseOnWidth() ? 5 : 6));
        gridAdapter = new GridAdapter(false, null);
        mGridView.setAdapter(gridAdapter);
        gridAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                sourceViewModel.getList(currentSort, page, dramaKey);
            }
        }, mGridView);
        gridAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = gridAdapter.getData().get(position);
                if (video == null || video.action != null) return;
                LogWriter.i("echo-drama page: click detail id=" + video.id + " sourceKey=" + video.sourceKey + " name=" + video.name);
                android.os.Bundle bundle = new android.os.Bundle();
                bundle.putString("id", video.id);
                bundle.putString("sourceKey", video.sourceKey);
                bundle.putString("title", video.name);
                bundle.putString("picture", video.pic);
                jumpActivity(DetailActivity.class, bundle);
            }
        });
        gridAdapter.setLoadMoreView(new LoadMoreView());
        setLoadSir(mGridView);
    }

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.sortResult.observe(this, new Observer<AbsSortXml>() {
            @Override
            public void onChanged(AbsSortXml absXml) {
                List<MovieSort.SortData> tabs = absXml != null && absXml.classes != null
                        && absXml.classes.sortList != null ? absXml.classes.sortList : null;
                if (tabs == null || tabs.isEmpty()) {
                    LogWriter.e("echo-drama page: sortResult EMPTY (absXml=" + (absXml == null ? "null" : "no class")
                            + ", check homeContent raw log above; also check the source name is not a \"xx搜\" filter source)");
                    showEmpty();
                    return;
                }
                LogWriter.i("echo-drama page: sortResult tabs=" + tabs.size()
                        + " first=" + tabs.get(0).id + "/" + tabs.get(0).name);
                sortAdapter.setNewData(new ArrayList<>(tabs));
                currentSort = tabs.get(0);
                mTabGrid.setSelectedPosition(0);
                page = 1;
                loadList();
            }
        });
        sourceViewModel.listResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                isRequesting = false;
                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                    LogWriter.i("echo-drama page: listResult videos=" + absXml.movie.videoList.size()
                            + " page=" + page + " pagecount=" + absXml.movie.pagecount);
                    if (page == 1) {
                        showSuccess();
                        gridAdapter.setNewData(absXml.movie.videoList);
                    } else {
                        gridAdapter.addData(absXml.movie.videoList);
                    }
                    page++;
                    maxPage = absXml.movie.pagecount;
                    if (maxPage > 0 && page > maxPage) {
                        gridAdapter.loadMoreEnd();
                        gridAdapter.setEnableLoadMore(false);
                    } else {
                        gridAdapter.loadMoreComplete();
                        gridAdapter.setEnableLoadMore(true);
                    }
                } else {
                    LogWriter.e("echo-drama page: listResult EMPTY (absXml=" + (absXml == null ? "null" : "no videoList")
                            + " page=" + page + ", check categoryContent raw log above)");
                    if (page == 1) {
                        showEmpty();
                    } else if (page > 2) {
                        Toast.makeText(mContext, "没有更多了", Toast.LENGTH_SHORT).show();
                    }
                    gridAdapter.loadMoreEnd();
                    gridAdapter.setEnableLoadMore(false);
                }
            }
        });
    }

    private void initData() {
        dramaKey = Hawk.get(HawkConfig.DRAMA_HOME, "");
        SourceBean drama = ApiConfig.get().getDrama(dramaKey);
        LogWriter.i("echo-drama page: init savedKey=" + (dramaKey.isEmpty() ? "(none)" : dramaKey)
                + " savedFound=" + (drama != null)
                + " totalDramas=" + ApiConfig.get().getDramaBeanList().size());
        if (drama == null) {
            List<SourceBean> dramas = new ArrayList<>(ApiConfig.get().getDramaBeanList().values());
            if (dramas.isEmpty()) {
                LogWriter.e("echo-drama page: NO drama source available (config has no valid dramas section), show empty page");
                showEmpty();
                return;
            }
            drama = dramas.get(0);
            ApiConfig.get().setHomeDrama(drama);
            dramaKey = drama.getKey();
            LogWriter.i("echo-drama page: fallback to first drama key=" + dramaKey);
        }
        refreshSourceName(drama);
        loadSort();
    }

    private void refreshSourceName(SourceBean drama) {
        if (tvSourceName != null && drama != null) {
            tvSourceName.setText(drama.getName() + " ▾");
        }
    }

    /** 拉取短剧源分类：复用 homeContent 协议（sortResult 回调后自动加载第一个分类）。 */
    private void loadSort() {
        LogWriter.i("echo-drama page: loadSort key=" + dramaKey);
        showLoading();
        sourceViewModel.getSort(dramaKey);
    }

    private void switchSort(int position) {
        if (isRequesting) return;
        List<MovieSort.SortData> tabs = sortAdapter.getData();
        if (position < 0 || position >= tabs.size()) return;
        if (currentSort == tabs.get(position) && mGridView.getChildCount() > 0) return;
        currentSort = tabs.get(position);
        page = 1;
        LogWriter.i("echo-drama page: switchSort pos=" + position + " tid=" + currentSort.id + " name=" + currentSort.name);
        loadList();
    }

    private void loadList() {
        if (currentSort == null) {
            LogWriter.e("echo-drama page: loadList skipped, currentSort is null (sortResult never arrived)");
            return;
        }
        LogWriter.i("echo-drama page: loadList key=" + dramaKey + " tid=" + currentSort.id + " page=" + page);
        isRequesting = true;
        showLoading();
        gridAdapter.setEnableLoadMore(false);
        sourceViewModel.getList(currentSort, page, dramaKey);
    }

    private void showDramaSwitch() {
        final List<SourceBean> dramas = new ArrayList<>(ApiConfig.get().getDramaBeanList().values());
        if (dramas.isEmpty()) return;
        int select = 0;
        for (int i = 0; i < dramas.size(); i++) {
            if (dramas.get(i).getKey().equals(dramaKey)) {
                select = i;
                break;
            }
        }
        SelectDialog<SourceBean> dialog = new SelectDialog<>(DramaActivity.this);
        TvRecyclerView list = dialog.findViewById(R.id.list);
        int spanCount = (int) Math.floor(dramas.size() / 20.0);
        spanCount = Math.min(spanCount, 2);
        list.setLayoutManager(new V7GridLayoutManager(dialog.getContext(), spanCount + 1));
        dialog.setTip("请选择短剧数据源");
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<SourceBean>() {
            @Override
            public void click(SourceBean value, int pos) {
                dialog.dismiss();
                ApiConfig.get().setHomeDrama(value);
                dramaKey = value.getKey();
                refreshSourceName(value);
                currentSort = null;
                loadSort();
            }

            @Override
            public String getDisplay(SourceBean value) {
                return value.getName();
            }
        }, new DiffUtil.ItemCallback<SourceBean>() {
            @Override
            public boolean areItemsTheSame(@androidx.annotation.NonNull SourceBean oldItem, @androidx.annotation.NonNull SourceBean newItem) {
                return oldItem == newItem;
            }

            @Override
            public boolean areContentsTheSame(@androidx.annotation.NonNull SourceBean oldItem, @androidx.annotation.NonNull SourceBean newItem) {
                return oldItem.getKey().equals(newItem.getKey());
            }
        }, dramas, select);
        if (!dialog.isShowing()) dialog.show();
    }
}
