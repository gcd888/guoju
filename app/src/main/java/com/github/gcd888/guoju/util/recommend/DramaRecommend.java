package com.github.gcd888.guoju.util.recommend;

import com.github.gcd888.guoju.bean.Movie;
import com.github.gcd888.guoju.util.HongguoRank;

import java.util.List;

/**
 * 短剧热播策略（GuojuRecommend 子类）：红果官网 rank/hot-drama（解析与请求见 HongguoRank）。
 * 零配置可用，不依赖任何短剧源配置。
 *
 * 架构关系：
 *   GuojuRecommend（父类/主调度）
 *     └ DramaRecommend（本类，短剧子策略）
 *         └ 热播列表：HongguoRank.loadHotDrama()
 *
 * 热播列表条目已含展示所需的全部字段（名称/热度/类型/简介），无需进详情页。
 * 播放地址获取不在本层做（番茄 API 签名复杂，Android 不内嵌），
 * 播放仍走用户配置的短剧源（FastSearch → 站源详情）。
 */
public class DramaRecommend implements GuojuRecommend.Provider {
    @Override
    public void load(final int page, final GuojuRecommend.Callback callback) {
        HongguoRank.loadHotDrama(page, new HongguoRank.Callback() {
            @Override
            public void onSuccess(List<Movie.Video> list, int totalPages) {
                if (callback != null) callback.onData(list, totalPages);
            }

            @Override
            public void onError(String msg) {
                if (callback != null) callback.onError(msg);
            }
        });
    }

    @Override
    public void cancel() {
        HongguoRank.cancel();
    }
}
