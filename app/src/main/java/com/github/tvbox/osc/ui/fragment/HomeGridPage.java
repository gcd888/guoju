package com.github.tvbox.osc.ui.fragment;

import com.github.tvbox.osc.bean.Movie;

import java.util.List;

/**
 * 首页二级全屏网格页统一契约：分类网格/观看历史/我的收藏共用同一套两层交互
 * （一级态 tab 预览一行货架，下键/上滑进入二级全屏网格）。
 */
public interface HomeGridPage {

    /** 数据是否已装载（一级态预览可直接取快照）。 */
    boolean isLoad();

    /** 预览数据快照（统一转为 Movie.Video，供首页货架渲染）。 */
    List<Movie.Video> getGridData();

    /** 进入二级全屏网格时聚焦第一张卡片（页面未实例化时挂起，待布局完成后补聚焦）。 */
    void focusGrid();

    /** tab 选中但无数据时是否允许触发刷新。 */
    boolean shouldReloadOnSelect();

    /** 强制重新装载数据。 */
    void forceRefresh();
}
