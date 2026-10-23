package com.github.gcd888.guoju.util.recommend;

import com.github.gcd888.guoju.bean.Movie;
import com.github.gcd888.guoju.util.LogWriter;
import com.github.gcd888.guoju.util.UA;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.StringCallback;
import com.lzy.okgo.model.Response;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 点播热播策略：豆瓣公开 api（new_search_subjects）电影/电视剧/综艺三分类聚合。
 * 与点播主页 Hero 三分类链路同源；Hero 的评分/简介增强（rexxar 详情）仍在
 * VodHomeFragment 内，本策略提供基础热播列表供主调度统一取数。
 * 零配置可用，不依赖任何站源配置。
 */
public class VodRecommend implements GuojuRecommend.Provider {
    private static final String TAG = "vod-recommend";
    private static final String[] TAGS = {"电影", "电视剧", "综艺"};
    /** 每分类取条数：三分类聚合后约 15 条，够一屏货架 + 前三 Hero。 */
    private static final int PER_TAG_COUNT = 5;
    private static final String REQUEST_TAG = "vod_recommend";

    @Override
    public void load(int page, final GuojuRecommend.Callback callback) {
        // 豆瓣接口无分页游标透出：首屏一次聚合，后续页直接回调空列表结束分页
        if (page > 1) {
            if (callback != null) callback.onData(new ArrayList<Movie.Video>(), 1);
            return;
        }
        final int year = Calendar.getInstance().get(Calendar.YEAR);
        final List<Movie.Video> merged = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger pending =
                new java.util.concurrent.atomic.AtomicInteger(TAGS.length);
        for (final String tag : TAGS) {
            String url = "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=" + tag
                    + "&playable=1&start=0&year_range=" + year + "," + year;
            OkGo.<String>get(url)
                    .tag(REQUEST_TAG)
                    .headers("User-Agent", UA.randomOne())
                    .execute(new StringCallback() {
                        @Override
                        public void onSuccess(Response<String> response) {
                            merged.addAll(parseTag(response.body(), tag));
                            done(pending, merged, callback);
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            LogWriter.e(TAG + ": req FAIL tag=" + tag + " code=" + response.code());
                            done(pending, merged, callback);
                        }
                    });
        }
    }

    @Override
    public void cancel() {
        try {
            OkGo.getInstance().cancelTag(REQUEST_TAG);
        } catch (Throwable ignored) {
        }
    }

    private void done(java.util.concurrent.atomic.AtomicInteger pending,
                      List<Movie.Video> merged, GuojuRecommend.Callback callback) {
        if (pending.decrementAndGet() != 0) {
            return;
        }
        if (callback == null) {
            return;
        }
        if (merged.isEmpty()) {
            callback.onError("豆瓣热播数据获取失败");
        } else {
            callback.onData(merged, 1);
        }
    }

    private List<Movie.Video> parseTag(String body, String tag) {
        List<Movie.Video> list = new ArrayList<>();
        try {
            JsonObject infoJson = new JsonParser().parse(body).getAsJsonObject();
            JsonArray array = infoJson.has("data") && !infoJson.get("data").isJsonNull()
                    ? infoJson.getAsJsonArray("data") : null;
            if (array == null) {
                return list;
            }
            int count = 0;
            for (JsonElement element : array) {
                if (count >= PER_TAG_COUNT) break;
                if (!element.isJsonObject()) continue;
                JsonObject obj = element.getAsJsonObject();
                Movie.Video vod = new Movie.Video();
                if (obj.has("title") && !obj.get("title").isJsonNull()) {
                    vod.name = obj.get("title").getAsString();
                }
                if (vod.name == null || vod.name.isEmpty()) continue;
                if (obj.has("rate") && !obj.get("rate").isJsonNull()) {
                    vod.note = obj.get("rate").getAsString();
                }
                if (obj.has("cover") && !obj.get("cover").isJsonNull()) {
                    vod.pic = obj.get("cover").getAsString().replace("s_ratio_poster", "l_ratio_poster")
                            + "@User-Agent=" + UA.randomOne() + "@Referer=https://www.douban.com/";
                }
                list.add(vod);
                count++;
            }
        } catch (Throwable t) {
            LogWriter.e(TAG + ": parse err tag=" + tag, t);
        }
        return list;
    }
}
