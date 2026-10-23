package com.github.gcd888.guoju.ui.fragment;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.gcd888.guoju.R;
import com.github.gcd888.guoju.api.ApiConfig;
import com.github.gcd888.guoju.base.BaseLazyFragment;
import com.github.gcd888.guoju.bean.Movie;
import com.github.gcd888.guoju.bean.VodInfo;
import com.github.gcd888.guoju.cache.RoomDataManger;
import com.github.gcd888.guoju.ui.fragment.home.HomeTabFragment;
import com.github.gcd888.guoju.event.ServerEvent;
import com.github.gcd888.guoju.ui.activity.DetailActivity;
import com.github.gcd888.guoju.ui.activity.FastSearchActivity;
import com.github.gcd888.guoju.ui.activity.HomeActivity;
import com.github.gcd888.guoju.ui.activity.SearchActivity;
import com.github.gcd888.guoju.ui.adapter.HomeHotVodAdapter;
import com.github.gcd888.guoju.ui.widget.HeroBannerView;
import com.github.gcd888.guoju.util.DoubanPosterFetcher;
import com.github.gcd888.guoju.util.HawkConfig;
import com.github.gcd888.guoju.util.ImgUtil;
import com.github.gcd888.guoju.util.LogWriter;
import com.github.gcd888.guoju.util.UA;
import com.github.gcd888.guoju.viewmodel.SourceViewModel;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * @author pj567
 * @date :2021/3/9
 * @description: Apple TV 风格首页推荐页：Hero 轮播 + 推荐货架。
 * 功能入口（历史/直播/搜索/推送/收藏/设置）已上移至 HomeActivity 顶部导航。
 * 继承 HomeTabFragment（内容主页基类）：mediaType=点播，Hero 动态排版与短剧主页共用。
 */
public class VodHomeFragment extends HomeTabFragment {
    @Override
    public int getMediaType() {
        return RoomDataManger.MEDIA_TYPE_VOD;
    }

    public static HomeHotVodAdapter homeHotVodAdapter;
    public static View userScroll;
    private List<Movie.Video> homeSourceRec;
    public static TvRecyclerView tvHotList;
    /** 推荐 tab 二级全屏网格（双击推荐展开）：多行多列显示全部推荐资源。 */
    private TvRecyclerView gridOverlay;
    private HomeHotVodAdapter gridOverlayAdapter;
    private HeroBannerView heroBanner;
    private SourceViewModel sourceViewModel;
    private ImgUtil.Style style;
    private String tvRateValue = "";

    /** 静态实例，供 HomeActivity 站点推荐回调同步 Hero。 */
    private static VodHomeFragment instance;

    /** Hero 底图数据上限：自然切换热播前 3。 */
    private static final int HERO_MAX_COUNT = 3;

    /** 缓存键版本前缀：数据链路/参数变更时+1，立即作废旧版缓存。 */
    private static final String CACHE_VER = "15";

    /** 日志 TAG：豆瓣请求/缓存/解析全链路。 */
    private static final String TAG = "GuoJuHome";

    /** rexxar 移动端 UA：m.douban.com 详情接口要求移动端 UA + Referer。 */
    private static final String REXXAR_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    /** Hero 内容：电影/电视剧/综艺三个分类近期热播第一。 */
    private static final String[] HERO_TAGS = {"电影", "电视剧", "综艺"};

    /** Hero 数据（按 HERO_TAGS 顺序，槽位可能为 null），ext 为 [meta, desc]。 */
    private final List<Movie.Video> heroItems = new ArrayList<>();
    private final List<String[]> heroExtras = new ArrayList<>();

    /** 与最终渲染列表对齐的详情（过滤空槽后），供 heroBinder 按 position 取用。 */
    private final List<String[]> heroRenderExtras = new ArrayList<>();

    /** 已被海报/剧照链升级过底图的条目（对象引用比对）；未升级条目 Hero 显示内置壁纸。 */
    private final java.util.Set<Object> heroUpgraded =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** 首页货架自身数据快照（豆瓣热播/站点推荐）：tab 预览切走后回到推荐可恢复。 */
    private static List<Movie.Video> lastHomeShelf = null;

    /** 货架是否正预览其他 tab（历史/收藏/分类）：预览期间首页数据只落盘不渲染，防止晚到响应覆盖预览。 */
    private static boolean shelfPreviewActive = false;

    private final HeroBannerView.Binder<Movie.Video> heroBinder = new HeroBannerView.Binder<Movie.Video>() {
        @NonNull
        @Override
        public View onCreateView(@NonNull Context context, @NonNull ViewGroup container) {
            return LayoutInflater.from(context).inflate(R.layout.view_hero_item, container, false);
        }

        @Nullable
        @Override
        public String coverUrl(@NonNull Movie.Video item) {
            if (!heroUpgraded.contains(item)) return null; // 未升级条目用内置壁纸，无需预加载
            String pic = item.pic == null ? "" : item.pic.trim();
            // base64 内联图无需网络预加载
            return TextUtils.isEmpty(pic) || ImgUtil.isBase64Image(pic) ? null : pic;
        }

        @Override
        public void onBind(@NonNull View itemView, Movie.Video item, int position) {
            ImageView ivHero = itemView.findViewById(R.id.ivHero);
            TextView tvTitle = itemView.findViewById(R.id.tvHeroTitle);
            TextView tvMeta = itemView.findViewById(R.id.tvHeroMeta);
            TextView tvDesc = itemView.findViewById(R.id.tvHeroDesc);
            String pic = item.pic == null ? "" : item.pic.trim();
            if (!heroUpgraded.contains(item)) {
                // 未升级条目（海报/剧照链未命中）：内置苹果风格壁纸铺底，文字照常立即加载
                ivHero.setImageResource(R.drawable.hero_default_bg);
            } else if (ImgUtil.isBase64Image(pic)) {
                ivHero.setImageBitmap(ImgUtil.decodeBase64ToBitmap(pic));
            } else if (!TextUtils.isEmpty(pic)) {
                // 铺满裁切：等比缩放铺满全屏，垂直从顶部向下截取（TopCenterCrop，保住画面上部片名）
                android.util.DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
                ImgUtil.loadHero(pic, ivHero, dm.widthPixels, dm.heightPixels, item.name);
            } else {
                ivHero.setImageDrawable(ImgUtil.createTextDrawable(item.name));
            }
            tvTitle.setText(item.name);
            // 豆瓣模式带补全详情（meta=年份·题材类型·影视类型·评分，desc=剧情简介）
            String[] ext = position >= 0 && position < heroRenderExtras.size() && heroRenderExtras.get(position) != null
                    ? heroRenderExtras.get(position) : null;
            String meta = ext != null ? ext[0] : "";
            String desc = ext != null ? ext[1] : "";
            // 兜底：详情接口全挂时评分挪到 meta 行（note 可能已带"分"字，避免双写）
            if (meta.isEmpty() && item.note != null && !item.note.isEmpty() && !"0".equals(item.note)) {
                meta = item.note.endsWith("分") ? item.note : item.note + " 分";
            }
            if (!meta.isEmpty()) {
                tvMeta.setText(meta);
                tvMeta.setVisibility(View.VISIBLE);
            } else {
                tvMeta.setVisibility(View.GONE);
            }
            if (!desc.isEmpty()) {
                // 简介宽度=真实屏幕一半减去左侧安全边距（文字从边距起排，右端正好不超过屏幕中线；
                // AutoSize 改写了 Resources 指标，须 getRealMetrics 取物理宽）
                // 简介宽度=屏幕 2/3（从文字起点到屏 2/3 处）；AutoSize 改写了 Resources 指标，
                // 须 getRealMetrics 取物理宽
                try {
                    android.util.DisplayMetrics dm2 = new android.util.DisplayMetrics();
                    mActivity.getWindowManager().getDefaultDisplay().getRealMetrics(dm2);
                    ViewGroup parent = (ViewGroup) tvDesc.getParent();
                    int side = parent == null ? 0 : parent.getPaddingLeft();
                    tvDesc.setWidth(dm2.widthPixels * 2 / 3 - side);
                } catch (Throwable ignored) {
                }
                tvDesc.setText(desc);
                tvDesc.setVisibility(View.VISIBLE);
            } else {
                // 无简介（rexxar 无 intro / 降级接口无短评 / 详情接口全挂）：占位显示，不隐藏
                tvDesc.setText("简介：暂无");
                tvDesc.setVisibility(View.VISIBLE);
            }
            // 动态排版：信息区顶距随导航栏实际高度（0.86 版间距基线）、
            // 简介行数随底部 tab 行实际位置（不再限 2 行，底部留 20mm）
            View infoBox = (View) tvTitle.getParent();
            applyHeroLayout(infoBox, tvTitle, tvMeta, tvDesc, com.github.gcd888.guoju.ui.activity.HomeActivity.getHomeTabTopPx());
            LogWriter.i("hero bind[" + position + "] " + item.name + " meta=[" + meta + "] descLen=" + desc.length());
            // 片名获/失焦：同步轮播暂停态（焦点停在片名上轮播停住，移出 Hero 恢复）+ 缩放表达焦点
            tvTitle.setOnFocusChangeListener((v, hasFocus) -> { heroBanner.refreshFocusPause(); com.github.gcd888.guoju.util.HeroInfoLayout.applyTitleFocus(v, hasFocus); });
            // 仅片名可点击（遥控 OK 键）：跳转搜索页并携带剧名自动搜索；整图不再响应点击
            tvTitle.setOnClickListener(v -> jumpSearch(item));
        }
    };

