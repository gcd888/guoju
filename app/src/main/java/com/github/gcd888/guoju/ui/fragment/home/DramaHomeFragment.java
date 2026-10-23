package com.github.gcd888.guoju.ui.fragment.home;

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
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.gcd888.guoju.R;
import com.github.gcd888.guoju.api.ApiConfig;
import com.github.gcd888.guoju.bean.AbsSortXml;
import com.github.gcd888.guoju.bean.Movie;
import com.github.gcd888.guoju.bean.MovieSort;
import com.github.gcd888.guoju.bean.SourceBean;
import com.github.gcd888.guoju.cache.RoomDataManger;
import com.github.gcd888.guoju.ui.activity.FastSearchActivity;
import com.github.gcd888.guoju.ui.activity.HomeActivity;
import com.github.gcd888.guoju.ui.adapter.HomeHotVodAdapter;
import com.github.gcd888.guoju.ui.adapter.SelectDialogAdapter;
import com.github.gcd888.guoju.ui.dialog.SelectDialog;
import com.github.gcd888.guoju.ui.tv.widget.LoadMoreView;
import com.github.gcd888.guoju.ui.widget.HeroBannerView;
import com.github.gcd888.guoju.util.FastClickCheckUtil;
import com.github.gcd888.guoju.util.HawkConfig;
import com.github.gcd888.guoju.util.recommend.GuojuRecommend;
import com.github.gcd888.guoju.util.ImgUtil;
import com.github.gcd888.guoju.util.LogWriter;
import com.github.gcd888.guoju.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.List;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 短剧主页（HomeActivity ViewPager 房间 0，与点播 VodHomeFragment 同机制）：
 * 全屏 Hero 轮播（前三热播）+ 一行货架 + gridOverlay 二级叠加层。
 * 底部 tab 行 / 观看历史 / 我的收藏 / 分类房间 / 双击二级 / 一级预览
 * 全部由 HomeActivity 统一管线管理（与点播完全一致），本页只负责推荐数据：
 * 「果剧推荐」= 红果热播榜（GuojuRecommend 短剧策略→红果官网）；「站点推荐」= 当前短剧源第一分类数据。
 * 与点播唯一的差异是影视数据来源。
 */
public class DramaHomeFragment extends HomeTabFragment {
    private static final String TAG = "echo-drama page";

    @Override
    public int getMediaType() {
        return RoomDataManger.MEDIA_TYPE_DRAMA;
    }

    private static DramaHomeFragment instance;
    private static HomeHotVodAdapter heroShelfAdapter;
    private static TvRecyclerView tvShelfList;
    /** 推荐货架快照（预览/恢复基准，与点播 lastHomeShelf 同机制）。 */
    private static List<Movie.Video> lastDramaShelf = null;
    private static boolean shelfPreviewActive = false;

