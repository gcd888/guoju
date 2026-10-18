package com.github.tvbox.osc.ui.activity;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.IntEvaluator;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.BounceInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.adapter.HomePageAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.adapter.SortAdapter;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.dialog.TipDialog;
import com.github.tvbox.osc.ui.fragment.CollectFragment;
import com.github.tvbox.osc.ui.fragment.GridFragment;
import com.github.tvbox.osc.ui.fragment.HistoryFragment;
import com.github.tvbox.osc.ui.fragment.HomeGridPage;
import com.github.tvbox.osc.ui.fragment.UserFragment;
import com.github.tvbox.osc.ui.tv.focus.FocusScaleHelper;
import com.github.tvbox.osc.ui.tv.widget.DefaultTransformer;
import com.github.tvbox.osc.ui.tv.widget.FixedSpeedScroller;
import com.github.tvbox.osc.ui.tv.widget.NoScrollViewPager;
import com.github.tvbox.osc.ui.tv.widget.ViewObj;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LogWriter;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class HomeActivity extends BaseActivity {
    private static HomeActivity instance;

    /** 首页两层状态：false=一级（Hero+tab+一行货架），true=二级（tab 吸顶+全屏多行网格）。 */
    private boolean gridExpanded = false;

    /** tab 双击检测：上一次点击位置与时间戳；pendingTabAction 为单击已选 tab 的延迟快捷逻辑。 */
    private int lastTabClickPos = -1;
    private long lastTabClickTime = 0;
    private Runnable pendingTabAction = null;
    /** 页面数据已就绪（可在两层间切换）。 */
    private volatile boolean pageDataReady = false;

    private LinearLayout topLayout;
    private TextView tvName;
    private TvRecyclerView mGridView;
    private NoScrollViewPager mViewPager;
    private SourceViewModel sourceViewModel;
    private SortAdapter sortAdapter;
    private HomePageAdapter pageAdapter;
    private View currentView;
    private final List<BaseLazyFragment> fragments = new ArrayList<>();
    private boolean isDownOrUp = false;
    private boolean sortChange = false;
    private int currentSelected = 0;
    private int sortFocused = 0;
    public View sortFocusView = null;
    private String loadingSourceKey;
    private String previousHomeName;
    private SourceBean previousHomeSource;
    private boolean homeSortLoading = false;
    private boolean refreshHomeRec = false;
    private final Handler mHandler = new Handler();
    private long mExitTime = 0;
    private boolean eventBusRegistered = false;
    private final Runnable refreshTopInfoTextSizeRunnable = new Runnable() {
        @Override
        public void run() {
            refreshTopInfoTextSize();
        }
    };
    private final Runnable refreshTopLayoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (topLayout == null || isActivityUnavailable() || currentSelected != 0 || topHide != 0) {
                return;
            }
            // OnePlus devices may finish applying immersive mode after the first measure.
            // Re-apply the visible top state once the final display metrics are available.
            hideSysBar();
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) topLayout.getLayoutParams();
            params.topMargin = 0;
            params.height = AutoSizeUtils.mm2px(HomeActivity.this, 30.0f);
            topLayout.setLayoutParams(params);
            topLayout.setAlpha(1.0f);
            refreshTopInfoTextSize();
            topLayout.requestLayout();
        }
    };

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_home;
    }

    @Override
    protected boolean shouldRefreshAutoSize() {
        return true;
    }

    boolean useCacheConfig = false;

    @Override
    protected void init() {
        EventBus.getDefault().register(this);
        eventBusRegistered = true;
        ControlManager.get().startServer();
        initView();
        initViewModel();
        useCacheConfig = false;
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            Bundle bundle = intent.getExtras();
            useCacheConfig = bundle.getBoolean("useCache", false);
        }
        initData();
    }

    private void initView() {
        instance = this;
        this.topLayout = findViewById(R.id.topLayout);
        this.tvName = findViewById(R.id.tvName);
        this.mGridView = findViewById(R.id.mGridView);
        this.mViewPager = findViewById(R.id.mViewPager);
        initTopNav();
        this.sortAdapter = new SortAdapter();
        this.mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        this.mGridView.setSpacingWithMargins(0, AutoSizeUtils.dp2px(this.mContext, 10.0f));
        this.mGridView.setAdapter(this.sortAdapter);
        sortAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                mGridView.post(() -> {
                    View firstChild = Objects.requireNonNull(mGridView.getLayoutManager()).findViewByPosition(0);
                    if (firstChild != null) {
                        mGridView.setSelectedPosition(0);
                        firstChild.requestFocus();
                    }
                });
            }
        });
        this.mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            public void onItemPreSelected(TvRecyclerView tvRecyclerView, View view, int position) {
                if (view != null && !HomeActivity.this.isDownOrUp) {
                    mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                TextView textView = view.findViewById(R.id.tvTitle);
                                textView.getPaint().setFakeBoldText(false);
                                if (sortFocused == p) {
                                    view.animate().scaleX(1.1f).scaleY(1.1f).setInterpolator(new BounceInterpolator()).setDuration(300).start();
                                } else {
                                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).start();
                                    view.findViewById(R.id.tvFilter).setVisibility(View.GONE);
                                    view.findViewById(R.id.tvFilterColor).setVisibility(View.GONE);
                                }
                                textView.invalidate();
                            }

                            public final int p = position;
                        }, 10);
                }
            }

            public void onItemSelected(TvRecyclerView tvRecyclerView, View view, int position) {
                if (view != null) {
                    HomeActivity.this.currentView = view;
                    HomeActivity.this.isDownOrUp = false;
                    HomeActivity.this.sortChange = true;
                    view.animate().scaleX(1.1f).scaleY(1.1f).setInterpolator(new BounceInterpolator()).setDuration(300).start();
                    TextView textView = view.findViewById(R.id.tvTitle);
                    textView.getPaint().setFakeBoldText(true);
                    textView.invalidate();
                    MovieSort.SortData sortData = sortAdapter.getItem(position);
                    if (!sortData.filters.isEmpty()) {
                        showFilterIcon(sortData.filterSelectCount());
                    }
                    HomeActivity.this.sortFocusView = view;
                    HomeActivity.this.sortFocused = position;
                    mHandler.removeCallbacks(mDataRunnable);
                    mHandler.postDelayed(mDataRunnable, 200);
                }
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                // 双击 tab（400ms 内两击同位）：进入该 tab 的全屏多行网格
                long now = android.os.SystemClock.uptimeMillis();
                boolean doubleTab = position == lastTabClickPos && now - lastTabClickTime < 400;
                lastTabClickPos = position;
                lastTabClickTime = now;
                if (doubleTab) {
                    lastTabClickTime = 0; // 防三击连续触发
                    mHandler.removeCallbacks(pendingTabAction); // 撤销第一击的延迟快捷逻辑
                    if (!gridExpanded && position < fragments.size()) {
                        sortFocused = position;
                        enterGrid();
                    }
                    return;
                }
                if (itemView != null && currentSelected == position) {
                    // 单击已选 tab：延迟执行原快捷逻辑（筛选/站源切换），留出双击判定窗口
                    final int pos = position;
                    mHandler.removeCallbacks(pendingTabAction);
                    pendingTabAction = new Runnable() {
                        @Override
                        public void run() {
                            BaseLazyFragment baseLazyFragment = fragments.get(pos);
                            if ((baseLazyFragment instanceof GridFragment) && !sortAdapter.getItem(pos).filters.isEmpty()) {// 弹出筛选
                                ((GridFragment) baseLazyFragment).showFilter();
                            } else if (baseLazyFragment instanceof UserFragment) {
                                showSiteSwitch();
                            }
                        }
                    };
                    mHandler.postDelayed(pendingTabAction, 400);
                }
            }
        });

        this.mGridView.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            public boolean onInBorderKeyEvent(int direction, View view) {
                // 顶部导航栏接收上边界焦点，不再拦截刷新
                if (direction == View.FOCUS_UP) {
                    return false;
                }
                if (direction != View.FOCUS_DOWN) {
                    return false;
                }
                if (gridExpanded) {
                    // 二级态：tab 行下键进入当前 tab 的全屏网格
                    if (sortFocused >= fragments.size()) {
                        return false;
                    }
                    enterCurrentGrid();
                    return true;
                }
                if (sortFocused >= fragments.size()) {
                    return false;
                }
                BaseLazyFragment baseLazyFragment = fragments.get(sortFocused);
                if (!(baseLazyFragment instanceof HomeGridPage)) {
                    return false;
                }
                return !((HomeGridPage) baseLazyFragment).isLoad();
            }
        });
        tvName.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                if(dataInitOk && jarInitOk){
                    String jar=ApiConfig.get().getHomeSourceBean().getJar();
                    String jarUrl=!jar.isEmpty()?jar:ApiConfig.get().getSpider();
                    String jarSource = jarUrl.split(";md5;")[0];
                    File cspCacheDir = new File(FileUtils.getFilePath() + "/csp/" + MD5.string2MD5(jarSource) + ".jar");
                    File jarCacheDir = new File(FileUtils.getCachePath() + "/jar/" + MD5.string2MD5(jarSource) + ".jar");
                    File jarFullCacheDir = new File(FileUtils.getCachePath() + "/jar/" + MD5.string2MD5(jarUrl) + ".jar");
                    Toast.makeText(mContext, "缓存已清除", Toast.LENGTH_LONG).show();
                    new Thread(() -> {
                        try {
                            FileUtils.deleteFile(cspCacheDir);
                            FileUtils.deleteFile(jarCacheDir);
                            FileUtils.deleteFile(jarFullCacheDir);
                            FileUtils.clearSpiderCacheFiles();
                            ApiConfig.get().clearSpiderCache();
                            refreshHome();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start();

                }else {
                    jumpActivity(SettingActivity.class);
                }
            }
        });
        tvName.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                // 长按首页图标切换站源，切换后重新加载首页内容
                showSiteSwitch();
                return true;
            }
        });
        setLoadSir(this.mViewPager);
        //mHandler.postDelayed(mFindFocus, 500);
    }


    private boolean skipNextUpdate = false;

    /** 顶部导航：Home 图标/直播/搜索/推送/收藏/设置（历史入口在推荐行尾部）。 */
    private void initTopNav() {
        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int id = v.getId();
                if (id == R.id.navHome) {
                    backToHome();
                } else if (id == R.id.navLive) {
                    jumpActivity(LivePlayActivity.class);
                } else if (id == R.id.navSearch) {
                    jumpActivity(SearchActivity.class);
                } else if (id == R.id.navPush) {
                    // 配置对话框（多仓/接口配置）
                    new com.github.tvbox.osc.ui.dialog.ConfigDialog(HomeActivity.this).show();
                } else if (id == R.id.navSetting) {
                    jumpActivity(SettingActivity.class);
                }
            }
        };
        int[] navIds = {R.id.navHome, R.id.navLive, R.id.navSearch, R.id.navPush, R.id.navSetting};
        for (int navId : navIds) {
            View nav = findViewById(navId);
            nav.setOnClickListener(listener);
            FocusScaleHelper.bind(nav);
        }
        // 站源切换入口：长按 Home 图标（原顶栏站源名的长按功能）
        findViewById(R.id.navHome).setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (dataInitOk && jarInitOk) {
                    showSiteSwitch();
                    return true;
                }
                return false;
            }
        });
    }

    /** tab 行尾部固定页：观看历史 / 我的收藏（与分类网格同一套两层交互）。 */
    private static final String TAB_NAV_HISTORY = "nav_history";
    private static final String TAB_NAV_COLLECT = "nav_collect";

    private static void appendNavTabs(List<MovieSort.SortData> list) {
        if (list == null) {
            return;
        }
        list.add(new MovieSort.SortData(TAB_NAV_HISTORY, "观看历史"));
        list.add(new MovieSort.SortData(TAB_NAV_COLLECT, "我的收藏"));
    }

    /** 顶部 Home 图标：回到推荐首页（一级态、推荐 tab）。 */
    private void backToHome() {
        gridExpanded = false;
        if (mViewPager != null && mViewPager.getCurrentItem() != 0) {
            mViewPager.setCurrentItem(0);
        }
        if (mGridView != null) {
            mGridView.setSelection(0);
            mGridView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mGridView != null) {
                        mGridView.requestFocus();
                        updateTabAnchor();
                    }
                }
            }, 120);
        } else {
            updateTabAnchor();
        }
    }

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.sortResult.observe(this, new Observer<AbsSortXml>() {
            @Override
            public void onChanged(AbsSortXml absXml) {
                if (skipNextUpdate) {
                    skipNextUpdate = false;
                    return;
                }
                if (!homeSortLoading && loadingSourceKey == null) {
                    return;
                }
                if (absXml != null && absXml.sourceKey != null && loadingSourceKey != null && !loadingSourceKey.equals(absXml.sourceKey)) {
                    return;
                }
                SourceBean home = ApiConfig.get().getHomeSourceBean();
                showSuccess();
                clearHomePages();
                List<MovieSort.SortData> newSortData;
                if (absXml != null && absXml.classes != null && absXml.classes.sortList != null) {
                    newSortData = DefaultConfig.adjustSort(ApiConfig.get().getHomeSourceBean().getKey(), absXml.classes.sortList, true);
                } else {
                    newSortData = DefaultConfig.adjustSort(ApiConfig.get().getHomeSourceBean().getKey(), new ArrayList<>(), true);
                }
                updateSortData(newSortData);
                initViewPager(absXml);
                updateHomeRec(absXml);
                if (home != null && home.getName() != null && !home.getName().isEmpty()) tvName.setText(home.getName());
                tvName.clearAnimation();
                homeSortLoading = false;
                loadingSourceKey = null;
                previousHomeName = null;
                previousHomeSource = null;
            }
        });
    }

    private boolean dataInitOk = false;
    private boolean jarInitOk = false;
    private boolean searchSpiderWarmStarted = false;
    private TipDialog mConfigErrorDialog;

    private void initData() {
        if (dataInitOk && jarInitOk) {
            loadHomeSort(false);
            if (hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                LOG.e("有");
            } else {
                LOG.e("无");
            }
            if (!useCacheConfig && Hawk.get(HawkConfig.DEFAULT_LOAD_LIVE, false)) {
                jumpActivity(LivePlayActivity.class);
            }
            //爬虫预热 仅首次加载
            if(!useCacheConfig)warmSearchSpidersOnce();
            return;
        }
        tvNameAnimation();
        showLoading();
        if (dataInitOk && !jarInitOk) {
            if (!ApiConfig.get().getSpider().isEmpty()) {
                ApiConfig.get().loadJar(useCacheConfig, ApiConfig.get().getSpider(), new ApiConfig.LoadConfigCallback() {
                    @Override
                    public void success() {
                        jarInitOk = true;
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
//                                if (!useCacheConfig) Toast.makeText(HomeActivity.this, "自定义jar加载成功", Toast.LENGTH_SHORT).show();
                                initData();
                            }
                        }, 50);
                    }

                    @Override
                    public void notice(String msg) {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(HomeActivity.this, msg, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }

                    @Override
                    public void error(String msg) {
                        jarInitOk = true;
                        dataInitOk = true;
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(HomeActivity.this, msg+" jar load err", Toast.LENGTH_SHORT).show();
                                initData();
                            }
                        },50);
                    }
                });
            }
            return;
        }
        ApiConfig.get().loadConfig(useCacheConfig, new ApiConfig.LoadConfigCallback() {
            @Override
            public void notice(String msg) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(HomeActivity.this, msg, Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void success() {
                dataInitOk = true;
                if (ApiConfig.get().getSpider().isEmpty()) {
                    jarInitOk = true;
                }
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        initData();
                    }
                }, 50);
            }

            @Override
            public void error(String msg) {
                if (msg.equalsIgnoreCase("-1")) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            dataInitOk = true;
                            jarInitOk = true;
                            initData();
                        }
                    });
                    return;
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isActivityUnavailable()) {
                            return;
                        }
                        if (mConfigErrorDialog == null)
                            mConfigErrorDialog = new TipDialog(HomeActivity.this, msg, "重试", "取消", new TipDialog.OnListener() {
                                @Override
                                public void left() {
                                    mHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            dismissConfigErrorDialog();
                                            initData();
                                        }
                                    });
                                }

                                @Override
                                public void right() {
                                    dataInitOk = true;
                                    jarInitOk = true;
                                    mHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            dismissConfigErrorDialog();
                                            initData();
                                        }
                                    });
                                }

                                @Override
                                public void cancel() {
                                    dataInitOk = true;
                                    jarInitOk = true;
                                    mHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            dismissConfigErrorDialog();
                                            initData();
                                        }
                                    });
                                }
                            });
                        if (!mConfigErrorDialog.isShowing())
                            mConfigErrorDialog.show();
                    }
                });
            }
        }, this);
    }

    private void warmSearchSpidersOnce() {
        if (searchSpiderWarmStarted) return;
        searchSpiderWarmStarted = true;
        ApiConfig.get().warmSearchSpiders();
    }

    private void loadHomeSort(boolean keepCurrentContent) {
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        homeSortLoading = keepCurrentContent;
        if (keepCurrentContent && home != null && home.getName() != null && !home.getName().isEmpty()) {
            previousHomeName = tvName.getText() == null ? null : tvName.getText().toString();
            tvName.setText(home.getName());
        }
        tvNameAnimation();
        if (home == null) {
            loadingSourceKey = null;
            if (!keepCurrentContent) showLoading();
            sourceViewModel.getSort(null);
            return;
        }
        loadingSourceKey = home.getKey();
        if (!keepCurrentContent) {
            showLoading();
        }
        sourceViewModel.getSort(loadingSourceKey);
    }

    private void initViewPager(AbsSortXml absXml) {
        if (sortAdapter.getData().size() > 0) {
            for (MovieSort.SortData data : sortAdapter.getData()) {
                if (data.id.equals("my0")) {
                    if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1 && absXml != null && absXml.videoList != null && absXml.videoList.size() > 0) {
                        fragments.add(UserFragment.newInstance(absXml.videoList));
                    } else {
                        fragments.add(UserFragment.newInstance(null));
                    }
                } else if (TAB_NAV_HISTORY.equals(data.id)) {
                    fragments.add(HistoryFragment.newInstance());
                } else if (TAB_NAV_COLLECT.equals(data.id)) {
                    fragments.add(CollectFragment.newInstance());
                } else {
                    fragments.add(GridFragment.newInstance(data));
                }
            }
            pageAdapter = new HomePageAdapter(getSupportFragmentManager(), fragments);
            try {
                Field field = ViewPager.class.getDeclaredField("mScroller");
                field.setAccessible(true);
                FixedSpeedScroller scroller = new FixedSpeedScroller(mContext, new AccelerateInterpolator());
                field.set(mViewPager, scroller);
                scroller.setmDuration(300);
            } catch (Exception e) {
            }
            mViewPager.setPageTransformer(true, new DefaultTransformer());
            mViewPager.setAdapter(pageAdapter);
            mViewPager.setCurrentItem(currentSelected, false);
            updateTabAnchor();
        }
    }

    private void clearHomePages() {
        mHandler.removeCallbacks(mDataRunnable);
        currentSelected = 0;
        sortFocused = 0;
        sortChange = false;
        sortFocusView = null;
        currentView = null;
        gridExpanded = false;
        pageDataReady = false;
        updateTabAnchor();
        if (pageAdapter != null) {
            mViewPager.setAdapter(null);
            pageAdapter.removeAll();
            pageAdapter = null;
        } else if (!fragments.isEmpty()) {
            fragments.clear();
        }
    }

    private void updateSortData(List<MovieSort.SortData> newSortData) {
        if (newSortData == null) {
            newSortData = new ArrayList<>();
        }
        appendNavTabs(newSortData);
        List<MovieSort.SortData> oldSortData = sortAdapter.getData();
        if (oldSortData.isEmpty()
                || newSortData.isEmpty()
                || oldSortData.get(0) == null
                || newSortData.get(0) == null
                || !"my0".equals(oldSortData.get(0).id)
                || !"my0".equals(newSortData.get(0).id)) {
            sortAdapter.setNewData(newSortData);
            return;
        }
        int oldTailCount = oldSortData.size() - 1;
        if (oldTailCount > 0) {
            oldSortData.subList(1, oldSortData.size()).clear();
            sortAdapter.notifyItemRangeRemoved(1, oldTailCount);
        }
        if (newSortData.size() > 1) {
            oldSortData.addAll(newSortData.subList(1, newSortData.size()));
            sortAdapter.notifyItemRangeInserted(1, newSortData.size() - 1);
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onBackPressed() {
        // 打断加载
        if (homeSortLoading) {
            cancelHomeSortLoading();
            return;
        }
        if (isLoading()) {
            refreshEmpty();
            return;
        }
        // 如果处于 VOD 删除模式，则退出该模式并刷新界面
        if (HawkConfig.hotVodDelete) {
            HawkConfig.hotVodDelete = false;
            UserFragment.homeHotVodAdapter.notifyDataSetChanged();
            HistoryFragment.resetDeleteMode();
            CollectFragment.resetDeleteMode();
            return;
        }

        // 检查 fragments 状态
        if (this.fragments.size() <= 0 || this.sortFocused < 0) {
            doExit();
            return;
        }
        // 焦点在 tab 行尾部入口（历史/收藏）时，返回先回到推荐 tab
        if (this.sortFocused >= this.fragments.size()) {
            this.mGridView.setSelection(0);
            return;
        }

        BaseLazyFragment baseLazyFragment = this.sortFocused < this.fragments.size()
                ? this.fragments.get(this.sortFocused) : null;
        if (baseLazyFragment instanceof GridFragment) {
            GridFragment grid = (GridFragment) baseLazyFragment;
            // 二级全屏网格先回一级（Hero + tab + 一行货架）
            if (gridExpanded) {
                exitToHome();
                return;
            }
            // 如果当前 Fragment 能恢复之前保存的 UI 状态，则直接返回
            if (grid.restoreView()) {
                return;
            }
            // 如果 sortFocusView 存在且没有获取焦点，则请求焦点
            if (this.sortFocusView != null && !this.sortFocusView.isFocused()) {
                this.sortFocusView.requestFocus();
            }
            // 如果当前不是第一个界面，则将列表设置到第一项
            else if (this.sortFocused != 0) {
                this.mGridView.setSelection(0);
            } else {
                doExit();
            }
        } else if (baseLazyFragment instanceof UserFragment) {
            // 推荐全屏网格（双击展开）先回一级，再谈退出
            if (gridExpanded) {
                exitToHome();
                return;
            }
            doExit();
        } else if (baseLazyFragment instanceof HomeGridPage) {
            // 观看历史/我的收藏与分类网格同规则：二级先回一级，一级回 tab 行
            if (gridExpanded) {
                exitToHome();
                return;
            }
            if (this.sortFocusView != null && !this.sortFocusView.isFocused()) {
                this.sortFocusView.requestFocus();
            } else if (this.sortFocused != 0) {
                this.mGridView.setSelection(0);
            } else {
                doExit();
            }
        } else {
            doExit();
        }
    }

    private void doExit() {
        // 如果两次返回间隔小于 2000 毫秒，则退出应用
        if (System.currentTimeMillis() - mExitTime < 2000) {
            AppManager.getInstance().finishAllActivity();
            unregisterEventBus();
            ControlManager.get().stopServer();
            finish();
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        } else {
            // 否则仅提示用户，再按一次退出应用
            mExitTime = System.currentTimeMillis();
            Toast.makeText(mContext, "再按一次返回键退出应用", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshTopInfoTextSize();
        mHandler.removeCallbacks(refreshTopInfoTextSizeRunnable);
        mHandler.postDelayed(refreshTopInfoTextSizeRunnable, 350);
        mHandler.removeCallbacks(refreshTopLayoutRunnable);
        mHandler.postDelayed(refreshTopLayoutRunnable, 450);
    }


    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(refreshTopInfoTextSizeRunnable);
        mHandler.removeCallbacks(refreshTopLayoutRunnable);
    }

    private void refreshTopInfoTextSize() {
        if (tvName == null) {
            return;
        }
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.ts_22));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_PUSH_URL) {
            if (ApiConfig.get().getSource("push_agent") != null) {
                Intent newIntent = new Intent(mContext, DetailActivity.class);
                newIntent.putExtra("id", (String) event.obj);
                newIntent.putExtra("sourceKey", "push_agent");
                newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                HomeActivity.this.startActivity(newIntent);
            }
        } else if (event.type == RefreshEvent.TYPE_FILTER_CHANGE) {
            if (currentView != null) {
                showFilterIcon((int) event.obj);
            }
        } else if (event.type == RefreshEvent.TYPE_HOME_SOURCE_CHANGE) {
            refreshHome(false);
        }
    }

    private void showFilterIcon(int count) {
        boolean visible = count > 0;
        currentView.findViewById(R.id.tvFilterColor).setVisibility(visible ? View.VISIBLE : View.GONE);
        currentView.findViewById(R.id.tvFilter).setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    private final Runnable mDataRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sortChange) {
                return;
            }
            sortChange = false;
            // 一级态 tab 预览：只换首页一行货架，不切页不进二级
            if (!gridExpanded) {
                if (sortFocused == 0) {
                    // 回到推荐 tab：恢复首页货架（豆瓣热播/站点推荐）
                    UserFragment.restoreHomeShelf();
                    return;
                }
                if (sortFocused < fragments.size()
                        && fragments.get(sortFocused) instanceof HomeGridPage) {
                    HomeGridPage page = (HomeGridPage) fragments.get(sortFocused);
                    if (page.isLoad()) {
                        UserFragment.showSortPreview(page.getGridData());
                    } else if (page.shouldReloadOnSelect()) {
                        page.forceRefresh();
                    }
                }
                return;
            }
            // 二级态：切页 + 数据不完整时刷新
            if (sortFocused != currentSelected && sortFocused < fragments.size()) {
                currentSelected = sortFocused;
                mViewPager.setCurrentItem(sortFocused, false);
            }
            // 二级态推荐叠加层/货架同步：离开推荐页物理收起叠加层并隐藏货架，
            // 回推荐页恢复——杜绝任何切页状态异常下的内容残留
            if (sortFocused == 0) {
                UserFragment.showRecGrid();
            } else {
                UserFragment.hideRecGrid();
            }
            LogWriter.i("tab switch page=" + sortFocused
                    + " scrollX=" + (mViewPager == null ? -1 : mViewPager.getScrollX()));
            if (sortFocused >= 0 && sortFocused < fragments.size()
                    && fragments.get(sortFocused) instanceof HomeGridPage) {
                HomeGridPage grid = (HomeGridPage) fragments.get(sortFocused);
                if (grid.shouldReloadOnSelect()) {
                    grid.forceRefresh();
                }
            }
        }
    };

    /** 首页货架数据就绪（豆瓣或站点推荐）：解除 enterGrid 门槛。 */
    public static void notifyHomeDataReady() {
        final HomeActivity act = instance;
        if (act != null) {
            act.pageDataReady = true;
        }
    }

    /** 首页网格页数据装载完成：进入二级门槛置位；一级态正预览该页则刷新货架。 */
    public static void onGridDataLoaded() {
        final HomeActivity act = instance;
        if (act == null) {
            return;
        }
        act.mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!act.pageDataReady) {
                    act.pageDataReady = true;
                }
                if (!act.gridExpanded
                        && act.sortFocused >= 0 && act.sortFocused < act.fragments.size()
                        && act.fragments.get(act.sortFocused) instanceof HomeGridPage) {
                    UserFragment.showSortPreview(((HomeGridPage) act.fragments.get(act.sortFocused)).getGridData());
                }
            }
        });
    }

    /** 一级列表（下键 / 上滑到底）进入二级全屏网格：切到当前分类页并聚焦。 */
    public static void enterGrid() {
        final HomeActivity act = instance;
        if (act == null || !act.pageDataReady) {
            return;
        }
        act.mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (act.gridExpanded || act.isLoading()
                        || act.sortFocused >= act.fragments.size()) {
                    return;
                }
                act.gridExpanded = true;
                act.currentSelected = act.sortFocused;
                act.mViewPager.setCurrentItem(act.sortFocused, false);
                act.changeTop(true); // 二级新页面隐藏顶部导航栏
                act.updateTabAnchor();
                if (act.fragments.get(act.sortFocused) instanceof UserFragment) {
                    // 推荐 tab 无独立网格页：展开 UserFragment 内的全屏网格叠加层（不抢焦点）
                    UserFragment.showRecGrid();
                }
                // 二级页焦点先落在 tab 行（当前 tab 选中态）；右键切 tab，下键才进网格
                act.mGridView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (act.gridExpanded) {
                            act.mGridView.setSelection(act.sortFocused);
                            act.mGridView.requestFocus();
                        }
                    }
                });
            }
        });
    }

    /** 二级态：tab 行获焦（当前 tab 选中效果），网格第一行上键回到 tab 行。 */
    public static void focusTabRow() {
        final HomeActivity act = instance;
        if (act == null || !act.gridExpanded) {
            return;
        }
        act.mGridView.post(new Runnable() {
            @Override
            public void run() {
                if (act.gridExpanded) {
                    act.mGridView.setSelection(act.sortFocused);
                    act.mGridView.requestFocus();
                }
            }
        });
    }

    /** 二级态：tab 行下键进入当前 tab 的全屏网格（分类/历史/收藏页或推荐叠加层）。 */
    public static void enterCurrentGrid() {
        final HomeActivity act = instance;
        if (act == null || !act.gridExpanded
                || act.sortFocused < 0 || act.sortFocused >= act.fragments.size()) {
            return;
        }
        BaseLazyFragment f = act.fragments.get(act.sortFocused);
        if (f instanceof HomeGridPage) {
            ((HomeGridPage) f).focusGrid();
        } else if (f instanceof UserFragment) {
            UserFragment.focusRecGrid();
        }
    }

    /** 二级网格第一行按上键回一级（Hero + tab + 一行货架）。 */
    public static void exitToHome() {
        final HomeActivity act = instance;
        if (act == null) {
            return;
        }
        act.mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!act.gridExpanded) {
                    return;
                }
                act.gridExpanded = false;
                act.currentSelected = 0;
                act.mViewPager.setCurrentItem(0, false);
                act.changeTop(false); // 回一级恢复顶部导航栏
                act.updateTabAnchor();
                UserFragment.hideRecGrid(); // 推荐全屏网格叠加层收起（幂等）
                // 回一级焦点回 tab 行「推荐」：tab 选中态=焦点态（白字加粗），与进入二级前视觉一致；
                // setSelection 只改选中位置不产生焦点，聚焦货架会让 tab 全部失焦变灰
                if (act.mGridView != null) {
                    act.mGridView.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!act.gridExpanded && act.mGridView != null) {
                                act.mGridView.setSelection(0);
                                act.mGridView.requestFocus();
                            }
                        }
                    });
                }
            }
        });
    }

    private long menuKeyDownTime = 0;
    private static final long LONG_PRESS_THRESHOLD = 2000; // 设置长按的阈值，单位是毫秒
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (topHide < 0)
            return false;
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                menuKeyDownTime = System.currentTimeMillis();
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                long pressDuration = System.currentTimeMillis() - menuKeyDownTime;
                if (pressDuration >= LONG_PRESS_THRESHOLD) {
                    jumpActivity(SettingActivity.class);;
                }else {
                    showSiteSwitch();
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    byte topHide = 0;

    private void changeTop(boolean hide) {
        ViewObj viewObj = new ViewObj(topLayout, (ViewGroup.MarginLayoutParams) topLayout.getLayoutParams());
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {

            }

            @Override
            public void onAnimationEnd(Animator animation) {
                topHide = (byte) (hide ? 1 : 0);
            }

            @Override
            public void onAnimationCancel(Animator animation) {

            }

            @Override
            public void onAnimationRepeat(Animator animation) {

            }
        });
        if (hide && topHide == 0) {
            animatorSet.playTogether(ObjectAnimator.ofObject(viewObj, "marginTop", new IntEvaluator(),
                            0,
                            AutoSizeUtils.mm2px(this.mContext, 0.0f)),
                    ObjectAnimator.ofObject(viewObj, "height", new IntEvaluator(),
                            AutoSizeUtils.mm2px(this.mContext, 30.0f),
                            AutoSizeUtils.mm2px(this.mContext, 1.0f)),
                    ObjectAnimator.ofFloat(this.topLayout, "alpha", 1.0f, 0.0f));
            animatorSet.setDuration(200);
            animatorSet.start();
            return;
        }
        if (!hide && topHide == 1) {
            animatorSet.playTogether(ObjectAnimator.ofObject(viewObj, "marginTop", new IntEvaluator(),
                            AutoSizeUtils.mm2px(this.mContext, 0.0f),
                            0),
                    ObjectAnimator.ofObject(viewObj, "height", new IntEvaluator(),
                            AutoSizeUtils.mm2px(this.mContext, 1.0f),
                            AutoSizeUtils.mm2px(this.mContext, 30.0f)),
                    ObjectAnimator.ofFloat(this.topLayout, "alpha", 0.0f, 1.0f));
            animatorSet.setDuration(200);
            animatorSet.start();
        }
    }

    /** 一级态 tab 停在 Hero 底部（300），二级态 tab 吸顶（30）。 */
    private void updateTabAnchor() {
        if (mGridView == null) {
            return;
        }
        float target = AutoSizeUtils.mm2px(this.mContext, gridExpanded ? 30.0f : 300.0f);
        mGridView.animate().translationY(target).setDuration(200).start();
    }

    @Override
    protected void onDestroy() {
        dismissHomeDialogs();
        mHandler.removeCallbacksAndMessages(null);
        instance = null;
        super.onDestroy();
        unregisterEventBus();
        if (isFinishing()) {
            ControlManager.get().stopServer();
        }
    }

    private void unregisterEventBus() {
        if (eventBusRegistered) {
            EventBus.getDefault().unregister(this);
            eventBusRegistered = false;
        }
    }

    private SelectDialog<SourceBean> mSiteSwitchDialog;

    void showSiteSwitch() {
        if (isActivityUnavailable()) return;
        List<SourceBean> sites = ApiConfig.get().getSwitchSourceBeanList();
        if (sites.isEmpty()) return;
        int select = sites.indexOf(ApiConfig.get().getHomeSourceBean());
        if (select < 0 || select >= sites.size()) select = 0;
        if (mSiteSwitchDialog == null) {
            mSiteSwitchDialog = new SelectDialog<>(HomeActivity.this);
            TvRecyclerView tvRecyclerView = mSiteSwitchDialog.findViewById(R.id.list);
            // 根据 sites 数量动态计算列数
            int spanCount = (int) Math.floor(sites.size() / 20.0);
            spanCount = Math.min(spanCount, 2);
            tvRecyclerView.setLayoutManager(new V7GridLayoutManager(mSiteSwitchDialog.getContext(), spanCount + 1));
            // 设置对话框宽度
            ConstraintLayout cl_root = mSiteSwitchDialog.findViewById(R.id.cl_root);
            ViewGroup.LayoutParams clp = cl_root.getLayoutParams();
            clp.width = AutoSizeUtils.mm2px(mSiteSwitchDialog.getContext(), 380 + 200 * spanCount);
            mSiteSwitchDialog.setTip("请选择首页数据源");
        }
        mSiteSwitchDialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<SourceBean>() {
            @Override
            public void click(SourceBean value, int pos) {
                dismissSiteSwitchDialog();
                previousHomeSource = ApiConfig.get().getHomeSourceBean();
                ApiConfig.get().setSourceBean(value);
                refreshHome(false);
            }
            @Override
            public String getDisplay(SourceBean val) {
                return val.getName();
            }
        }, new DiffUtil.ItemCallback<SourceBean>() {
            @Override
            public boolean areItemsTheSame(@NonNull SourceBean oldItem, @NonNull SourceBean newItem) {
                return oldItem == newItem;
            }
            @Override
            public boolean areContentsTheSame(@NonNull SourceBean oldItem, @NonNull SourceBean newItem) {
                return oldItem.getKey().equals(newItem.getKey());
            }
        }, sites, select);
        if (!mSiteSwitchDialog.isShowing())
            mSiteSwitchDialog.show();
    }

    private void refreshHome()
    {
        refreshHome(true);
    }

    private void refreshHomeSort() {
        refreshHomeRec = true;
        if (UserFragment.homeHotVodAdapter != null) {
            UserFragment.homeHotVodAdapter.setNewData(new ArrayList<Movie.Video>());
        }
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        if (home != null) {
            SourceViewModel.clearSortCache(home.getKey());
        }
        refreshHome(false);
    }

    private void updateHomeRec(AbsSortXml absXml) {
        if (!refreshHomeRec) return;
        refreshHomeRec = false;
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) != 1) return;
        // 走 UserFragment 统一渲染入口，货架与 Hero 轮播同步刷新
        UserFragment.renderRecData(absXml);
    }

    private void refreshHome(final boolean restart)
    {
        if (Thread.currentThread() != android.os.Looper.getMainLooper().getThread()) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    refreshHome(restart);
                }
            });
            return;
        }
        if (isActivityUnavailable()) {
            return;
        }
        dismissHomeDialogs();
        if (!restart) {
            loadHomeSort(true);
            return;
        }
        Intent intent = new Intent(getApplicationContext(), HomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Bundle bundle = new Bundle();
        bundle.putBoolean("useCache", true);
        intent.putExtras(bundle);
        HomeActivity.this.startActivity(intent);
    }

    private boolean isActivityUnavailable() {
        return isFinishing() || (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed());
    }

    private void dismissHomeDialogs() {
        dismissConfigErrorDialog();
        dismissSiteSwitchDialog();
    }

    private void dismissConfigErrorDialog() {
        if (mConfigErrorDialog != null) {
            if (mConfigErrorDialog.isShowing()) {
                mConfigErrorDialog.dismiss();
            }
            mConfigErrorDialog = null;
        }
    }

    private void dismissSiteSwitchDialog() {
        if (mSiteSwitchDialog != null) {
            if (mSiteSwitchDialog.isShowing()) {
                mSiteSwitchDialog.dismiss();
            }
            mSiteSwitchDialog = null;
        }
    }

    private void refreshEmpty()
    {
        skipNextUpdate=true;
        showSuccess();
        cancelHomeSortLoading();
        clearHomePages();
        List<MovieSort.SortData> initSort = DefaultConfig.adjustSort(ApiConfig.get().getHomeSourceBean().getKey(), new ArrayList<>(), true);
        appendNavTabs(initSort);
        sortAdapter.setNewData(initSort);
        initViewPager(null);
        tvName.clearAnimation();
    }

    private void cancelHomeSortLoading() {
        homeSortLoading = false;
        loadingSourceKey = null;
        tvName.clearAnimation();
        if (previousHomeSource != null) {
            ApiConfig.get().setSourceBean(previousHomeSource);
        }
        if (previousHomeName != null && !previousHomeName.isEmpty()) {
            tvName.setText(previousHomeName);
        }
        previousHomeSource = null;
        previousHomeName = null;
    }

    private void tvNameAnimation()
    {
        tvName.clearAnimation();
        AlphaAnimation blinkAnimation = new AlphaAnimation(0.0f, 1.0f);
        blinkAnimation.setDuration(500);
        blinkAnimation.setStartOffset(20);
        blinkAnimation.setRepeatMode(Animation.REVERSE);
        blinkAnimation.setRepeatCount(Animation.INFINITE);
        tvName.startAnimation(blinkAnimation);
    }
}