    public static VodHomeFragment newInstance() {
        return new VodHomeFragment();
    }

    public static VodHomeFragment newInstance(List<Movie.Video> recVod) {
        return new VodHomeFragment().setArguments(recVod);
    }

    public VodHomeFragment setArguments(List<Movie.Video> recVod) {
        this.homeSourceRec = recVod;
        return this;
    }

    @Override
    protected void onFragmentResume() {
        super.onFragmentResume();
        // 货架布局（单行/多行）统一切到基类 applyShelfLayout：多行=V7GridLayoutManager+定高两行，
        // 网格内部滚动，不再撑高顶走 tab 行/盖 Hero
        int spanCount = 5;
        if (Hawk.get(HawkConfig.HOME_REC_STYLE, false)
                && style != null && Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1) {
            spanCount = ImgUtil.spanCountByStyle(style, spanCount);
        }
        applyShelfLayout(tvHotList, spanCount);
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 2) {
            List<VodInfo> allVodRecord = RoomDataManger.getAllVodRecord(20, RoomDataManger.MEDIA_TYPE_VOD);
            List<Movie.Video> vodList = new ArrayList<>();
            for (VodInfo vodInfo : allVodRecord) {
                Movie.Video vod = new Movie.Video();
                vod.id = vodInfo.id;
                vod.sourceKey = vodInfo.sourceKey;
                vod.name = vodInfo.name;
                vod.pic = vodInfo.pic;
                if (vodInfo.playNote != null && !vodInfo.playNote.isEmpty())
                    vod.note = "上次看到" + vodInfo.playNote;
                vodList.add(vod);
            }
            renderHomeList(vodList);
        }
    }

    /** 首页多行开关变化（设置页切换后广播）：货架即时重排 + Hero 文字区重绑（显隐/排版重算）。 */
    @Override
    protected void onRecStyleChanged() {
        int spanCount = 5;
        if (Hawk.get(HawkConfig.HOME_REC_STYLE, false)
                && style != null && Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1) {
            spanCount = ImgUtil.spanCountByStyle(style, spanCount);
        }
        applyShelfLayout(tvHotList, spanCount);
        com.github.gcd888.guoju.ui.activity.HomeActivity.alignTabToShelf(tvHotList);
        if (heroBanner != null) heroBanner.rebindCurrent();
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_user;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TvRecyclerView hotList = view.findViewById(R.id.tvHotList);
        if (hotList != null && hotList.getLayoutManager() == null) {
            hotList.setLayoutManager(new V7LinearLayoutManager(mContext, V7LinearLayoutManager.HORIZONTAL, false));
        }
    }

    private void jumpSearch(Movie.Video vod) {
        Intent newIntent;
        if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
            newIntent = new Intent(mContext, FastSearchActivity.class);
        } else {
            newIntent = new Intent(mContext, SearchActivity.class);
        }
        newIntent.putExtra("title", vod.name);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mActivity.startActivity(newIntent);
    }

    @Override
    protected void init() {
        instance = this;
        EventBus.getDefault().register(this);
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        shelfPreviewActive = false; // 防御：跨 Activity 重建的 static 残留（销毁于预览态）
        heroBanner = findViewById(R.id.heroBanner);
        userScroll = findViewById(R.id.userScroll);
        tvHotList = findViewById(R.id.tvHotList);
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1 && homeSourceRec != null) {
            style = ImgUtil.initStyle();
        }
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1 && homeSourceRec != null) {
            tvRateValue = "站点推荐";
        } else {
            tvRateValue = "果剧推荐";
        }
        homeHotVodAdapter = new HomeHotVodAdapter(style, tvRateValue);
        homeHotVodAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                handleVodClick((Movie.Video) adapter.getItem(position), position, homeHotVodAdapter);
            }
        });

        homeHotVodAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @android.annotation.SuppressLint("NotifyDataSetChanged")
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                return handleVodLongClick((Movie.Video) adapter.getItem(position));
            }
        });

        tvHotList.setAdapter(homeHotVodAdapter);
        // 货架高度/位置变化（数据渲染、预览切换）→ tab 行贴底重算（tab 底边贴货架顶边）
        tvHotList.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or2, int ob) {
                if (homeHotVodAdapter != null && !homeHotVodAdapter.getData().isEmpty()) {
                    HomeActivity.alignTabToShelf(tvHotList);
                }
            }
        });

        // 推荐 tab 二级全屏网格：双击展开，复用货架卡片，多行多列
        gridOverlay = findViewById(R.id.gridOverlay);
        gridOverlayAdapter = new HomeHotVodAdapter(style, tvRateValue);
        gridOverlayAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                handleVodClick((Movie.Video) adapter.getItem(position), position, gridOverlayAdapter);
            }
        });
        gridOverlayAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                return handleVodLongClick((Movie.Video) adapter.getItem(position));
            }
        });
        V7GridLayoutManager overlayLayout = new V7GridLayoutManager(mContext, 7);
        gridOverlay.setLayoutManager(overlayLayout);
        gridOverlay.setAdapter(gridOverlayAdapter);

        // 手机端展开全屏网格已改为「双击 tab 文字」（见 HomeActivity 双击检测），不再用手势上滑

        // 列表按下边界（TV 下键）进入当前分类全屏网格；手机双击 tab 展开
        tvHotList.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View focused) {
                if (direction == View.FOCUS_DOWN) {
                    HomeActivity.enterGrid();
                    return true;
                }
                return false;
            }
        });

        // 无"查看全部"尾卡：电视下键 / 手机双击 tab 展开全屏网格（HomeActivity.enterGrid）

        initHomeHotVod();
        sourceViewModel.actionResult.observe(this, new Observer<JSONObject>() {
            @Override
            public void onChanged(JSONObject jsonObject) {
                if (jsonObject == null) return;
                String msg = jsonObject.optString("msg");
                if (!msg.isEmpty()) Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 货架/全屏网格点击统一处理（两个适配器共用）。 */
    private void handleVodClick(Movie.Video vod, int position, HomeHotVodAdapter adapter) {
        if (ApiConfig.get().getSourceBeanList().isEmpty()) {
            Toast.makeText(mContext, "请先在设置中配置接口", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1 && homeSourceRec != null && vod.action != null) {
            sourceViewModel.action(vod.sourceKey, vod.action);
            return;
        }
        if ((vod.id != null && !vod.id.isEmpty()) && (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 2) && HawkConfig.hotVodDelete) {
            adapter.remove(position);
            VodInfo vodInfo = RoomDataManger.getVodInfo(vod.sourceKey, vod.id);
            assert vodInfo != null;
            RoomDataManger.deleteVodRecord(vod.sourceKey, vodInfo);
            Toast.makeText(mContext, "已删除当前记录", Toast.LENGTH_SHORT).show();
        } else if (vod.id != null && vod.id.startsWith("msearch:")) {
            jumpSearch(vod);
        } else if (vod.sourceKey == null || vod.sourceKey.isEmpty()) {
            // 豆瓣/片单条目：无站源详情，快速搜索片名选站源进详情
            jumpSearch(vod);
        } else {
            Bundle bundle = new Bundle();
            bundle.putString("id", vod.id);
            bundle.putString("sourceKey", vod.sourceKey);
            bundle.putString("title", vod.name);
            bundle.putString("picture", vod.pic);
            jumpActivity(DetailActivity.class, bundle);
        }
    }

    /** 货架/全屏网格长按统一处理（删除模式/快速搜索）。 */
    private boolean handleVodLongClick(Movie.Video vod) {
        if (ApiConfig.get().getSourceBeanList().isEmpty()) return false;
        assert vod != null;
        if (vod.id != null && vod.id.startsWith("msearch:")) {
            Bundle bundle = new Bundle();
            bundle.putString("id", vod.id);
            bundle.putString("sourceKey", vod.sourceKey);
            bundle.putString("title", vod.name);
            bundle.putString("picture", vod.pic);
            jumpActivity(DetailActivity.class, bundle);
        } else if ((vod.id != null && !vod.id.isEmpty()) && (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 2)) {
            HawkConfig.hotVodDelete = !HawkConfig.hotVodDelete;
            homeHotVodAdapter.notifyDataSetChanged();
        } else {
            Bundle bundle = new Bundle();
            bundle.putString("title", vod.name);
            jumpActivity(FastSearchActivity.class, bundle);
        }
        return true;
    }

    /** 双击推荐 tab：展开推荐全屏网格（数据=货架快照，多行多列）。 */
    public static void showRecGrid() {
        if (instance == null || instance.gridOverlay == null || instance.gridOverlayAdapter == null) {
            return;
        }
        List<Movie.Video> data = lastHomeShelf == null ? new ArrayList<>() : lastHomeShelf;
        instance.gridOverlayAdapter.setNewData(new ArrayList<>(data));
        instance.gridOverlay.setVisibility(View.VISIBLE);
        // 二级态物理隐藏货架：彻底杜绝任何切页状态异常下货架卡残留在推荐页上
        if (tvHotList != null) {
            tvHotList.setVisibility(View.GONE);
        }
        // 不自动获焦：二级页焦点停在 tab 行，下键走 HomeActivity.focusRecGrid 进网格
        LogWriter.i("rec grid show size=" + data.size());
    }

    /** 二级态：tab 行下键，推荐网格叠加层获焦。 */
    public static void focusRecGrid() {
        if (instance == null || instance.gridOverlay == null) {
            return;
        }
        instance.gridOverlay.post(new Runnable() {
            @Override
            public void run() {
                if (instance != null && instance.gridOverlay != null) {
                    instance.gridOverlay.requestFocus();
                }
            }
        });
    }

    /** 回一级：收起推荐全屏网格（幂等）。 */
    public static void hideRecGrid() {
        if (instance == null || instance.gridOverlay == null) {
            return;
        }
        if (instance.gridOverlay.getVisibility() == View.VISIBLE) {
            instance.gridOverlay.setVisibility(View.GONE);
            LogWriter.i("rec grid hide");
        }
        // 恢复货架可见性（与 showRecGrid 的 GONE 配对；无条件设置保证幂等）
        if (tvHotList != null) {
            tvHotList.setVisibility(View.VISIBLE);
        }
    }

    /** 统一渲染入口：刷新货架并同步 Hero 轮播（历史模式不显示 Hero）。 */
    private void renderHomeList(List<Movie.Video> list) {
        lastHomeShelf = list == null ? null : new ArrayList<>(list);
        if (shelfPreviewActive) {
            // 正在预览历史/收藏/分类货架：首页数据仅落盘，回"推荐"时经 restoreHomeShelf 恢复
            LogWriter.i("shelf home buffered size=" + (list == null ? 0 : list.size()));
        } else {
            homeHotVodAdapter.setNewData(list);
            safeShelfLayout();
            LogWriter.i("shelf render home size=" + (list == null ? 0 : list.size()));
        }
        HomeActivity.notifyHomeDataReady();
        int recMode = Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC);
        if (recMode == 2) {
            heroBanner.setVisibility(View.GONE);
            heroBanner.setAutoScrollEnabled(false);
            return;
        }
        // 豆瓣模式（recMode=0，或 recMode=1 但尚未导入站源）：Hero 由 loadHeroData
        // 三分类独立渲染，货架数据不得覆盖（否则轮播变混排货架、详情被清空）
        if (recMode == 0 || (recMode == 1 && homeSourceRec == null)) {
            return;
        }
        if (list == null || list.isEmpty()) {
            heroBanner.setVisibility(View.GONE);
            heroBanner.setAutoScrollEnabled(false);
            return;
        }
        heroExtras.clear();
        heroRenderExtras.clear();
        heroBanner.setVisibility(View.VISIBLE);
        final ArrayList<Movie.Video> heroList = new ArrayList<>(
                list.subList(0, Math.min(list.size(), HERO_MAX_COUNT)));
        // 文字详情按位回填（suggest→rexxar 详情），先铺空槽位；豆瓣对不上的保持 null → 渲染层显示"简介：暂无"
        for (int i = 0; i < heroList.size(); i++) heroRenderExtras.add(null);
        heroBanner.setData(heroList, heroBinder);
        // 站源条目只有剧名（无豆瓣 id）：先按剧名 suggest 搜豆瓣拿 id，再拉横版剧照替换竖版海报；
        // 全部完成后若有变更才刷新轮播，拿不到横图保持站源原图
        heroStillTarget = heroList;
        final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger(heroList.size());
        final java.util.concurrent.atomic.AtomicInteger changed = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger textChanged = new java.util.concurrent.atomic.AtomicInteger(0);
        android.os.Handler stagger = new android.os.Handler(android.os.Looper.getMainLooper());
        for (int i = 0; i < heroList.size(); i++) {
            final Movie.Video vod = heroList.get(i);
            final int idx = i;
            final int delay = i * 900;
            stagger.postDelayed(() -> {
                if (vod == null || mActivity == null) return;
                fetchSuggestThenStill(vod, vod.pic, idx, pending, changed, textChanged);
            }, delay);
        }
    }

    /** HomeActivity 站点推荐数据回调：同时刷新货架与 Hero 轮播。 */
    public static void renderRecData(com.github.gcd888.guoju.bean.AbsSortXml absXml) {
        List<com.github.gcd888.guoju.bean.Movie.Video> list = (absXml == null || absXml.videoList == null)
                ? new ArrayList<>() : absXml.videoList;
        if (instance != null) {
            instance.renderHomeList(list);
        } else if (homeHotVodAdapter != null) {
            homeHotVodAdapter.setNewData(list);
            safeShelfLayout();
        }
    }

    /** 一级态 tab 预览：货架切到所选 tab 内容（含空列表，铺空避免停留"推荐"旧数据）。 */
    public static void showSortPreview(List<Movie.Video> list) {
        if (homeHotVodAdapter == null) {
            return;
        }
        shelfPreviewActive = true;
        ArrayList<Movie.Video> preview = new ArrayList<>();
        if (list != null && !list.isEmpty()) {
            preview.addAll(list.subList(0, Math.min(list.size(), 25)));
        }
        homeHotVodAdapter.setNewData(preview);
        LogWriter.i("shelf preview size=" + preview.size());
        safeShelfLayout();
    }

    /** 防御：货架数据落位后补一次布局请求。货架 layout_gravity=bottom，整页重建的布局空档里
     *  未布局的货架几何位于父容器原点（屏幕左上角），若被提前绘制就会表现为推荐卡卡在左上角。 */
    private static void safeShelfLayout() {
        if (tvHotList == null) {
            return;
        }
        tvHotList.post(new Runnable() {
            @Override
            public void run() {
                if (tvHotList != null) {
                    tvHotList.requestLayout();
                    tvHotList.invalidate();
                }
            }
        });
    }

    /** 一级态回到推荐 tab：恢复首页货架数据（豆瓣热播/站点推荐）。 */
    public static void restoreHomeShelf() {
        if (tvHotList != null) {
            tvHotList.setVisibility(View.VISIBLE); // 防御：回一级必恢复货架
        }
        if (homeHotVodAdapter == null) {
            return;
        }
        shelfPreviewActive = false;
        if (lastHomeShelf == null) {
            return;
        }
        homeHotVodAdapter.setNewData(new ArrayList<>(lastHomeShelf));
        LogWriter.i("shelf restore size=" + lastHomeShelf.size());
        safeShelfLayout();
    }

    private void initHomeHotVod() {
        int recMode = Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC);
        if (recMode == 1) {
            if (homeSourceRec != null) {
                renderHomeList(homeSourceRec);
                return;
            }
        } else if (recMode == 2) {
            heroBanner.setVisibility(View.GONE);
            return;
        }
        loadHeroData();
        setDouBanData();
    }

    /** 今日日期串（与 home_hot 缓存同格式）。 */
    private static String todayString() {
        Calendar cal = Calendar.getInstance();
        return String.format("%d%d%d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DATE));
    }

    /** 本会话已发起 Hero 请求的日期（跨 fragment 重建幂等，防 loadHomeSort 二次执行并发重发）。 */
    private static String heroRequestDay = null;

    /**
     * Hero 数据：电影/电视剧/综艺三分类近期热播（sort=U+今年年份）各取第一，
     * 再按条目的 subject id 请求 subject_abstract 补全年份/类型/剧情简介。按天缓存。
     */
    private void loadHeroData() {
        String today = todayString();
        if (Hawk.get(CACHE_VER + "home_hero_day", "").equals(today) && restoreHeroCache()) {
            LogWriter.i("hero cache hit today");
            renderHero();
            return;
        }
        if (today.equals(heroRequestDay)) {
            // 当天已发过三分类请求（loadHomeSort 会跑两次），二次触发直接忽略，
            // 否则两轮并发交错回填会多次 renderHero → setData 重置轮播节律
            LogWriter.i("hero request already sent today, skip");
            return;
        }
        heroRequestDay = today;
        java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger(HERO_TAGS.length);
        heroItems.clear();
        heroExtras.clear();
        android.os.Handler stagger = new android.os.Handler(android.os.Looper.getMainLooper());
        for (int i = 0; i < HERO_TAGS.length; i++) {
            heroItems.add(null);
            heroExtras.add(null);
            final int idx = i;
            // 错峰发请求（间隔900ms），降低并发触发豆瓣风控的概率
            stagger.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mActivity == null) return;
                    fetchHeroTag(idx, HERO_TAGS[idx], pending, today);
                }
            }, i * 900L);
        }
    }

    private void fetchHeroTag(final int index, final String tag,
                              final java.util.concurrent.atomic.AtomicInteger pending, final String today) {
        // sort=U 近期热度 + 今年年份过滤 = 近期热播（sort=T 是全时间热门榜，会推老综艺）
        int year = Calendar.getInstance().get(Calendar.YEAR);
        String url = "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=" + tag
                + "&playable=1&start=0&year_range=" + year + "," + year;
        LogWriter.i("hero[" + index + "] req tag=" + tag + " url=" + url);
        OkGo.<String>get(url)
                .headers("User-Agent", UA.randomOne())
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        String body = response.body();
                        LogWriter.i("hero[" + index + "] resp len=" + (body == null ? -1 : body.length())
                                + " head=" + (body == null ? "null" : body.substring(0, Math.min(160, body.length()))));
                        try {
                            JsonObject infoJson = new Gson().fromJson(body, JsonObject.class);
                            JsonArray array = infoJson != null && infoJson.has("data") && !infoJson.get("data").isJsonNull()
                                    ? infoJson.getAsJsonArray("data") : null;
                            if (array != null && array.size() > 0) {
                                JsonObject obj = array.get(0).getAsJsonObject();
                                Movie.Video vod = new Movie.Video();
                                vod.name = obj.get("title").getAsString();
                                vod.note = obj.get("rate").getAsString();
                                vod.pic = obj.get("cover").getAsString()
                                        .replace("s_ratio_poster", "l_ratio_poster")
                                        + "@User-Agent=" + UA.randomOne()
                                        + "@Referer=https://www.douban.com/";
                                LogWriter.i("hero[" + index + "] pick=" + vod.name + " rate=" + vod.note);
                                String subjectId = "";
                                if (obj.has("url") && !obj.get("url").isJsonNull()) {
                                    java.util.regex.Matcher m = java.util.regex.Pattern
                                            .compile("subject/(\\d+)").matcher(obj.get("url").getAsString());
                                    if (m.find()) subjectId = m.group(1);
                                }
                                heroItems.set(index, vod);
                                // 摘要接口失败前的保底 meta：分类标签 + 评分
                                String fallbackMeta = tag + (vod.note.isEmpty() || "0".equals(vod.note)
                                        ? "" : " · " + vod.note + " 分");
                                heroExtras.set(index, new String[]{fallbackMeta, ""});
                                if (!subjectId.isEmpty()) {
                                    fetchHeroRexxar(index, vod, tag, subjectId, pending, today);
                                    return;
                                }
                            } else {
                                LogWriter.w("hero[" + index + "] no data (风控或空) msg="
                                        + (infoJson != null && infoJson.has("msg") ? infoJson.get("msg").getAsString() : "?"));
                            }
                        } catch (Throwable t) {
                            LogWriter.e("hero[" + index + "] parse err", t);
                        }
                        finishHeroTag(pending, today);
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        LogWriter.e("hero[" + index + "] http err code=" + response.code());
                        finishHeroTag(pending, today);
                    }
                });
    }

    /**
     * rexxar 详情 JSON 解析（豆瓣模式与站源 Hero 文字补全共用）→ [meta, desc]。
     * meta = 年份 · 题材类型 · 影视类型 · 评分；desc = intro 真简介（string 或段落 array）压空白。
     */
    private static String[] parseRexxarDetail(JsonObject root, String tag) {
        // meta = 年份 · 题材类型(genres) · 影视类型(tag) · 评分
        StringBuilder meta = new StringBuilder();
        if (root.has("year") && !root.get("year").isJsonNull()) {
            String y = root.get("year").getAsString().trim();
            if (!y.isEmpty()) meta.append(y);
        }
        if (root.has("genres") && root.get("genres").isJsonArray()) {
            StringBuilder gs = new StringBuilder();
            for (JsonElement ge : root.getAsJsonArray("genres")) {
                if (gs.length() > 0) gs.append(" / ");
                gs.append(ge.getAsString());
            }
            if (gs.length() > 0) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(gs);
            }
        }
        if (meta.length() > 0) meta.append(" · ");
        meta.append(tag);
        String rate = "";
        if (root.has("rating") && root.get("rating").isJsonObject()
                && root.getAsJsonObject("rating").has("value")
                && !root.getAsJsonObject("rating").get("value").isJsonNull()) {
            rate = root.getAsJsonObject("rating").get("value").getAsString();
        }
        if (!rate.isEmpty() && !"0".equals(rate)) {
            meta.append(" · ").append(rate).append(" 分");
        } else {
            // 豆瓣未开分（《醉梦》《现在就出发 第四季》等新片）：显示"暂无评分"
            meta.append(" · 暂无评分");
        }
        // desc = intro 真简介（string 或段落 array），压空白
        String desc = "";
        if (root.has("intro") && !root.get("intro").isJsonNull()) {
            if (root.get("intro").isJsonArray()) {
                StringBuilder is = new StringBuilder();
                for (JsonElement ie : root.getAsJsonArray("intro")) {
                    if (is.length() > 0) is.append(" ");
                    is.append(ie.getAsString());
                }
                desc = is.toString();
            } else {
                desc = root.get("intro").getAsString();
            }
        }
        desc = desc.replaceAll("\\s+", " ").trim();
        return new String[]{meta.toString(), desc};
    }

    /**
     * 详情主源：m.douban.com rexxar v2（实测含 year/genres/rating/intro 真简介）。
     * 电影走 movie/{id}，电视剧/综艺走 tv/{id}；失败降级 subject_abstract。
     */
    private void fetchHeroRexxar(final int index, final Movie.Video vod, final String tag,
                                 String subjectId, final java.util.concurrent.atomic.AtomicInteger pending,
                                 final String today) {
        String kind = "电影".equals(tag) ? "movie" : "tv";
        String url = "https://m.douban.com/rexxar/api/v2/" + kind + "/" + subjectId;
        LogWriter.i("hero[" + index + "] rexxar req " + url);
        OkGo.<String>get(url)
                .headers("User-Agent", REXXAR_UA)
                .headers("Referer", "https://m.douban.com/")
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            JsonObject root = new Gson().fromJson(response.body(), JsonObject.class);
                            if (root != null) {
                                String[] ext = parseRexxarDetail(root, tag);
                                heroExtras.set(index, ext);
                                LogWriter.i("hero[" + index + "] rexxar ok meta=[" + ext[0] + "] descLen=" + ext[1].length());
                                // 主渲染立即完成（文字 + 内置壁纸底图），海报链异步后补——失败绝不阻塞 Hero 渲染
                                finishHeroTag(pending, today);
                                // WebView 海报优先链：正式海报>预告海报>壁纸，失败才回退 rexxar 剧照
                                huntPoster(vod, subjectId, index, kind, null);
                            } else {
                                finishHeroTag(pending, today);
                            }
                        } catch (Throwable t) {
                            LogWriter.e("hero[" + index + "] rexxar parse err", t);
                            finishHeroTag(pending, today);
                        }
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        LogWriter.e("hero[" + index + "] rexxar err code=" + response.code() + " → abstract 降级");
                        fetchHeroAbstract(index, vod, tag, subjectId, pending, today);
                    }
                });
    }

    /** 详情降级源：subject_abstract 摘要（无简介，用热门短评顶 desc），meta 补类别标签。 */
    private void fetchHeroAbstract(final int index, final Movie.Video vod, final String tag,
                                   String subjectId, final java.util.concurrent.atomic.AtomicInteger pending,
                                   final String today) {
        OkGo.<String>get("https://movie.douban.com/j/subject_abstract?subject_id=" + subjectId)
                .headers("User-Agent", UA.randomOne())
                .headers("Referer", "https://movie.douban.com/subject/" + subjectId + "/")
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        // 起点继承 fetchHeroTag 的保底 meta（类别 · 评分），摘要接口风控/失败时不丢信息
                        String[] cur = heroExtras.get(index);
                        String meta = cur != null ? cur[0] : tag;
                        String desc = "";
                        try {
                            JsonObject root = new Gson().fromJson(response.body(), JsonObject.class);
                            JsonObject subject = root != null && root.has("subject") && !root.get("subject").isJsonNull()
                                    ? root.getAsJsonObject("subject") : null;
                            if (subject != null) {
                                meta = "";
                                // 评分（豆瓣未开分时显示"暂无评分"）
                                String rate = subject.has("rate") && !subject.get("rate").isJsonNull()
                                        ? subject.get("rate").getAsString() : "";
                                if (!rate.isEmpty() && !"0".equals(rate)) {
                                    meta = rate + " 分";
                                } else {
                                    meta = "暂无评分";
                                }
                                // 类型（types: 剧情爱情古装…）
                                if (subject.has("types") && subject.get("types").isJsonArray()) {
                                    JsonArray types = subject.getAsJsonArray("types");
                                    StringBuilder ts = new StringBuilder();
                                    for (int i = 0; i < types.size() && i < 3; i++) {
                                        if (ts.length() > 0) ts.append(" / ");
                                        ts.append(types.get(i).getAsString());
                                    }
                                    if (ts.length() > 0) meta = ts + (meta.isEmpty() ? "" : " · " + meta);
                                }
                                // 类别（电影/电视剧/综艺）
                                meta = meta.isEmpty() ? tag : meta + " · " + tag;
                                // 年份（release_year）
                                String year = subject.has("release_year") && !subject.get("release_year").isJsonNull()
                                        ? subject.get("release_year").getAsString() : "";
                                if (!year.isEmpty()) meta = year + " · " + meta;
                                // 副标题用热门短评（该接口无 summary 简介）
                                if (subject.has("short_comment") && !subject.get("short_comment").isJsonNull()) {
                                    JsonObject sc = subject.getAsJsonObject("short_comment");
                                    if (sc.has("content") && !sc.get("content").isJsonNull()) {
                                        desc = sc.get("content").getAsString().replaceAll("\\s+", " ").trim();
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        if (meta.isEmpty()) meta = tag;
                        String[] ext = heroExtras.get(index);
                        if (ext != null) {
                            ext[0] = meta;
                            ext[1] = desc;
                        }
                        finishHeroTag(pending, today);
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        finishHeroTag(pending, today);
                    }
                });
    }

    /** 照片池 JSON 解析：photos[] 内挑横版图。
     *  实测 rexxar photos 接口无视 type/subtype/sortby 参数，恒返回混合全池（海报+壁纸+剧照），
     *  且照片对象的 type/subtype 字段恒为通用值 photo/photo，无法区分网页版的
     *  「正式海报(R,o) > 预告海报(R,p) > 壁纸(W) > 剧照(S)」分层——只能客户端近似官方意图：
     *  横版(w>h)内优先宽幅(≥1.7，官方横版主视觉构图)，同档按 likers_count 最高（官方图获赞最多），再按宽度最大。 */
    private static String parseStill(String body) {
        try {
            JsonObject root = new Gson().fromJson(body, JsonObject.class);
            if (root == null || !root.has("photos") || !root.get("photos").isJsonArray()) return null;
            String bestWide = null, bestAny = null;
            long bestWideScore = -1, bestAnyScore = -1;
            for (JsonElement el : root.getAsJsonArray("photos")) {
                try {
                    JsonObject p = el.getAsJsonObject();
                    if (!p.has("image") || p.get("image").isJsonNull()) continue;
                    JsonObject large = p.getAsJsonObject("image").getAsJsonObject("large");
                    int w = large.get("width").getAsInt();
                    int h = large.get("height").getAsInt();
                    if (w <= h) continue;
                    String url = large.get("url").getAsString();
                    long likers = p.has("likers_count") && !p.get("likers_count").isJsonNull()
                            ? p.get("likers_count").getAsLong() : 0;
                    // 同档评分：点赞为主（官方主视觉获赞最高），宽度消歧
                    long score = likers * 100000L + w;
                    if (w >= h * 1.7) {
                        if (score > bestWideScore) {
                            bestWideScore = score;
                            bestWide = url;
                        }
                    } else if (score > bestAnyScore) {
                        bestAnyScore = score;
                        bestAny = url;
                    }
                } catch (Throwable ignored) {
                }
            }
            return bestWide != null ? bestWide : bestAny;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 拉取横版海报/壁纸/剧照替换 vod.pic（rexxar photos 接口）。
     * combo 队列元素为 kind（movie/tv），依次尝试（kind 404 时兜底）直至拿到横图；
     * type/subtype/sortby 参数均被 API 无视，分类选图在 parseStill 客户端完成。
     * 全失败保留竖版原图。完成必回调 onDone（成功/失败均回调一次）。
     */
    private void fetchStillPhotos(final Movie.Video vod, final String subjectId,
                                  final java.util.ArrayDeque<String> combos, final Runnable onDone) {
        if (combos.isEmpty()) {
            onDone.run();
            return;
        }
        final String kind = combos.poll();
        // API 单次上限 50 张：池子小时横图可能只有特写，官方 16:9 横版主视觉常排在 17~50 位（如兰香如故 533 张池）
        String url = "https://m.douban.com/rexxar/api/v2/" + kind + "/" + subjectId
                + "/photos?start=0&count=50&size=a";
        LogWriter.i("hero still req " + kind + " " + vod.name);
        OkGo.<String>get(url)
                .headers("User-Agent", REXXAR_UA)
                .headers("Referer", "https://m.douban.com/")
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        String still = parseStill(response.body());
                        if (still != null) {
                            vod.pic = still + "@User-Agent=" + UA.randomOne()
                                    + "@Referer=https://www.douban.com/";
                            LogWriter.i("hero still ok(" + kind + ") " + vod.name + " " + still);
                            onDone.run();
                        } else {
                            LogWriter.w("hero still none(" + kind + ") " + vod.name + " → 换源或保留竖图");
                            fetchStillPhotos(vod, subjectId, combos, onDone);
                        }
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        LogWriter.e("hero still err(" + kind + ") code=" + response.code() + " " + vod.name);
                        fetchStillPhotos(vod, subjectId, combos, onDone);
                    }
                });
    }

    /**
     * WebView 海报优先链：正式海报 > 预告海报 > 壁纸。
     * 借 WebView 执行 sec.douban.com 挑战 JS 放行桌面照片页，注入 JS 提取列表、
     * 下载 m 缩略图解码边界挑横版（页面已按尺寸排序，第一张横版即取），命中即换 l 大图直链。
     * 全链失败回退 rexxar 剧照横图（kind 未知时 tv/movie 队列）；两者皆败保持内置壁纸。
     * onFallbackDone：整条链（海报或剧照兜底）结束时回调一次，供站源链路 pending 计数，可为 null。
     */
    private void huntPoster(final Movie.Video vod, final String subjectId,
                            final int index, final String kind, final Runnable onFallbackDone) {
        if (mActivity == null) {
            if (onFallbackDone != null) onFallbackDone.run();
            return;
        }
        final String oldPic = vod.pic == null ? "" : vod.pic;
        // 屏幕比例（宽/高）：跨级比例匹配的目标 R。AutoSize 会改写 Resources 的 DisplayMetrics
        // （实测拿到 2.21 的逻辑比例），必须用 WindowManager getRealMetrics 取真实物理分辨率
        double screenRatio = 16.0 / 9.0;
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            mActivity.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            if (dm.heightPixels > 0) screenRatio = (double) dm.widthPixels / dm.heightPixels;
        } catch (Throwable ignored) {
        }
        DoubanPosterFetcher.fetch(subjectId, vod.name, screenRatio, url -> mActivity.runOnUiThread(() -> {
            if (mActivity == null) return;
            if (url != null && heroBanner != null) {
                vod.pic = url;
                heroUpgraded.add(vod);
                heroBanner.rebindCurrent();
                saveHeroCache(todayString());
                if (onFallbackDone != null) onFallbackDone.run();
                return;
            }
            // WebView 全链失败 → rexxar 剧照兜底（横图）；type 参数被 API 无视，单 kind 拉全池客户端选图
            LogWriter.w("hero[" + index + "] poster chain failed → still fallback " + vod.name);
            java.util.ArrayDeque<String> combos = new java.util.ArrayDeque<>(kind != null
                    ? java.util.Collections.singletonList(kind)
                    : java.util.Arrays.asList("tv", "movie"));
            fetchStillPhotos(vod, subjectId, combos, () -> {
                boolean changed = !oldPic.equals(vod.pic);
                LogWriter.i("hero[" + index + "] still fallback changed=" + changed);
                if (changed) heroUpgraded.add(vod);
                if (changed && heroBanner != null && mActivity != null) {
                    mActivity.runOnUiThread(() -> {
                        // 数据引用未变，setData 会被同引用短路——必须 rebindCurrent 重绑
                        heroBanner.rebindCurrent();
                        saveHeroCache(todayString());
                    });
                }
                if (onFallbackDone != null) onFallbackDone.run();
            });
        }));
    }

    /** 站源 Hero 增强：剧名 → 豆瓣 suggest 搜索拿 id → 文字详情补全 + 海报链（失败剧照兜底）。全部完成后回调刷新。 */
    private void fetchSuggestThenStill(final Movie.Video vod, final String oldPic, final int index,
                                       final java.util.concurrent.atomic.AtomicInteger pending,
                                       final java.util.concurrent.atomic.AtomicInteger changed,
                                       final java.util.concurrent.atomic.AtomicInteger textChanged) {
        try {
            String q = java.net.URLEncoder.encode(vod.name == null ? "" : vod.name, "UTF-8");
            OkGo.<String>get("https://movie.douban.com/j/subject_suggest?q=" + q)
                    .headers("User-Agent", UA.randomOne())
                    .headers("Referer", "https://movie.douban.com/")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public void onSuccess(Response<String> response) {
                            String id = "";
                            String type = "movie";
                            try {
                                JsonArray arr = new Gson().fromJson(response.body(), JsonArray.class);
                                if (arr != null && arr.size() > 0) {
                                    JsonObject hit = arr.get(0).getAsJsonObject();
                                    id = hit.get("id").getAsString();
                                    if (hit.has("type") && !hit.get("type").isJsonNull()) {
                                        type = hit.get("type").getAsString();
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                            LogWriter.i("hero suggest " + vod.name + " id=" + id + " type=" + type);
                            if (!id.isEmpty()) {
                                final String sid = id;
                                // 文字详情补全（与豆瓣模式同源 rexxar 接口）：meta=年份·题材类型·影视类型·评分，desc=intro 简介
                                fillSourceHeroDetail(index, sid, type, textChanged);
                                // WebView 海报优先链：正式海报>预告海报>壁纸，失败才回退 rexxar 剧照（tv/movie 队列）
                                huntPoster(vod, sid, -1, null, () -> {
                                    if (!oldPic.equals(vod.pic)) {
                                        changed.incrementAndGet();
                                        if (mActivity != null) {
                                            mActivity.runOnUiThread(() -> {
                                                if (heroBanner != null) heroBanner.rebindCurrent();
                                            });
                                        }
                                    }
                                    if (pending.decrementAndGet() == 0) refreshHeroAfterStills(changed, textChanged);
                                });
                            } else if (pending.decrementAndGet() == 0) {
                                refreshHeroAfterStills(changed, textChanged);
                            }
                        }

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            return response.body().string();
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            if (pending.decrementAndGet() == 0) refreshHeroAfterStills(changed, textChanged);
                        }
                    });
        } catch (Throwable t) {
            LogWriter.e("hero suggest err", t);
            if (pending.decrementAndGet() == 0) refreshHeroAfterStills(changed, textChanged);
        }
    }

    /**
     * 站源 Hero 文字详情：rexxar v2 详情（与豆瓣模式同源解析）。
     * 成功按位回填 heroRenderExtras 并重绑当前页；失败/风控保持空槽 → 渲染层显示"简介：暂无"。
     */
    private void fillSourceHeroDetail(final int index, final String subjectId, final String suggestType,
                                      final java.util.concurrent.atomic.AtomicInteger textChanged) {
        String kind = "tv".equals(suggestType) ? "tv" : "movie";
        String tag = "tv".equals(suggestType) ? "电视剧" : "电影";
        String url = "https://m.douban.com/rexxar/api/v2/" + kind + "/" + subjectId;
        OkGo.<String>get(url)
                .headers("User-Agent", REXXAR_UA)
                .headers("Referer", "https://m.douban.com/")
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            JsonObject root = new Gson().fromJson(response.body(), JsonObject.class);
                            if (root != null && index >= 0 && index < heroRenderExtras.size()) {
                                String[] ext = parseRexxarDetail(root, tag);
                                heroRenderExtras.set(index, ext);
                                textChanged.incrementAndGet();
                                LogWriter.i("hero site detail[" + index + "] meta=[" + ext[0]
                                        + "] descLen=" + ext[1].length());
                                if (heroBanner != null) heroBanner.rebindCurrent();
                            }
                        } catch (Throwable t) {
                            LogWriter.e("hero site detail[" + index + "] parse err", t);
                        }
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        LogWriter.e("hero site detail[" + index + "] http err code=" + response.code());
                    }
                });
    }

    /** 站源 Hero 横图/文字全部就绪：任一有变更才重置轮播刷新（整页重绑，保证非当前页的文字详情也生效）。 */
    private void refreshHeroAfterStills(java.util.concurrent.atomic.AtomicInteger changed,
                                        java.util.concurrent.atomic.AtomicInteger textChanged) {
        if (changed.get() + textChanged.get() > 0 && heroBanner != null && mActivity != null) {
            mActivity.runOnUiThread(() -> heroBanner.setData(currentHeroList(), heroBinder));
            LogWriter.i("hero stills/texts applied, refreshed");
        }
    }

    /** 当前 Hero 列表：站源增强链路用（renderHomeList 中暂存）。 */
    private ArrayList<Movie.Video> heroStillTarget = null;

    private ArrayList<Movie.Video> currentHeroList() {
        return heroStillTarget != null ? heroStillTarget : new ArrayList<>(heroItems);
    }

    /** 三分类全部返回（成功或失败）后统一渲染并缓存。 */
    private void finishHeroTag(java.util.concurrent.atomic.AtomicInteger pending, String today) {
        if (pending.decrementAndGet() != 0) {
            return;
        }
        int filled = 0;
        for (Movie.Video v : heroItems) {
            if (v != null) filled++;
        }
        LogWriter.i("hero all done, filled=" + filled + "/" + heroItems.size());
        saveHeroCache(today);
        renderHero();
    }

    /** 渲染 Hero（过滤空槽位并同步对齐详情列表），全空则隐藏。仅豆瓣链路调用。 */
    private void renderHero() {
        ArrayList<Movie.Video> heroList = new ArrayList<>();
        heroRenderExtras.clear();
        for (int i = 0; i < heroItems.size(); i++) {
            Movie.Video vod = heroItems.get(i);
            if (vod != null) {
                heroList.add(vod);
                heroRenderExtras.add(i < heroExtras.size() ? heroExtras.get(i) : null);
            }
        }
        if (heroList.isEmpty()) {
            heroBanner.setVisibility(View.GONE);
            return;
        }
        HomeActivity.notifyHomeDataReady();
        heroBanner.setVisibility(View.VISIBLE);
        heroBanner.setData(heroList, heroBinder);
    }

    /** 按天缓存 Hero（含 meta/desc），减少重复请求。 */
    private void saveHeroCache(String today) {
        try {
            JsonArray arr = new JsonArray();
            for (int i = 0; i < heroItems.size(); i++) {
                Movie.Video vod = heroItems.get(i);
                String[] ext = i < heroExtras.size() ? heroExtras.get(i) : null;
                if (vod == null) continue;
                JsonObject obj = new JsonObject();
                obj.addProperty("name", vod.name);
                obj.addProperty("rate", vod.note == null ? "" : vod.note);
                obj.addProperty("pic", vod.pic == null ? "" : vod.pic);
                obj.addProperty("meta", ext != null ? ext[0] : "");
                obj.addProperty("desc", ext != null ? ext[1] : "");
                arr.add(obj);
            }
            // 全空（如被豆瓣风控）不缓存，下次启动重试
            if (arr.size() == 0) return;
            Hawk.put(CACHE_VER + "home_hero_day", today);
            Hawk.put(CACHE_VER + "home_hero_json", arr.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 恢复当日 Hero 缓存。 */
    private boolean restoreHeroCache() {
        try {
            String json = Hawk.get(CACHE_VER + "home_hero_json", "");
            if (json.isEmpty()) return false;
            JsonArray arr = new Gson().fromJson(json, JsonArray.class);
            if (arr == null || arr.size() == 0) return false;
            heroItems.clear();
            heroExtras.clear();
            for (int i = 0; i < arr.size(); i++) {
                JsonObject obj = arr.get(i).getAsJsonObject();
                Movie.Video vod = new Movie.Video();
                vod.name = obj.has("name") ? obj.get("name").getAsString() : "";
                vod.note = obj.has("rate") ? obj.get("rate").getAsString() : "";
                vod.pic = obj.has("pic") ? obj.get("pic").getAsString() : "";
                heroItems.add(vod);
                heroExtras.add(new String[]{
                        obj.has("meta") ? obj.get("meta").getAsString() : "",
                        obj.has("desc") ? obj.get("desc").getAsString() : ""});
            }
            // 缓存恢复的条目视为已升级：直接显示缓存图（上轮链路终态），不再回落壁纸
            heroUpgraded.addAll(heroItems);
            LogWriter.i("hero cache restored n=" + heroItems.size());
            return !heroItems.isEmpty();
        } catch (Throwable th) {
            return false;
        }
    }

    private void setDouBanData() {
        try {
            Calendar cal = Calendar.getInstance();
            int year = cal.get(Calendar.YEAR);
            int month = cal.get(Calendar.MONTH) + 1;
            int day = cal.get(Calendar.DATE);
            String today = String.format("%d%d%d", year, month, day);
            String requestDay = Hawk.get(CACHE_VER + "home_hot_day", "");
            if (requestDay.equals(today)) {
                String json = Hawk.get(CACHE_VER + "home_hot", "");
                if (!json.isEmpty()) {
                    ArrayList<Movie.Video> hotMovies = loadHots(json);
                    if (hotMovies != null && hotMovies.size() > 0) {
                        renderHomeList(hotMovies);
                        return;
                    }
                }
            }
            String doubanUrl = "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0&year_range=" + year + "," + year;
            // 延后至 Hero 三分类请求之后发出，错峰降低风控概率
            final String fToday = today;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mActivity == null) return;
                    requestDouBan(doubanUrl, fToday, true);
                }
            }, 2700L);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /**
     * 请求豆瓣货架数据。风控（r=1）/空结果/网络失败时：allowFallback 则换
     * 近期热度参数（sort=T，无年份限制）重试一次；最终仍空则不缓存不渲染，
     * 避免空数据被固化一整天。
     */
    private void requestDouBan(final String url, final String today, final boolean allowFallback) {
        OkGo.<String>get(url)
                .headers("User-Agent", UA.randomOne())
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        if (mActivity == null) return;
                        ArrayList<Movie.Video> list = loadHots(response.body());
                        if (list.isEmpty()) {
                            retryDouBanOrGiveUp(today, allowFallback);
                            return;
                        }
                        Hawk.put(CACHE_VER + "home_hot_day", today);
                        Hawk.put(CACHE_VER + "home_hot", response.body());
                        mActivity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                renderHomeList(list);
                            }
                        });
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        retryDouBanOrGiveUp(today, allowFallback);
                    }
                });
    }

    private void retryDouBanOrGiveUp(final String today, boolean allowFallback) {
        if (!allowFallback) {
            return;
        }
        String fallbackUrl = "https://movie.douban.com/j/new_search_subjects?sort=T&range=0,10&tags=&playable=1&start=0";
        requestDouBan(fallbackUrl, today, false);
    }

    private ArrayList<Movie.Video> loadHots(String json) {
        ArrayList<Movie.Video> result = new ArrayList<>();
        try {
            JsonObject infoJson = new Gson().fromJson(json, JsonObject.class);
            // 豆瓣风控响应：{"r":1,"msg":"检测到有异常请求..."}，无 data 字段
            if (infoJson == null || !infoJson.has("data") || infoJson.get("data").isJsonNull()) {
                return result;
            }
            JsonArray array = infoJson.getAsJsonArray("data");
            int limit = Math.min(array.size(), 25);
            for (int i = 0; i < limit; i++) {
                JsonElement ele = array.get(i);
                JsonObject obj = ele.getAsJsonObject();
                Movie.Video vod = new Movie.Video();
                vod.name = obj.get("title").getAsString();
                vod.note = obj.get("rate").getAsString();
                if (!vod.note.isEmpty()) vod.note += " 分";
                vod.pic = obj.get("cover").getAsString()
                        .replace("s_ratio_poster", "l_ratio_poster")
                        + "@User-Agent=" + UA.randomOne()
                        + "@Referer=https://www.douban.com/";

                result.add(vod);
            }
        } catch (Throwable th) {

        }
        return result;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void server(ServerEvent event) {
        if (event.type == ServerEvent.SERVER_CONNECTION) {
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        if (instance == this) instance = null;
        if (userScroll == findViewById(R.id.userScroll)) userScroll = null;
        if (tvHotList == findViewById(R.id.tvHotList)) tvHotList = null;
    }
}