    private SourceViewModel sourceViewModel;
    private HomeHotVodAdapter heroAdapter;
    private TvRecyclerView gridOverlay;
    private HomeHotVodAdapter gridOverlayAdapter;
    private HeroBannerView heroBanner;
    private HeroBannerView.Binder<Movie.Video> heroBinderField;
    /** true=「果剧推荐」（红果热播榜）；false=「站点推荐」（短剧源第一分类数据）。 */
    private boolean rankMode = true;
    private String dramaKey = "";
    private int page = 1;
    private int rankTotalPages = 1;
    private boolean isRequesting = false;
    private int requestPage = -1;

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_drama_home;
    }

    @Override
    protected void onFragmentResume() {
        super.onFragmentResume();
        // 货架布局（单行/多行）与点播同机制：多行=V7GridLayoutManager+定高两行，网格内部滚动
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
        shelfPreviewActive = false; // 防御：跨 Activity 重建的 static 残留（销毁于预览态）
        initView();
        initViewModel();
        initData();
    }

    private void initView() {
        heroBanner = findViewById(R.id.heroBanner);
        initHeroBanner();

        // 一行货架（与点播 tvHotList 同机制：layout_gravity=bottom 一行多列）
        tvShelfList = findViewById(R.id.mGridView);
        tvShelfList.setLayoutManager(new V7LinearLayoutManager(mContext, V7LinearLayoutManager.HORIZONTAL, false));
        int paddingLeft = -tvShelfList.mHorizontalSpacingWithMargins / 2 + getResources().getDimensionPixelSize(R.dimen.vs_60);
        int paddingRight = -tvShelfList.mHorizontalSpacingWithMargins / 2 + getResources().getDimensionPixelSize(R.dimen.vs_60);
        tvShelfList.setPadding(paddingLeft, getResources().getDimensionPixelSize(R.dimen.vs_10),
                paddingRight, getResources().getDimensionPixelSize(R.dimen.vs_20));
        heroAdapter = new HomeHotVodAdapter(null, "热播");
        tvShelfList.setAdapter(heroAdapter);
        heroShelfAdapter = heroAdapter;
        // 货架高度/位置变化（数据渲染、单行/多行切换）→ tab 行贴底重算（tab 底边贴货架顶边，与点播同机制）
        tvShelfList.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or2, int ob) {
                if (heroShelfAdapter != null && !heroShelfAdapter.getData().isEmpty()) {
                    HomeActivity.alignTabToShelf(tvShelfList);
                }
            }
        });
        heroAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = heroAdapter.getData().get(position);
                if (video == null || video.action != null) return;
                jumpDetail(video);
            }
        });

        // 二级叠加层（与点播 gridOverlay 同机制：双击推荐 tab 展开）
        gridOverlay = findViewById(R.id.gridOverlay);
        gridOverlay.setLayoutManager(new V7GridLayoutManager(mContext, isBaseOnWidth() ? 5 : 6));
        gridOverlayAdapter = new HomeHotVodAdapter(null, "热播");
        gridOverlay.setAdapter(gridOverlayAdapter);
        gridOverlayAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = gridOverlayAdapter.getData().get(position);
                if (video == null || video.action != null) return;
                jumpDetail(video);
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
        gridOverlayAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                loadMoreRank();
            }
        }, gridOverlay);
    }

    /**
     * 短剧点击（PRD 3.5）：已配置短剧源 → 快速搜索选源进详情（与点播一致）；
     * 零配置只展示不播放 → 提示先配置源。
     */
    private void jumpDetail(Movie.Video video) {
        if (ApiConfig.get().getDramaBeanList().isEmpty()) {
            LogWriter.i(TAG + ": click blocked (no drama source) name=" + video.name);
            Toast.makeText(mContext, "暂无短剧源，请先在设置中配置", Toast.LENGTH_LONG).show();
            return;
        }
        LogWriter.i(TAG + ": click search name=" + video.name);
        Bundle bundle = new Bundle();
        bundle.putString("title", video.name);
        jumpActivity(FastSearchActivity.class, bundle);
    }

    /** Hero 轮播：前三热播，竖版海报铺满全屏（与点播 Hero 同款单页布局 + 动态排版）。 */
    private void initHeroBanner() {
        heroBinderField = new HeroBannerView.Binder<Movie.Video>() {
            @NonNull
            @Override
            public View onCreateView(@NonNull android.content.Context context, @NonNull ViewGroup container) {
                return LayoutInflater.from(context).inflate(R.layout.view_hero_item, container, false);
            }

            @Nullable
            @Override
            public String coverUrl(@NonNull Movie.Video item) {
                String pic = item.pic == null ? "" : item.pic.trim();
                return TextUtils.isEmpty(pic) || ImgUtil.isBase64Image(pic) ? null : pic;
            }

            @Override
            public void onBind(@NonNull View itemView, Movie.Video item, int position) {
                ImageView ivHero = itemView.findViewById(R.id.ivHero);
                TextView tvTitle = itemView.findViewById(R.id.tvHeroTitle);
                TextView tvMeta = itemView.findViewById(R.id.tvHeroMeta);
                TextView tvDesc = itemView.findViewById(R.id.tvHeroDesc);
                String pic = item.pic == null ? "" : item.pic.trim();
                if (ImgUtil.isBase64Image(pic)) {
                    ivHero.setImageBitmap(ImgUtil.decodeBase64ToBitmap(pic));
                } else if (!TextUtils.isEmpty(pic)) {
                    // 铺满裁切：等比缩放铺满全屏，垂直从顶部向下截取（与点播 Hero 同款）
                    ImgUtil.loadHero(pic, ivHero, mContext.getResources().getDisplayMetrics().widthPixels,
                            mContext.getResources().getDisplayMetrics().heightPixels, item.name);
                } else {
                    ivHero.setImageDrawable(ImgUtil.createTextDrawable(item.name));
                }
                tvTitle.setText(item.name);
                // meta 行（PRD：热度 · 题材类型 · 影视类型 · 评分）：
                //   果剧推荐数据 note 已由 HongguoRank 拼好完整 meta（actor 为空，直接用 note）；
                //   站点推荐 spider 数据 actor=题材类型、note=集数/热度 → 拼"题材类型 · 集数/热度"
                String tags = item.actor == null ? "" : item.actor.trim();
                String note = item.note == null ? "" : item.note.trim();
                String meta = tags;
                if (!note.isEmpty()) meta = meta.isEmpty() ? note : meta + " · " + note;
                if (!meta.isEmpty()) {
                    tvMeta.setText(meta);
                    tvMeta.setVisibility(View.VISIBLE);
                } else {
                    tvMeta.setVisibility(View.GONE);
                }
                // 简介真实字段为 des（AbsJson: vod_content→des），此前读 content 恒空；content 仅作兜底
                String desc = item.des != null && !item.des.trim().isEmpty() ? item.des.trim()
                        : (item.content == null ? "" : item.content.trim());
                if (!desc.isEmpty()) {
                    // 简介宽度=屏幕 2/3（与点播同款；AutoSize 改写指标，须 getRealMetrics 取物理宽）
                    try {
                        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                        mActivity.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
                        ViewGroup parent = (ViewGroup) tvDesc.getParent();
                        int side = parent == null ? 0 : parent.getPaddingLeft();
                        tvDesc.setWidth(dm.widthPixels * 2 / 3 - side);
                    } catch (Throwable ignored) {
                    }
                    tvDesc.setText(desc);
                    tvDesc.setVisibility(View.VISIBLE);
                } else {
                    // 无简介：占位显示，不隐藏（与点播 Hero 一致）
                    tvDesc.setText("简介：暂无");
                    tvDesc.setVisibility(View.VISIBLE);
                }
                // 动态排版：信息区顶距随导航栏实际高度、简介行数随底部 tab 行实际位置
                View infoBox = (View) tvTitle.getParent();
                applyHeroLayout(infoBox, tvTitle, tvMeta, tvDesc, HomeActivity.getHomeTabTopPx());
                LogWriter.i(TAG + ": hero bind[" + position + "] " + item.name);
                // 片名获/失焦：同步轮播暂停态 + 缩放表达焦点（与点播同款）
                tvTitle.setOnFocusChangeListener((v, hasFocus) -> { heroBanner.refreshFocusPause(); com.github.gcd888.guoju.util.HeroInfoLayout.applyTitleFocus(v, hasFocus); });
                // 片名可点击（遥控 OK）：与货架点击同逻辑（已配置短剧源跳快速搜索，零配置提示）
                tvTitle.setOnClickListener(v -> jumpDetail(item));
            }
        };
        heroBanner.setData(null, heroBinderField);
    }

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.sortResult.observe(this, new Observer<AbsSortXml>() {
            @Override
            public void onChanged(AbsSortXml absXml) {
                // 与点播 initViewPager 的 my0 分支同机制：站点推荐数据直供货架 + 分类上报建房间
                List<MovieSort.SortData> categories = absXml != null && absXml.classes != null
                        && absXml.classes.sortList != null ? absXml.classes.sortList : null;
                LogWriter.i(TAG + ": sortResult categories=" + (categories == null ? 0 : categories.size()));
                HomeActivity.appendDramaCategory(categories, dramaKey);
                if (!rankMode && absXml != null && absXml.videoList != null && !absXml.videoList.isEmpty()) {
                    renderDramaList(absXml.videoList);
                }
            }
        });
    }

    private void initData() {
        rankMode = Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 0;
        dramaKey = Hawk.get(HawkConfig.DRAMA_HOME, "");
        SourceBean drama = ApiConfig.get().getDrama(dramaKey);
        LogWriter.i(TAG + ": init savedKey=" + (dramaKey.isEmpty() ? "(none)" : dramaKey)
                + " savedFound=" + (drama != null)
                + " totalDramas=" + ApiConfig.get().getDramaBeanList().size()
                + " rankMode=" + rankMode);
        // 短剧源解析（与点播 homeSourceBean 同语义）：优先上次选中，否则取 json 第一个短剧源。
        // 分类拉取不随推荐模式门控（与点播 loadHomeSort 无条件 getSort 一致）
        if (drama == null) {
            List<SourceBean> dramas = new ArrayList<>(ApiConfig.get().getDramaBeanList().values());
            if (!dramas.isEmpty()) {
                drama = dramas.get(0);
                ApiConfig.get().setHomeDrama(drama);
                dramaKey = drama.getKey();
                LogWriter.i(TAG + ": fallback to first drama key=" + dramaKey);
            }
        }
        // 果剧推荐：货架走红果热播榜（不依赖短剧源）；分类仍拉源 homeContent 追加 tab
        if (rankMode) {
            page = 1;
            loadRankPage();
            if (drama != null) sourceViewModel.getSort(dramaKey);
            return;
        }
        // 站点推荐：必须有短剧源；零配置不拉任何外部配置（PRD 红线），空态提示用户配置
        if (drama == null) {
            LogWriter.i(TAG + ": site-recommend without drama source, show empty state");
            Toast.makeText(mContext, "暂无短剧源，请先在设置中配置", Toast.LENGTH_LONG).show();
            showEmptyShelf();
            return;
        }
        // 站点推荐：分类与第一分类数据经 sortResult 回调到达
        sourceViewModel.getSort(dramaKey);
    }

    /** 推荐货架落地（红果/站点推荐共用）：快照 + Hero 前三 + 通知 HomeActivity 解除进入二级门槛。 */
    private void renderDramaList(List<Movie.Video> videos) {
        lastDramaShelf = videos == null ? null : new ArrayList<>(videos);
        if (shelfPreviewActive) {
            LogWriter.i(TAG + ": shelf home buffered size=" + (videos == null ? 0 : videos.size()));
        } else {
            heroAdapter.setNewData(lastDramaShelf);
            safeShelfLayout();
        }
        HomeActivity.notifyHomeDataReady();
        List<Movie.Video> heroList = videos != null && videos.size() > 3 ? videos.subList(0, 3) : videos;
        // 布局初始 visibility=gone，必须显式置回（否则轮播 bind/预载日志正常但画面空白）
        heroBanner.setVisibility(View.VISIBLE);
        heroBanner.setData(heroList == null ? new ArrayList<Movie.Video>() : new ArrayList<>(heroList), heroBinderField);
    }

    private void showEmptyShelf() {
        heroAdapter.setNewData(new ArrayList<Movie.Video>());
        heroBanner.setVisibility(View.GONE); // 无数据时收起 Hero（露出页面底色）
        heroBanner.setData(new ArrayList<>(), heroBinderField);
        HomeActivity.notifyHomeDataReady();
    }

    /** 红果热播榜分页加载（二级叠加层继续翻页）。经 GuojuRecommend 主调度取数（短剧策略=红果官网）。 */
    private void loadRankPage() {
        LogWriter.i(TAG + ": loadRankPage page=" + page);
        isRequesting = true;
        requestPage = page;
        final int reqPage = page;
        GuojuRecommend.recommend(GuojuRecommend.MEDIA_DRAMA, page, new GuojuRecommend.Callback() {
            @Override
            public void onData(List<Movie.Video> list, int totalPages) {
                isRequesting = false;
                if (reqPage != requestPage) return;
                rankTotalPages = Math.max(1, totalPages);
                if (reqPage == 1) {
                    renderDramaList(list);
                } else {
                    gridOverlayAdapter.addData(list);
                }
                page = reqPage + 1;
                gridOverlayAdapter.setEnableLoadMore(page <= rankTotalPages);
            }

            @Override
            public void onError(String msg) {
                isRequesting = false;
                if (reqPage != requestPage) return;
                if (reqPage == 1) {
                    showEmptyShelf();
                    Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show();
                    gridOverlayAdapter.loadMoreEnd();
                    gridOverlayAdapter.setEnableLoadMore(false);
                }
            }
        });
    }

    private void loadMoreRank() {
        if (isRequesting) return;
        if (page <= rankTotalPages) {
            loadRankPage();
        } else {
            gridOverlayAdapter.loadMoreEnd();
        }
    }

    // ==================== 静态桥接（HomeActivity 统一管线调用，与点播 VodHomeFragment 同机制） ====================

    /** 双击推荐 tab：展开推荐全屏网格（数据=货架快照，多行多列）。 */
    public static void showDramaRecGrid() {
        if (instance == null || instance.gridOverlay == null || instance.gridOverlayAdapter == null) {
            return;
        }
        List<Movie.Video> data = lastDramaShelf == null ? new ArrayList<>() : lastDramaShelf;
        instance.gridOverlayAdapter.setNewData(new ArrayList<>(data));
        instance.gridOverlay.setVisibility(View.VISIBLE);
        // 二级态物理隐藏货架：彻底杜绝任何切页状态异常下货架卡残留在推荐页上
        if (tvShelfList != null) {
            tvShelfList.setVisibility(View.GONE);
        }
        LogWriter.i(TAG + ": rec grid show size=" + data.size());
    }

    /** 二级态：tab 行下键，推荐网格叠加层获焦。 */
    public static void focusDramaRecGrid() {
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

    /** 回一级/离开推荐页：收起推荐全屏网格（幂等）。 */
    public static void hideDramaRecGrid() {
        if (instance == null || instance.gridOverlay == null) {
            return;
        }
        if (instance.gridOverlay.getVisibility() == View.VISIBLE) {
            instance.gridOverlay.setVisibility(View.GONE);
            LogWriter.i(TAG + ": rec grid hide");
        }
        // 恢复货架可见性（与 showDramaRecGrid 的 GONE 配对；无条件设置保证幂等）
        if (tvShelfList != null) {
            tvShelfList.setVisibility(View.VISIBLE);
        }
    }

    /** 一级态 tab 预览：货架切到所选 tab 内容（含空列表，铺空避免停留"推荐"旧数据）。 */
    public static void showDramaShelfPreview(List<Movie.Video> list) {
        if (heroShelfAdapter == null) {
            return;
        }
        shelfPreviewActive = true;
        ArrayList<Movie.Video> preview = new ArrayList<>();
        if (list != null && !list.isEmpty()) {
            preview.addAll(list.subList(0, Math.min(list.size(), 25)));
        }
        heroShelfAdapter.setNewData(preview);
        LogWriter.i(TAG + ": shelf preview size=" + preview.size());
        safeShelfLayout();
    }

    /** 一级态回到推荐 tab：恢复推荐货架数据（红果热播/站点推荐）。 */
    public static void restoreDramaShelf() {
        if (tvShelfList != null) {
            tvShelfList.setVisibility(View.VISIBLE); // 防御：回一级必恢复货架
        }
        if (heroShelfAdapter == null) {
            return;
        }
        shelfPreviewActive = false;
        if (lastDramaShelf == null) {
            return;
        }
        heroShelfAdapter.setNewData(new ArrayList<>(lastDramaShelf));
        LogWriter.i(TAG + ": shelf restore size=" + lastDramaShelf.size());
        safeShelfLayout();
    }

    /** 防御：货架数据落位后补一次布局请求（与点播 safeShelfLayout 同机制）。 */
    private static void safeShelfLayout() {
        if (tvShelfList == null) {
            return;
        }
        tvShelfList.post(new Runnable() {
            @Override
            public void run() {
                if (tvShelfList != null) {
                    tvShelfList.requestLayout();
                    tvShelfList.invalidate();
                }
            }
        });
    }

    /** 切换短剧源（HomeActivity 长按 home / 单击推荐 tab 桥接调用）。 */
    public void showDramaSwitch() {
        final List<SourceBean> dramas = new ArrayList<>(ApiConfig.get().getDramaBeanList().values());
        if (dramas.isEmpty()) return;
        int select = 0;
        for (int i = 0; i < dramas.size(); i++) {
            if (dramas.get(i).getKey().equals(dramaKey)) {
                select = i;
                break;
            }
        }
        SelectDialog<SourceBean> dialog = new SelectDialog<>(mActivity);
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
                // 与点播切站源同机制：缓存重启重建全部房间与 tab 行
                HomeActivity.refreshDramaHome();
            }

            @Override
            public String getDisplay(SourceBean value) {
                return value.getName();
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
        }, dramas, select);
        if (!dialog.isShowing()) dialog.show();
    }

    @Override
    public void onDestroyView() {
        GuojuRecommend.cancel(GuojuRecommend.MEDIA_DRAMA);
        if (instance == this) {
            instance = null;
            heroShelfAdapter = null;
            tvShelfList = null;
        }
        super.onDestroyView();
    }
}
