package com.github.gcd888.guoju.util.recommend;

import com.github.gcd888.guoju.bean.Movie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 果剧推荐 — 父类/主调度（PRD 3.2 & PRD 6）：
 *
 * 安装后默认「设置-首页推荐 = 果剧推荐」，无需任何配置即可看到各模块的热播数据。
 * 本类为父类/主入口，按 mediaType 分发到对应的子类策略（Provider 实现）：
 *
 *   GuojuRecommend（父类，本文件）
 *     ├ DramaRecommend   → 红果官网（短剧热播）
 *     ├ VodRecommend     → 豆瓣 api（点播子策略；暂未注册，点播热播仍在
 *     │                    VodHomeFragment 内部链路，迁移后 register 即接入）
 *     ├ LiveRecommend    → 预留占位（直播子策略）
 *     └ MusicRecommend   → 预留占位（音乐子策略）
 *
 * 父类职责：
 *   1. 维护模块→子策略注册表（Map<Integer, Provider>）
 *   2. 提供统一入口 recommend(mediaType, page, callback) 分发到子类
 *   3. 首屏缓存（TTL 10 分钟）+ 同模块 pending 请求合并（并发 recommend 只发一次网络）
 *   4. prefetchAll() 并行预取全部已注册模块首屏（进入 app 后台预热，切模块秒开；
 *      与页面请求共用 pending 合并去重；新模块 register 后自动被覆盖）
 *   5. 强制零配置红线——子类不得读取任何用户配置
 *
 * 线程模型：状态变更均在主线程（OkGo 回调主线程 + UI 发起），synchronized 兜底；
 * 回调一律在锁外投递（防 onData 同步重入 recommend 造成死锁）。
 */
public final class GuojuRecommend {
    /** 内容模块（与 mediaType 一致）：点播=0 直播=1 短剧=2 音乐=3。 */
    public static final int MEDIA_VOD = 0;
    public static final int MEDIA_LIVE = 1;
    public static final int MEDIA_DRAMA = 2;
    public static final int MEDIA_MUSIC = 3;

    /** 首屏缓存有效期（榜单类数据，避免长时间展示陈旧内容）。 */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    /** 热播数据回调：onData 在主线程。 */
    public interface Callback {
        void onData(List<Movie.Video> list, int totalPages);

        void onError(String msg);
    }

    /**
     * 内容源策略接口（子类实现）：一个模块一个实现，分页语义与站点一致（page 从 1 开始）。
     * 新增模块 = 新增一个 Provider 实现并 register 到父类，调用方无感知。
     */
    public interface Provider {
        void load(int page, Callback callback);

        void cancel();
    }

    /** 首屏缓存条目（page=1 聚合结果）。 */
    private static class CacheEntry {
        final List<Movie.Video> list;
        final int totalPages;
        final long at;

        CacheEntry(List<Movie.Video> list, int totalPages) {
            this.list = list;
            this.totalPages = totalPages;
            this.at = System.currentTimeMillis();
        }

        boolean valid() {
            return System.currentTimeMillis() - at < CACHE_TTL_MS;
        }
    }

    private static final Map<Integer, Provider> PROVIDERS = new LinkedHashMap<>();
    private static final Map<Integer, CacheEntry> CACHES = new HashMap<>();
    private static final Map<Integer, List<Callback>> PENDING = new HashMap<>();
    /** per-module 请求代次：cancel 时 +1，迟到回调按代次丢弃（cancelTag 后 OkGo 仍可能回调）。 */
    private static final Map<Integer, Integer> GENERATIONS = new HashMap<>();

    // 父类启动时注册已实现的子类策略
    static {
        register(MEDIA_DRAMA, new DramaRecommend());
        // 点播子策略暂不注册：点播热播仍在 VodHomeFragment 内部链路（豆瓣+rexxar 增强），
        // 注册会造成无人消费的重复豆瓣请求（风控风险）。点播迁移到统一调度后再开启：
        // register(MEDIA_VOD, new VodRecommend());
        // 直播/音乐子策略待实现后注册：
        // register(MEDIA_LIVE, new LiveRecommend());
        // register(MEDIA_MUSIC, new MusicRecommend());
    }

    /**
     * 注册子策略（新增模块时调用）。
     *
     * @param mediaType 模块类型（MEDIA_VOD / MEDIA_LIVE / MEDIA_DRAMA / MEDIA_MUSIC）
     * @param provider  子策略实现
     */
    public static void register(int mediaType, Provider provider) {
        synchronized (GuojuRecommend.class) {
            PROVIDERS.put(mediaType, provider);
        }
    }

