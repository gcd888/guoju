package com.github.gcd888.guoju.ui.activity;

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
import android.widget.FrameLayout;
import android.widget.ImageView;
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

import com.github.gcd888.guoju.R;
import com.github.gcd888.guoju.api.ApiConfig;
import com.github.gcd888.guoju.base.BaseActivity;
import com.github.gcd888.guoju.base.BaseLazyFragment;
import com.github.gcd888.guoju.bean.AbsSortXml;
import com.github.gcd888.guoju.bean.Movie;
import com.github.gcd888.guoju.bean.MovieSort;
import com.github.gcd888.guoju.bean.SourceBean;
import com.github.gcd888.guoju.cache.RoomDataManger;
import com.github.gcd888.guoju.event.RefreshEvent;
import com.github.gcd888.guoju.server.ControlManager;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.gcd888.guoju.ui.adapter.HomePageAdapter;
import com.github.gcd888.guoju.ui.adapter.SelectDialogAdapter;
import com.github.gcd888.guoju.ui.adapter.SortAdapter;
import com.github.gcd888.guoju.ui.dialog.SelectDialog;
import com.github.gcd888.guoju.ui.dialog.TipDialog;
import com.github.gcd888.guoju.ui.fragment.CollectFragment;
import com.github.gcd888.guoju.ui.fragment.GridFragment;
import com.github.gcd888.guoju.ui.fragment.HistoryFragment;
import com.github.gcd888.guoju.ui.fragment.HomeGridPage;
import com.github.gcd888.guoju.ui.fragment.VodHomeFragment;
import com.github.gcd888.guoju.ui.fragment.home.DramaHomeFragment;
import com.github.gcd888.guoju.ui.fragment.home.HomeTabFragment;
import com.github.gcd888.guoju.ui.fragment.home.MusicHomeFragment;
import com.github.gcd888.guoju.ui.tv.focus.FocusScaleHelper;
import com.github.gcd888.guoju.ui.tv.widget.DefaultTransformer;
import com.github.gcd888.guoju.ui.tv.widget.FixedSpeedScroller;
import com.github.gcd888.guoju.ui.tv.widget.NoScrollViewPager;
import com.github.gcd888.guoju.ui.tv.widget.ViewObj;
import com.github.gcd888.guoju.util.AppManager;
import com.github.gcd888.guoju.util.DefaultConfig;
import com.github.gcd888.guoju.util.FastClickCheckUtil;
import com.github.gcd888.guoju.util.FileUtils;
import com.github.gcd888.guoju.util.HawkConfig;
import com.github.gcd888.guoju.util.LogWriter;
import com.github.gcd888.guoju.util.LOG;
import com.github.gcd888.guoju.util.MD5;
import com.github.gcd888.guoju.viewmodel.SourceViewModel;
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

    /** 主页类型常量（对应设置-主页Tab；1 直播走独立页入口）。 */
    private static final int HOME_TYPE_VOD = 0;
    private static final int HOME_TYPE_DRAMA = 2;
    private static final int HOME_TYPE_MUSIC = 3;
    /** 当前 page0 类型：跟随设置-主页Tab；导航点播/短剧/音乐切换时用 override 临时覆盖（不改设置）。 */
    private int homeType = HOME_TYPE_VOD;

    private int resolveHomeType() {
        // 导航切换的临时覆盖持久化在 Hawk：refreshHome(true) 会重启 Activity，实例字段必丢
        int override = HawkConfig.getHomeTypeOverride();
        if (override == HOME_TYPE_VOD || override == HOME_TYPE_DRAMA || override == HOME_TYPE_MUSIC) {
            return override;
        }
        int home = HawkConfig.getHomePage();
        if (home == HOME_TYPE_DRAMA) return HOME_TYPE_DRAMA;
        if (home == HOME_TYPE_MUSIC) return HOME_TYPE_MUSIC;
        return HOME_TYPE_VOD;
    }

    /** 当前 page0 是否为短剧主页（DramaHomeFragment）。 */
    private boolean isDramaHome() {
        return homeType == HOME_TYPE_DRAMA && !fragments.isEmpty()
                && fragments.get(0) instanceof DramaHomeFragment;
    }

    /** 当前 page0 是否为音乐主页（MusicHomeFragment）。 */
    private boolean isMusicHome() {
        return homeType == HOME_TYPE_MUSIC && !fragments.isEmpty()
                && fragments.get(0) instanceof MusicHomeFragment;
    }

    /** tab 双击检测：上一次点击位置与时间戳；pendingTabAction 为单击已选 tab 的延迟快捷逻辑。 */
    private int lastTabClickPos = -1;
    private long lastTabClickTime = 0;
    private long lastTabLongClickAt = 0; // tab 长按进网格的时间戳，用于吞掉长按后抬起派发的 click
    private Runnable pendingTabAction = null;
    /** 页面数据已就绪（可在两层间切换）。 */
    private volatile boolean pageDataReady = false;

    private FrameLayout topLayout;
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
            params.height = navBarHeightPx();
            topLayout.setLayoutParams(params);
            topLayout.setAlpha(1.0f);
            refreshTopInfoTextSize();
            topLayout.requestLayout();
        }
    };

    /**
     * 顶部导航栏动态高度（问题1：容器随字体自适应，不再写死 30mm）：
     * 导航项字号（ts_nav）em × 1.4 行距系数。至少保留 atv_nav_h 原值兜底。
     */
    private int navBarHeightPx() {
        TextView ref = findViewById(R.id.navVod);
        int dynamic = ref == null ? 0 : (int) (ref.getTextSize() * 1.4f);
        int fallback = getResources().getDimensionPixelSize(R.dimen.atv_nav_h);
        return Math.max(dynamic, fallback);
    }

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
        // 导航栏高度按字号动态设置（布局内 atv_nav_h 仅占位）
        ViewGroup.LayoutParams navLp = topLayout.getLayoutParams();
        navLp.height = navBarHeightPx();
        topLayout.setLayoutParams(navLp);
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
                                    view.animate().scaleX(1.05f).scaleY(1.05f).setDuration(180).start();
                                } else {
                                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(180).start();
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
                    view.animate().scaleX(1.05f).scaleY(1.05f).setDuration(180).start();
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
                // 长按进网格后抬起会派发一次 click，忽略（防御误触单击已选 tab 的延迟筛选）
                if (android.os.SystemClock.uptimeMillis() - lastTabLongClickAt < 800) {
                    return;
                }
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
                    // 单击已选 tab：延迟执行原快捷逻辑（筛选），留出双击判定窗口。
                    // 站源切换已收敛到长按 HOME 图标（navHome/tvName），单击推荐 tab 不再弹切源
                    final int pos = position;
                    mHandler.removeCallbacks(pendingTabAction);
                    pendingTabAction = new Runnable() {
                        @Override
                        public void run() {
                            BaseLazyFragment baseLazyFragment = fragments.get(pos);
                            if ((baseLazyFragment instanceof GridFragment) && !sortAdapter.getItem(pos).filters.isEmpty()) {// 弹出筛选
                                ((GridFragment) baseLazyFragment).showFilter();
                            }
                        }
                    };
                    mHandler.postDelayed(pendingTabAction, 400);
                }
            }
        });

        // 遥控器等价路径：长按确认键直接进入该 tab 的二级全屏网格
        // （双击 OK 判定窗口 400ms 对无线遥控连按不稳，且易误触单击延迟逻辑）
        // 挂在 BaseQuickAdapter 的 item 长按上（itemView 可聚焦，遥控长按 OK 会触发 itemView 长按）
        this.sortAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                if (!gridExpanded && position < fragments.size()) {
                    sortFocused = position;
                    lastTabLongClickAt = android.os.SystemClock.uptimeMillis();
                    enterGrid();
                    return true;
                }
                return false;
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

    /** 最近一次成功渲染首页时使用的分类签名，用于识别重复/迟到的 sort 回调，避免无谓的整页重建。 */
    private String lastHomeSortSignature = null;

    /** 首页分类签名：源key + 推荐模式 + 分类id/名称列表。签名未变时 sort 回调无需推倒重建首页。 */
    private String homeSortSignature(AbsSortXml absXml) {
        StringBuilder sb = new StringBuilder();
        sb.append(ApiConfig.get().getHomeSourceBean().getKey()).append('|');
        sb.append(Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC)).append('|');
        if (absXml != null && absXml.classes != null && absXml.classes.sortList != null) {
            for (MovieSort.SortData data : absXml.classes.sortList) {
                sb.append(data.id).append(',').append(data.name).append(';');
            }
        }
        return sb.toString();
    }

    /** 顶部导航：home 图标(=设置的主页)/点播/直播/短剧/音乐/搜索/配置/设置，主页对应项隐藏。
     *  Home 图标双击（400ms 内两击）= 刷新首页数据；单击仍跳主页。 */
    private long lastNavHomeClickTime = 0;

    private void initTopNav() {
        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int id = v.getId();
                if (id == R.id.navHome) {
                    // home 图标：双击（400ms 内两击）刷新首页数据；单击跳转设置的主页
                    // （navHome 不走 FastClickCheckUtil——其 500ms 冷却会吞掉第二次点击）
                    long now = System.currentTimeMillis();
                    if (now - lastNavHomeClickTime < 400) {
                        lastNavHomeClickTime = 0; // 防三击连续触发
                        Toast.makeText(getApplicationContext(), "正在刷新首页…", Toast.LENGTH_SHORT).show();
                        refreshHome(true);
                    } else {
                        lastNavHomeClickTime = now;
                        jumpToHomePage();
                    }
                    return;
                }
                FastClickCheckUtil.check(v);
                if (id == R.id.navVod) {
                    if (isDramaHome()) {
                        // 短剧主页 → 点播主页：in-place 切换（不重启 Activity，丝滑无黑屏）
                        switchToHomeType(HOME_TYPE_VOD);
                    } else {
                        backToHome();
                    }
                } else if (id == R.id.navLive) {
                    jumpActivity(LivePlayActivity.class);
                } else if (id == R.id.navDrama) {
                    enterDramaHome();
                } else if (id == R.id.navMusic) {
                    if (isMusicHome()) {
                        backToHome(); // 已在音乐主页：回到 page0 一级态
                    } else {
                        enterMusicHome();
                    }
                } else if (id == R.id.navSearch) {
                    jumpActivity(SearchActivity.class);
                } else if (id == R.id.navPush) {
                    // 配置对话框（多仓/接口配置）
                    new com.github.gcd888.guoju.ui.dialog.ConfigDialog(HomeActivity.this).show();
                } else if (id == R.id.navSetting) {
                    jumpActivity(SettingActivity.class);
                }
            }
        };
        int[] navIds = {R.id.navHome, R.id.navVod, R.id.navLive, R.id.navDrama, R.id.navMusic,
                R.id.navSearch, R.id.navPush, R.id.navSetting};
        for (int navId : navIds) {
            View nav = findViewById(navId);
            nav.setOnClickListener(listener);
            FocusScaleHelper.bind(nav);
        }
        // 站源切换入口：长按 Home 图标。必须无条件消费长按（showSiteSwitch 内部自带
        // 未就绪保护）；若返回 false，抬起时会触发单击跳转主页Tab，长按形同失效。
        // 主页为短剧时长按切短剧源，与点播长按切站点源行为一致
        findViewById(R.id.navHome).setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (isDramaHome()) {
                    DramaHomeFragment drama = fragments.get(0) instanceof DramaHomeFragment
                            ? (DramaHomeFragment) fragments.get(0) : null;
                    if (drama != null) drama.showDramaSwitch();
                } else {
                    showSiteSwitch();
                }
                return true;
            }
        });
        applyNavHomePage();
    }

    /** 按「主页」设置显隐导航项：主页自身由 home 图标代表，其余内容页依次排列。 */
    private void applyNavHomePage() {
        int home = HawkConfig.getHomePage();
        int[] pageIds = {R.id.navVod, R.id.navLive, R.id.navDrama, R.id.navMusic};
        for (int i = 0; i < pageIds.length; i++) {
            View v = findViewById(pageIds[i]);
            if (v != null) v.setVisibility(i == home ? View.GONE : View.VISIBLE);
        }
    }

    /** 跳转用户设置的「主页」页面：0=点播 1=直播 2=短剧 3=音乐（打开 app 也走这里）。 */
    private void jumpToHomePage() {
        int home = HawkConfig.getHomePage();
        if (home == 1) {
            jumpActivity(LivePlayActivity.class);
        } else if (home == 2) {
            if (isDramaHome()) {
                backToHome(); // 已在短剧主页：回到 page0 一级态
            } else {
                enterDramaHome();
            }
        } else if (home == 3) {
            if (isMusicHome()) {
                backToHome(); // 已在音乐主页：回到 page0 一级态
            } else {
                enterMusicHome();
            }
        } else {
            // 设置主页=点播：短剧主页时 in-place 切回点播（此前只回一级不切页，回不到点播）
            if (isDramaHome()) {
                switchToHomeType(HOME_TYPE_VOD);
            } else {
                backToHome();
            }
        }
    }

    /**
     * 顶部导航 in-place 切换主页类型（点播 ↔ 短剧）：不重启 Activity，
     * 直接按目标类型重建 ViewPager（点播 sort 有缓存毫秒级、短剧同步建页），
     * 无黑屏过渡/配置蒙层/状态恢复链问题。
     */
    private void switchToHomeType(int target) {
        if (homeType == target && !fragments.isEmpty()) {
            backToHome();
            return;
        }
        HawkConfig.putHomeTypeOverride(target); // 意外重建时 resolveHomeType 保持目标页
        gridExpanded = false;
        pageDataReady = false; // 切页后旧数据就绪态作废：音乐等空数据页防误入二级网格
        VodHomeFragment.hideRecGrid();
        DramaHomeFragment.hideDramaRecGrid();
        MusicHomeFragment.hideMusicRecGrid();
        homeType = target;
        lastHomeSortSignature = null; // 强制重建（否则同 signature 走 skip-rebuild 卡旧页）
        updateNavSelection();
        loadHomeSort(false);
    }

    /** 顶部导航选中态：当前主页类型对应 tab 白字高亮；Home 图标主页时白色填充、其他 tab 时白描边空心。 */
    private void updateNavSelection() {
        View navVod = findViewById(R.id.navVod);
        View navDrama = findViewById(R.id.navDrama);
        View navMusic = findViewById(R.id.navMusic);
        if (navVod != null) navVod.setSelected(homeType == HOME_TYPE_VOD);
        if (navDrama != null) navDrama.setSelected(homeType == HOME_TYPE_DRAMA);
        if (navMusic != null) navMusic.setSelected(homeType == HOME_TYPE_MUSIC);
        // Home 图标：处于用户设置的主页 → 白色填充；处于其他内容 tab → 白色描边、内部透明
        ImageView navHome = findViewById(R.id.navHome);
        if (navHome != null) {
            int homeSetting = HawkConfig.getHomePage();
            boolean atHome = (homeSetting == 0 && homeType == HOME_TYPE_VOD)
                    || (homeSetting == 2 && homeType == HOME_TYPE_DRAMA)
                    || (homeSetting == 3 && homeType == HOME_TYPE_MUSIC);
            navHome.setImageResource(atHome ? R.drawable.icon_home : R.drawable.icon_home_outline);
            navHome.setColorFilter(null); // 清掉历史染色，避免描边/填充被误染
        }
    }

    /**
     * 短剧主页入口：page0 装配 DramaHomeFragment。无论是否已有短剧源都直接进入——
     * 推荐 tab（红果热播）不依赖源配置，站点推荐/分类所需源由 fragment 内自动补载。
     * 已处于短剧主页时只回一级。
     */
    private void enterDramaHome() {
        if (isDramaHome()) {
            backToHome();
            return;
        }
        // 红果热播（推荐 tab 默认数据源）不依赖短剧源配置，无源也直接进短剧页；
        // 短剧源仅由用户配置（配置弹窗短剧 Tab / 推送自动配置），App 不内置任何源——
        // 零配置时点击影片提示先配置（DramaHomeFragment.jumpDetail 兜底）。
        LOG.i("echo-drama entry: enterDramaHome dramas=" + ApiConfig.get().getDramaBeanList().size()
                + " override kept on restart");
        switchToHomeType(HOME_TYPE_DRAMA); // in-place 切换（不重启 Activity，丝滑无黑屏）
    }

    /**
     * 音乐主页入口：page0 装配 MusicHomeFragment（与短剧入口同机制，in-place 切换）。
     * 音乐数据源暂未开放：页面骨架（Hero/货架/二级网格）已就位，历史/收藏房间可用，
     * 推荐数据接入后无需再改 Activity 管线。
     */
    private void enterMusicHome() {
        if (isMusicHome()) {
            backToHome();
            return;
        }
        LOG.i("echo-music entry: enterMusicHome override kept on restart");
        switchToHomeType(HOME_TYPE_MUSIC);
    }

    /** tab 行固定页：观看历史 / 我的收藏，固定排在「推荐」后面，站源分类 tab 依次后移。 */
    private static final String TAB_NAV_HISTORY = "nav_history";
    private static final String TAB_NAV_COLLECT = "nav_collect";

    private static void insertNavTabs(List<MovieSort.SortData> list) {
        if (list == null) {
            return;
        }
        list.add(1, new MovieSort.SortData(TAB_NAV_HISTORY, "观看历史"));
        list.add(2, new MovieSort.SortData(TAB_NAV_COLLECT, "我的收藏"));
    }

    /** 顶部 Home 图标：回到推荐首页（一级态、推荐 tab）。 */
    private void backToHome() {
        gridExpanded = false;
        // 推荐叠加层（双击展开的全屏网格）残留时收起（幂等，点播/短剧/音乐通用）
        VodHomeFragment.hideRecGrid();
        DramaHomeFragment.hideDramaRecGrid();
        MusicHomeFragment.hideMusicRecGrid();
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
                String signature = homeSortSignature(absXml);
                if (pageAdapter != null && signature.equals(lastHomeSortSignature)) {
                    // 分类/源/推荐模式均未变化：不推倒重建 ViewPager（重建会闪屏，且可能在布局空档
                    // 把 gravity=bottom 的货架画到父容器原点=屏幕左上角，表现为推荐卡卡在左上角），
                    // 仅刷新推荐数据后返回
                    updateHomeRec(absXml);
                    if (home != null && home.getName() != null && !home.getName().isEmpty()) tvName.setText(home.getName());
                    tvName.clearAnimation();
                    homeSortLoading = false;
                    loadingSourceKey = null;
                    previousHomeName = null;
                    previousHomeSource = null;
                    LogWriter.i("home sort unchanged, skip rebuild");
                    return;
                }
                lastHomeSortSignature = signature;
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
            // 打开 app 展示用户设置的「主页」页面（点播/直播/短剧/音乐）
            jumpToHomePage();
            //爬虫预热 仅首次加载
            if(!useCacheConfig)warmSearchSpidersOnce();
            // 各模块热播并行预热（短剧=红果官网；音乐等新模块注册后自动覆盖）：
            // 错峰冷启动（避开 ApiConfig/jar 预热与点播页 2700ms 豆瓣窗口），切模块时秒开
            warmRecommendOnce();
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
                            // 弹窗约定：第一参数=取消（视觉右），第二参数=确认动作（视觉左）
                            mConfigErrorDialog = new TipDialog(HomeActivity.this, msg, "取消", "重试", new TipDialog.OnListener() {
                                @Override
                                public void left() {
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
                                public void right() {
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

    /** 热播并行预热（仅一次）：延时 1s 错峰冷启动，与用户先行进入短剧页的请求经 pending 合并去重。 */
    private boolean recommendWarmStarted = false;

    private void warmRecommendOnce() {
        if (recommendWarmStarted) return;
        recommendWarmStarted = true;
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                com.github.gcd888.guoju.util.recommend.GuojuRecommend.prefetchAll();
            }
        }, 1000L);
    }

    private void loadHomeSort(boolean keepCurrentContent) {
        homeType = resolveHomeType();
        // 短剧/音乐主页：不走点播 sort 管线，直接组装对应 page0（页内自管 tab/数据）
        if (homeType == HOME_TYPE_DRAMA) {
            buildDramaPager();
            return;
        }
        if (homeType == HOME_TYPE_MUSIC) {
            buildMusicPager();
            return;
        }
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
        if (homeType == HOME_TYPE_DRAMA) {
            buildDramaPager();
            return;
        }
        if (homeType == HOME_TYPE_MUSIC) {
            buildMusicPager();
            return;
        }
        if (sortAdapter.getData().size() > 0) {
            for (MovieSort.SortData data : sortAdapter.getData()) {
                if (data.id.equals("my0")) {
                    if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1 && absXml != null && absXml.videoList != null && absXml.videoList.size() > 0) {
                        fragments.add(VodHomeFragment.newInstance(absXml.videoList));
                    } else {
                        fragments.add(VodHomeFragment.newInstance(null));
                    }
                } else if (TAB_NAV_HISTORY.equals(data.id)) {
                    fragments.add(HistoryFragment.newInstance(RoomDataManger.MEDIA_TYPE_VOD));
                } else if (TAB_NAV_COLLECT.equals(data.id)) {
                    fragments.add(CollectFragment.newInstance(RoomDataManger.MEDIA_TYPE_VOD));
                } else {
                    fragments.add(GridFragment.newInstance(data, null));
                }
            }
            pageAdapter = new HomePageAdapter(getSupportFragmentManager(), fragments);
            setupViewPagerScroller();
            mViewPager.setPageTransformer(true, new DefaultTransformer());
            mViewPager.setAdapter(pageAdapter);
            // 预实例化全部 tab 页：一级态预览需要未激活分类页可被 forceRefresh 拉数据
            // （否则远端页 rootView 未创建，无法初始化，切 tab 货架永远停在推荐内容）
            mViewPager.setOffscreenPageLimit(fragments.size());
            mViewPager.setCurrentItem(currentSelected, false);
            updateTabAnchor();
            updateNavSelection();
        }
    }

    /** ViewPager 滚动速度与翻页动画（点播/短剧主页共用）。 */
    private void setupViewPagerScroller() {
        try {
            Field field = ViewPager.class.getDeclaredField("mScroller");
            field.setAccessible(true);
            FixedSpeedScroller scroller = new FixedSpeedScroller(mContext, new AccelerateInterpolator());
            field.set(mViewPager, scroller);
            scroller.setmDuration(300);
        } catch (Exception e) {
        }
    }

    /**
     * 短剧主页组装（与点播 initViewPager 同机制）：
     * 房间 = [推荐(DramaHomeFragment), 观看历史(mediaType=2), 我的收藏(mediaType=2)]，
     * 分类房间由 DramaHomeFragment sortResult 回调经 appendDramaCategory 追加。
     */
    private void buildDramaPager() {
        // 解除 initData 期间 showLoading 的蒙层：短剧链路不走点播 sortResult 管线
        // （那里的 showSuccess 不会被触发），不解除会一直停在"正在加载中"
        showSuccess();
        clearHomePages();
        homeType = resolveHomeType();
        if (homeType != HOME_TYPE_DRAMA) {
            return; // override 已切回点播：交还点播管线
        }
        List<MovieSort.SortData> tabs = new ArrayList<>();
        tabs.add(new MovieSort.SortData("my0", "推荐"));
        tabs.add(new MovieSort.SortData(TAB_NAV_HISTORY, "观看历史"));
        tabs.add(new MovieSort.SortData(TAB_NAV_COLLECT, "我的收藏"));
        sortAdapter.setNewData(tabs);
        fragments.add(new DramaHomeFragment());
        fragments.add(HistoryFragment.newInstance(RoomDataManger.MEDIA_TYPE_DRAMA));
        fragments.add(CollectFragment.newInstance(RoomDataManger.MEDIA_TYPE_DRAMA));
        pageAdapter = new HomePageAdapter(getSupportFragmentManager(), fragments);
        setupViewPagerScroller();
        mViewPager.setPageTransformer(true, new DefaultTransformer());
        mViewPager.setAdapter(pageAdapter);
        // 预实例化全部 tab 页：一级态预览需要未激活分类页可被 forceRefresh 拉数据（与点播一致）
        mViewPager.setOffscreenPageLimit(fragments.size());
        currentSelected = 0;
        mViewPager.setCurrentItem(0, false);
        updateTabAnchor();
        updateNavSelection();
    }

    /**
     * 音乐主页组装（与 buildDramaPager 同机制）：
     * 房间 = [推荐(MusicHomeFragment), 观看历史(mediaType=3), 我的收藏(mediaType=3)]。
     * 音乐数据源暂未开放：推荐页为空白骨架，历史/收藏房间照常可用。
     */
    private void buildMusicPager() {
        // 解除 initData 期间 showLoading 的蒙层：音乐链路不走点播 sortResult 管线
        showSuccess();
        clearHomePages();
        homeType = resolveHomeType();
        if (homeType != HOME_TYPE_MUSIC) {
            return; // override 已切走：交还对应管线
        }
        List<MovieSort.SortData> tabs = new ArrayList<>();
        tabs.add(new MovieSort.SortData("my0", "推荐"));
        tabs.add(new MovieSort.SortData(TAB_NAV_HISTORY, "观看历史"));
        tabs.add(new MovieSort.SortData(TAB_NAV_COLLECT, "我的收藏"));
        sortAdapter.setNewData(tabs);
        fragments.add(new MusicHomeFragment());
        fragments.add(HistoryFragment.newInstance(RoomDataManger.MEDIA_TYPE_MUSIC));
        fragments.add(CollectFragment.newInstance(RoomDataManger.MEDIA_TYPE_MUSIC));
        pageAdapter = new HomePageAdapter(getSupportFragmentManager(), fragments);
        setupViewPagerScroller();
        mViewPager.setPageTransformer(true, new DefaultTransformer());
        mViewPager.setAdapter(pageAdapter);
        mViewPager.setOffscreenPageLimit(fragments.size());
        currentSelected = 0;
        mViewPager.setCurrentItem(0, false);
        updateTabAnchor();
        updateNavSelection();
    }

    /**
     * 短剧分类到达：追加 tab 行 + 分类房间（GridFragment 按 dramaKey 加载，
     * 与点播分类房间同机制，仅数据源不同）。重复回调时先清旧分类重建。
     */
    public static void appendDramaCategory(List<MovieSort.SortData> categories, String dramaKey) {
        final HomeActivity act = instance;
        if (act == null || act.homeType != HOME_TYPE_DRAMA) {
            return;
        }
        act.mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (act.homeType != HOME_TYPE_DRAMA || act.sortAdapter == null) {
                    return;
                }
                // 重建 tab 数据：固定三导航 tab + 最新分类（fragments 同步重建）
                List<MovieSort.SortData> tabs = new ArrayList<>();
                tabs.add(new MovieSort.SortData("my0", "推荐"));
                tabs.add(new MovieSort.SortData(TAB_NAV_HISTORY, "观看历史"));
                tabs.add(new MovieSort.SortData(TAB_NAV_COLLECT, "我的收藏"));
                if (categories != null) tabs.addAll(categories);
                act.sortAdapter.setNewData(tabs);
                // fragments：保留前三个房间（推荐/历史/收藏），追加分类房间
                while (act.fragments.size() > 3) {
                    act.fragments.remove(act.fragments.size() - 1);
                }
                if (categories != null) {
                    for (MovieSort.SortData data : categories) {
                        act.fragments.add(GridFragment.newInstance(data, dramaKey));
                    }
                }
                if (act.pageAdapter != null) {
                    act.pageAdapter.notifyDataSetChanged();
                    act.mViewPager.setOffscreenPageLimit(act.fragments.size());
                }
                LogWriter.i("echo-drama page: append categories=" + (categories == null ? 0 : categories.size()));
            }
        });
    }

    /** 短剧切源后缓存重启（与点播切站源后 refreshHome 同机制）。 */
    public static void refreshDramaHome() {
        final HomeActivity act = instance;
        if (act != null) {
            act.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    act.refreshHome(true);
                }
            });
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
        insertNavTabs(newSortData);
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
            VodHomeFragment.homeHotVodAdapter.notifyDataSetChanged();
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
        } else if (baseLazyFragment instanceof VodHomeFragment
                || baseLazyFragment instanceof DramaHomeFragment) {
            // 推荐全屏网格（双击展开）先回一级，再谈退出（点播/短剧推荐页同规则）
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
        // 设置页可能改了「主页」或点播/短剧主页类型：回来时同步导航显隐与 page0
        applyNavHomePage();
        int settingHome = HawkConfig.getHomePage();
        boolean settingIsDrama = settingHome == HOME_TYPE_DRAMA;
        if (HawkConfig.getHomeTypeOverride() < 0 && !fragments.isEmpty() && settingIsDrama != isDramaHome()) {
            // 无导航临时覆盖时，跟随最新设置切换主页类型（缓存重启）
            refreshHome(true);
        }
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
        } else if (event.type == RefreshEvent.TYPE_HOME_REC_STYLE_CHANGE) {
            // 首页多行开关：主页货架/Hero 即时重排（设置关闭后即见效果，无需切 tab）
            HomeTabFragment.notifyRecStyleChanged();
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
            if (homeType == HOME_TYPE_MUSIC) {
                return; // 音乐页暂无预览管线（货架静态数据为空，数据源接入后按短剧同机制补）
            }
            boolean dramaHome = homeType == HOME_TYPE_DRAMA;
            // 一级态 tab 预览：只换首页一行货架，不切页不进二级
            if (!gridExpanded) {
                if (sortFocused == 0) {
                    // 回到推荐 tab：恢复首页货架（点播=豆瓣热播/站点推荐；短剧=红果热播/站点推荐）
                    if (dramaHome) {
                        DramaHomeFragment.restoreDramaShelf();
                    } else {
                        VodHomeFragment.restoreHomeShelf();
                    }
                    return;
                }
                if (sortFocused < fragments.size()
                        && fragments.get(sortFocused) instanceof HomeGridPage) {
                    HomeGridPage page = (HomeGridPage) fragments.get(sortFocused);
                    if (page.isLoad()) {
                        if (dramaHome) {
                            DramaHomeFragment.showDramaShelfPreview(page.getGridData());
                        } else {
                            VodHomeFragment.showSortPreview(page.getGridData());
                        }
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
                if (dramaHome) {
                    DramaHomeFragment.showDramaRecGrid();
                } else {
                    VodHomeFragment.showRecGrid();
                }
            } else {
                DramaHomeFragment.hideDramaRecGrid();
                VodHomeFragment.hideRecGrid();
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
                    // 按主页类型分流：短剧页异步数据到达必须通知短剧货架（此前写死点播，
                    // 短剧页首次切分类 tab 后货架不刷新，需反复切换才变）
                    List<Movie.Video> previewData = ((HomeGridPage) act.fragments.get(act.sortFocused)).getGridData();
                    if (act.homeType == HOME_TYPE_DRAMA) {
                        DramaHomeFragment.showDramaShelfPreview(previewData);
                    } else {
                        VodHomeFragment.showSortPreview(previewData);
                    }
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
                if (act.sortFocused == 0) {
                    // 推荐 tab 无独立网格页：展开首页推荐全屏网格叠加层（不抢焦点）
                    if (act.homeType == HOME_TYPE_MUSIC) {
                        // 音乐页暂无推荐数据：不展开叠加层
                    } else if (act.homeType == HOME_TYPE_DRAMA) {
                        DramaHomeFragment.showDramaRecGrid();
                    } else {
                        VodHomeFragment.showRecGrid();
                    }
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
        } else if (f instanceof VodHomeFragment) {
            VodHomeFragment.focusRecGrid();
        } else if (f instanceof DramaHomeFragment) {
            DramaHomeFragment.focusDramaRecGrid();
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
                VodHomeFragment.hideRecGrid(); // 推荐全屏网格叠加层收起（幂等）
                DramaHomeFragment.hideDramaRecGrid(); // 短剧推荐叠加层收起（幂等）
                MusicHomeFragment.hideMusicRecGrid(); // 音乐推荐叠加层收起（幂等）
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
                    jumpActivity(SettingActivity.class);
                } else {
                    // MENU 短按 = Home 图标长按效果：站源切换（点播切站源/短剧切短剧源）
                    if (isDramaHome()) {
                        DramaHomeFragment drama = fragments.size() > 0
                                && fragments.get(0) instanceof DramaHomeFragment
                                ? (DramaHomeFragment) fragments.get(0) : null;
                        if (drama != null) drama.showDramaSwitch();
                    } else {
                        showSiteSwitch();
                    }
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
                            navBarHeightPx(),
                            AutoSizeUtils.mm2px(this.mContext, 1.0f)),
                    ObjectAnimator.ofFloat(this.topLayout, "alpha", 1.0f, 0.0f));
            animatorSet.setDuration(240);
            animatorSet.setInterpolator(new android.view.animation.AccelerateInterpolator());
            animatorSet.start();
            return;
        }
        if (!hide && topHide == 1) {
            animatorSet.playTogether(ObjectAnimator.ofObject(viewObj, "marginTop", new IntEvaluator(),
                            AutoSizeUtils.mm2px(this.mContext, 0.0f),
                            0),
                    ObjectAnimator.ofObject(viewObj, "height", new IntEvaluator(),
                            AutoSizeUtils.mm2px(this.mContext, 1.0f),
                            navBarHeightPx()),
                    ObjectAnimator.ofFloat(this.topLayout, "alpha", 0.0f, 1.0f));
            animatorSet.setDuration(240);
            animatorSet.setInterpolator(new android.view.animation.DecelerateInterpolator());
            animatorSet.start();
        }
    }

    /** 一级态 tab 贴底锚点（tab 底边贴货架顶边）；货架未测量前 -1，tab 保持布局初始值不动。 */
    private float homeTabBottomAnchor = -1f;

    /** 底部 tab 行顶边窗口坐标（Hero 简介动态排版的底线基准）；未就绪返回 -1。 */
    public static float getHomeTabTopPx() {
        final HomeActivity act = instance;
        if (act == null || act.mGridView == null || act.mGridView.getHeight() <= 0) {
            return -1f;
        }
        int[] loc = new int[2];
        act.mGridView.getLocationOnScreen(loc);
        return loc[1];
    }

    /** tab 行锚点：二级态吸顶对齐导航栏高度；一级态贴货架顶边（锚点未测得时不强设，保持布局初始值）。 */
    private void updateTabAnchor() {
        if (mGridView == null) {
            return;
        }
        float target;
        if (gridExpanded) {
            // 吸顶位置 = 导航栏实际高度（动态字号折算，与 topLayout 等高对齐）
            target = navBarHeightPx();
        } else if (homeTabBottomAnchor >= 0) {
            target = homeTabBottomAnchor;
        } else {
            return;
        }
        mGridView.animate().translationY(target).setDuration(240)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    /**
     * 一级态 tab 行动态贴底：tab 底边贴住货架顶边（同窗口坐标换算，适配不同屏高），
     * 替代固定 300mm——货架 tvHotList 贴屏幕底，屏高不同时固定值会留出大段空隙。
     * 由货架布局变化（VodHomeFragment 的 OnLayoutChangeListener）驱动。
     */
    public static void alignTabToShelf(View shelf) {
        HomeActivity act = instance;
        if (act == null || act.mGridView == null || shelf == null || act.gridExpanded) {
            return;
        }
        if (shelf.getVisibility() != View.VISIBLE || shelf.getHeight() <= 0
                || act.mGridView.getHeight() <= 0) {
            return;
        }
        int[] shelfLoc = new int[2];
        shelf.getLocationOnScreen(shelfLoc);
        int[] gridLoc = new int[2];
        act.mGridView.getLocationOnScreen(gridLoc);
        float parentTop = gridLoc[1] - act.mGridView.getTranslationY(); // mGridView 在窗口内的布局 top
        float target = shelfLoc[1]
                - act.mContext.getResources().getDimensionPixelSize(R.dimen.vs_10)
                - act.mGridView.getHeight() - parentTop;
        act.homeTabBottomAnchor = target;
        if (Math.abs(act.mGridView.getTranslationY() - target) > 2f) {
            act.mGridView.animate().translationY(target).setDuration(240)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
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
        if (VodHomeFragment.homeHotVodAdapter != null) {
            VodHomeFragment.homeHotVodAdapter.setNewData(new ArrayList<Movie.Video>());
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
        // 走 VodHomeFragment 统一渲染入口，货架与 Hero 轮播同步刷新
        VodHomeFragment.renderRecData(absXml);
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
        insertNavTabs(initSort);
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