    /**
     * 取热播数据（page=1 优先命中缓存/pending 合并；page>1 直接透传子策略）。
     * callback 传 null 即预热调用（结果只写缓存）。
     * onData 回调在主线程；列表为缓存/请求结果的拷贝，消费方可安全持有。
     */
    public static void recommend(int mediaType, int page, Callback callback) {
        if (page > 1) {
            Provider provider = providerOf(mediaType);
            if (provider == null) {
                if (callback != null) callback.onError("该模块暂未接入热播数据");
                return;
            }
            provider.load(page, callback);
            return;
        }
        List<Callback> pendingToEnqueue = null;
        CacheEntry hit = null;
        synchronized (GuojuRecommend.class) {
            Provider provider = PROVIDERS.get(mediaType);
            if (provider == null) {
                if (callback != null) callback.onError("该模块暂未接入热播数据");
                return;
            }
            CacheEntry cache = CACHES.get(mediaType);
            if (cache != null && cache.valid()) {
                hit = cache;
            } else {
                List<Callback> pending = PENDING.get(mediaType);
                if (pending != null) {
                    // 在途请求合并：只发一次网络，回调挂 pending 等结果
                    if (callback != null) pending.add(callback);
                } else {
                    pendingToEnqueue = new ArrayList<>();
                    if (callback != null) pendingToEnqueue.add(callback);
                    PENDING.put(mediaType, pendingToEnqueue);
                }
            }
        }
        if (hit != null) {
            if (callback != null) callback.onData(new ArrayList<>(hit.list), hit.totalPages);
            return;
        }
        if (pendingToEnqueue != null) {
            providerOf(mediaType).load(1, new CacheWriteCallback(mediaType));
        }
    }

    /**
     * 并行预取全部已注册模块首屏（进入 app 后台预热，切模块秒开）。
     * 统一走 recommend 的 pending 合并：与用户先行进入模块页的请求天然去重。
     * 重复调用安全（命中缓存/在途合并即无事发生）。
     */
    public static void prefetchAll() {
        List<Integer> mediaTypes;
        synchronized (GuojuRecommend.class) {
            mediaTypes = new ArrayList<>(PROVIDERS.keySet());
        }
        for (int mediaType : mediaTypes) {
            recommend(mediaType, 1, null);
        }
    }

    /**
     * 取消模块在途请求（页面销毁时调用）。
     * 只断网络 + 清 pending + 代次失效；缓存保留（否则预取结果被离开页面动作清掉，预热白费），
     * 缓存靠 TTL 自然过期。
     */
    public static void cancel(int mediaType) {
        Provider provider;
        synchronized (GuojuRecommend.class) {
            provider = PROVIDERS.get(mediaType);
            if (provider == null) return;
            PENDING.remove(mediaType);
            GENERATIONS.put(mediaType, generationOf(mediaType) + 1);
        }
        provider.cancel();
    }

    private static Provider providerOf(int mediaType) {
        synchronized (GuojuRecommend.class) {
            return PROVIDERS.get(mediaType);
        }
    }

    private static int generationOf(int mediaType) {
        Integer gen = GENERATIONS.get(mediaType);
        return gen == null ? 0 : gen;
    }

    /**
     * 首屏写缓存回调：成功（含迟到成功）一律写缓存——数据有效即缓存，投递才按代次过滤；
     * 失败不写负缓存。锁外把结果投递给等待中的 pending 回调。
     */
    private static class CacheWriteCallback implements Callback {
        private final int mediaType;
        private final int generation;

        CacheWriteCallback(int mediaType) {
            this.mediaType = mediaType;
            this.generation = generationOf(mediaType);
        }

        @Override
        public void onData(List<Movie.Video> list, int totalPages) {
            List<Callback> pending;
            boolean stale;
            synchronized (GuojuRecommend.class) {
                CACHES.put(mediaType, new CacheEntry(new ArrayList<>(list), totalPages));
                pending = PENDING.remove(mediaType);
                stale = generation != generationOf(mediaType);
            }
            if (pending == null || stale) return;
            for (Callback cb : pending) {
                try {
                    cb.onData(new ArrayList<>(list), totalPages);
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public void onError(String msg) {
            List<Callback> pending;
            boolean stale;
            synchronized (GuojuRecommend.class) {
                pending = PENDING.remove(mediaType);
                stale = generation != generationOf(mediaType);
            }
            if (pending == null || stale) return;
            for (Callback cb : pending) {
                try {
                    cb.onError(msg);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private GuojuRecommend() {
    }
}
