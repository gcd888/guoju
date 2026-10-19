package com.github.tvbox.osc.util;

import android.annotation.SuppressLint;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.tvbox.osc.base.App;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 豆瓣海报跨级比例匹配链（WebView 方案）：正式海报(R,o) → 预告海报(R,p) → 壁纸(W) → 剧照(调用方兜底)。
 * <p>
 * 背景实测：桌面版照片页被 sec.douban.com 的 JS 挑战保护——无 Cookie 的纯 HTTP 直连只回 3KB
 * 挑战页；App 可用的 rexxar photos 接口只开放剧照池且无视 type/subtype/sortby 参数。
 * 海报/壁纸池仅存在于桌面 HTML 页，故借 WebView 执行挑战 JS（实测 6~8s 自动放行、无需交互），
 * 页面渲染后注入 JS 提取 data-id + m 缩略图列表（服务端已按 sortby=size 排序）。
 * <p>
 * 选图决策树（屏幕比例 R，如 16:9=1.778；完全匹配容差 |r−R|/R ≤ 2%）：
 * ① 正式海报池有完全匹配 → 直接选中；② 无 → 暂存 oNear=池内最接近，查预告海报：
 * 预告有完全匹配 → 用预告（覆盖 oNear）；预告也无 → 用 oNear 结束（不看壁纸）；
 * ③ 正式海报池为空 → 预告有完全匹配直接选；无 → 暂存 pNear=预告最接近，查壁纸：
 * 壁纸有完全匹配 → 用壁纸；无 → pNear 优先，壁纸最接近次之；三级全空 → 回调 null。
 * <p>
 * 每级页面到手后并发（6 线程）下载全部 m 缩略图（约 30KB/张）解码边界得比例，
 * 全池信息是跨级决策（"最接近"）的前提；命中后取 l 大图直链（实测 1440×1080 级）。
 * <p>
 * 资源约束：单 WebView 串行处理全部任务（老旧电视防内存压力）；页级超时兜底；
 * 全链失败静默回调 null（调用方回退 rexxar 剧照横图）。
 */
public class DoubanPosterFetcher {
    private static final String TAG = "GuoJuHome";

    /** 桌面 UA：挑战与照片页按桌面站走 */
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 单页加载+挑战放行+提取预算（实测挑战约 6~8s，留足余量） */
    private static final long PAGE_TIMEOUT_MS = 18000L;

    /** DOM 提取轮询间隔 */
    private static final long POLL_INTERVAL_MS = 1200L;

    /** 完全匹配容差：|图片比例 − 屏幕比例| / 屏幕比例 ≤ 2%（16:10 等视为"最接近"而非匹配） */
    private static final double EXACT_TOLERANCE = 0.02;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 扫池调度线程（池内解码再拆并发） */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    /** m 缩略图并发下载解码池：全池并行使耗时 ≈ 单张 RTT × ⌈池大小/6⌉ */
    private static final ExecutorService DECODE = Executors.newFixedThreadPool(6);

    /** 待处理任务队列 + 运行标志：同一时刻至多一个 WebView 实例。 */
    private static final ArrayDeque<Task> QUEUE = new ArrayDeque<>();
    private static boolean running = false;

    /** 结果回调（主线程）：url=带防盗链后缀的大图直链；null=全链无合适图/失败。 */
    public interface Callback {
        void onResult(String url);
    }

    private static final class Task {
        final String subjectId;
        final String name;
        final Callback callback;
        /** 屏幕比例（宽/高），调用方按实际分辨率传入 */
        final double screenRatio;
        /** 当前加载的层级：0=正式海报 1=预告海报 2=壁纸 */
        int tier = 0;
        /** 正式海报池的"最接近"候选直链（池空时为 null） */
        volatile String oNear;
        /** oNear 与屏幕比例的差值（|r−R|），供跨级比较 */
        volatile double oNearDiff = Double.MAX_VALUE;
        /** 预告海报池的"最接近"候选直链（正式海报池为空场景才使用） */
        volatile String pNear;
        /** pNear 与屏幕比例的差值，供跨级比较 */
        volatile double pNearDiff = Double.MAX_VALUE;
        /** 当前页是否已有提取轮询在跑（防 onPageFinished 与兜底定时器双开） */
        volatile boolean polling;
        volatile boolean finished;
        /** 当前页的 WebView 引用（跨级复用，任务结束时销毁） */
        volatile WebView webView;
        /** 当前级的页面超时截止时间（onPageFinished 回调里动态取用） */
        volatile long pageDeadline;

        Task(String subjectId, String name, double screenRatio, Callback callback) {
            this.subjectId = subjectId;
            this.name = name;
            this.screenRatio = screenRatio;
            this.callback = callback;
        }
    }

    /** 单级页面扫描结果：完全匹配下标 / 最接近下标及其差值 / 候选 id 列表。 */
    private static final class Pool {
        static final Pool EMPTY = new Pool(-1, -1, new ArrayList<String>(), Double.MAX_VALUE);
        final int exact;
        final int near;
        final double nearDiff;
        final List<String> ids;

        Pool(int exact, int near, List<String> ids, double nearDiff) {
            this.exact = exact;
            this.near = near;
            this.ids = ids;
            this.nearDiff = nearDiff;
        }
    }

    /**
     * 发起一次海报优先链抓取（任意线程可调；内部排队串行执行）。
     *
     * @param screenRatio 屏幕比例 宽/高（如 16:9 传 1.778）
     */
    public static void fetch(String subjectId, String name, double screenRatio, Callback callback) {
        if (subjectId == null || subjectId.isEmpty() || callback == null) return;
        synchronized (QUEUE) {
            QUEUE.add(new Task(subjectId, name, screenRatio, callback));
        }
        pump();
    }

    private static void pump() {
        final Task task;
        synchronized (QUEUE) {
            if (running || QUEUE.isEmpty()) return;
            task = QUEUE.poll();
            running = true;
        }
        MAIN.post(() -> loadNextTier(task));
    }

    /** 页面链接：正式海报(R,o) → 预告海报(R,p) → 壁纸(W,a)，服务端已按尺寸排序。
     *  注意：结尾不能拼空值参数（如 appeared_personage_ids=），实测豆瓣会把
     *  "type=R" 当成它的参数值 → 页面被解析成"剧照"视图且列表为空。 */
    private static String pageUrl(Task task, int tier) {
        String base = "https://movie.douban.com/subject/" + task.subjectId
                + "/photos?start=0&sortby=size&size=a&type=";
        if (tier == 0) return base + "R&subtype=o";
        if (tier == 1) return base + "R&subtype=p";
        return base + "W&subtype=a";
    }

    private static void loadNextTier(final Task task) {
        if (task.finished) return;
        if (task.tier > 2) {
            finish(task, null);
            return;
        }
        final int tier = task.tier;
        String url = pageUrl(task, tier);
        task.polling = false;

        // 同任务跨级复用 WebView：挑战 Cookie 已热，避免每级重过 sec.douban.com 挑战
        // （实测每级新建时预告页挑战放行可达 40s+，直接超时）
        WebView wv = task.webView;
        if (wv == null) {
            wv = createWebView();
            task.webView = wv;
            wv.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String finUrl) {
                    // 挑战页(sec.douban.com)会自动重定向回目标页；仅目标页才开始提取
                    if (finUrl != null && finUrl.contains("movie.douban.com/subject/")
                            && finUrl.contains("/photos")) {
                        pollExtract(task, view, task.pageDeadline, task.tier);
                    }
                }
            });
        }
        final WebView fv = wv;
        final long deadline = SystemClock.uptimeMillis() + PAGE_TIMEOUT_MS;
        task.pageDeadline = deadline;

        wv.loadUrl(url);
        // 看门狗：每 2s 检查一次 deadline（evaluateJavascript 回调卡死时也能保证超时推进）
        final Runnable[] watchdog = new Runnable[1];
        watchdog[0] = () -> {
            if (task.finished || task.webView != fv || task.tier != tier) return;
            if (SystemClock.uptimeMillis() >= deadline) {
                LogWriter.w("hero poster timeout tier" + tier + " " + task.name);
                advanceTier(task, tier);
            } else {
                MAIN.postDelayed(watchdog[0], 2000);
            }
        };
        MAIN.postDelayed(watchdog[0], 2000);
    }

    /** 轮询注入提取脚本，直到拿到列表或页超时；拿到则后台扫池。 */
    private static void pollExtract(final Task task, final WebView wv,
                                    final long deadline, final int tier) {
        if (task.finished || task.webView != wv) return;
        if (task.polling) return;
        task.polling = true;
        if (SystemClock.uptimeMillis() > deadline) {
            LogWriter.w("hero poster timeout tier" + tier + " " + task.name);
            advanceTier(task, tier);
            return;
        }
        wv.evaluateJavascript(EXTRACT_JS, value -> {
            task.polling = false;
            // WebView 跨级复用：tier 不一致说明是上一级的迟到回调，丢弃
            if (task.finished || task.webView != wv || task.tier != tier) return;
            String json = unquote(value);
            if (json != null && json.length() > 4) {
                final String payload = json;
                IO.execute(() -> {
                    Pool pool = scanPool(payload, task.screenRatio, tier, task.name);
                    MAIN.post(() -> onPoolReady(task, tier, pool));
                });
                return;
            }
            MAIN.postDelayed(() -> pollExtract(task, wv, deadline, tier), POLL_INTERVAL_MS);
        });
    }

    /** 该级超时/失败：按"池空"走统一决策树（等价于该级无候选）。WebView 保留复用。 */
    private static void advanceTier(Task task, int doneTier) {
        if (task.finished) return;
        onPoolReady(task, doneTier, Pool.EMPTY);
    }

    /**
     * 跨级决策树（主线程）：每级池到手后按流程图推进。
     * 完全匹配优先；"最接近"候选跨级暂存（oNear/pNear），预告完全匹配可覆盖 oNear。
     * WebView 跨级复用后旧级扫描可能迟到：tier 不一致的一律丢弃。
     */
    private static void onPoolReady(Task task, int tier, Pool pool) {
        if (task.finished || task.tier != tier) return;
        String exact = pool.exact >= 0 ? largeUrl(pool.ids.get(pool.exact)) : null;
        String near = pool.near >= 0 ? largeUrl(pool.ids.get(pool.near)) : null;
        if (tier == 0) {
            if (exact != null) {
                finish(task, exact);
                return;
            }
            // 正式海报无完全匹配：暂存最接近（池空则 null），查预告海报
            task.oNear = near;
            task.oNearDiff = pool.nearDiff;
            task.tier = 1;
            loadNextTier(task);
        } else if (tier == 1) {
            if (exact != null) {
                finish(task, exact);
                return;
            }
            if (task.oNear != null) {
                // 正式/预告都无完全匹配：取与屏幕比例更接近者（同差保持正式海报 oNear）
                if (pool.near >= 0 && pool.nearDiff < task.oNearDiff) {
                    finish(task, near);
                } else {
                    finish(task, task.oNear);
                }
                return;
            }
            // 正式海报池为空：暂存预告最接近，继续查壁纸
            task.pNear = near;
            task.pNearDiff = pool.nearDiff;
            task.tier = 2;
            loadNextTier(task);
        } else {
            if (exact != null) {
                finish(task, exact);
            } else if (task.pNear != null) {
                // 壁纸/预告都无完全匹配：取更接近者（同差保持预告 pNear）
                if (pool.near >= 0 && pool.nearDiff < task.pNearDiff) {
                    finish(task, near);
                } else {
                    finish(task, task.pNear);
                }
            } else if (near != null) {
                finish(task, near);
            } else {
                finish(task, null);
            }
        }
    }

    private static void finish(Task task, String url) {
        if (task.finished) return;
        task.finished = true;
        destroyWebView(task);
        synchronized (QUEUE) {
            running = false;
        }
        try {
            task.callback.onResult(url);
        } catch (Throwable ignored) {
        }
        pump();
    }

    private static void destroyWebView(Task task) {
        final WebView wv = task.webView;
        task.webView = null;
        if (wv != null) {
            MAIN.post(() -> {
                try {
                    wv.stopLoading();
                    wv.loadUrl("about:blank");
                    wv.destroy();
                } catch (Throwable ignored) {
                }
            });
        }
    }

    /** DOM 提取脚本：photo 列表 → [[m缩略图URL, data-id], ...]（服务端已按尺寸排序）。 */
    private static final String EXTRACT_JS =
            "(function(){try{var r=[];var ls=document.querySelectorAll('.article li[data-id]');"
                    + "for(var i=0;i<ls.length;i++){var li=ls[i];var im=li.querySelector('img');"
                    + "r.push([im?im.src:'',li.getAttribute('data-id')]);}"
                    + "return JSON.stringify(r);}catch(e){return '[]';}})()";

    /** evaluateJavascript 返回值是 JSON 字符串字面量，需先去引号转义。 */
    private static String unquote(String value) {
        if (value == null || value.length() < 2) return null;
        try {
            return new org.json.JSONTokener(value).nextValue().toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 扫池（IO 线程）：解析 [mUrl, id] 列表 → 并发解码全部 m 图边界 → 计算完全匹配/最接近下标。
     * 完全匹配：|r−R|/R ≤ 2%，取页面序第一（尺寸序=最大主视觉）；
     * 最接近：|r−R| 最小，同差保持靠前者（严格小于才替换）。
     */
    private static Pool scanPool(String json, double screenRatio, int tier, String name) {
        List<String> mUrls = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONArray it = arr.optJSONArray(i);
                if (it == null || it.length() < 2) continue;
                String mUrl = it.optString(0, "");
                String id = it.optString(1, "");
                if (mUrl.isEmpty() || id.isEmpty()) continue;
                mUrls.add(mUrl);
                ids.add(id);
            }
        } catch (Throwable t) {
            LogWriter.w("hero poster scan parse fail tier" + tier + " " + name + " " + t);
            return Pool.EMPTY;
        }
        int n = mUrls.size();
        if (n == 0) return Pool.EMPTY;
        final int[][] dims = new int[n][];
        CountDownLatch latch = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final String u = mUrls.get(i);
            DECODE.execute(() -> {
                try {
                    dims[idx] = decodeBounds(u);
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            // 总预算 20s：未完成者按失败跳过（6 并发下极端罕见）
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        int exact = -1;
        int near = -1;
        double nearDiff = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int[] d = dims[i];
            if (d == null || d[0] <= 0 || d[1] <= 0) continue;
            double r = (double) d[0] / d[1];
            double diff = Math.abs(r - screenRatio);
            boolean isExact = diff / screenRatio <= EXACT_TOLERANCE;
            boolean isNear = diff < nearDiff;
            if (exact < 0 && isExact) exact = i;
            if (isNear) {
                nearDiff = diff;
                near = i;
            }
        }
        return new Pool(exact, near, ids, nearDiff);
    }

    /** data-id → l 大图直链（含防盗链后缀）。 */
    private static String largeUrl(String id) {
        return "https://img3.doubanio.com/view/photo/l/public/p" + id + ".jpg"
                + "@User-Agent=" + UA.randomOne() + "@Referer=https://www.douban.com/";
    }

    /** 下载 m 缩略图并解码边界（仅取宽高，不生成 Bitmap）。失败返回 null。 */
    private static int[] decodeBounds(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", DESKTOP_UA);
            conn.setRequestProperty("Referer", "https://www.douban.com/");
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            byte[] bytes = bos.toByteArray();
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
            return new int[]{opts.outWidth, opts.outHeight};
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) try {
                conn.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static WebView createWebView() {
        WebView wv = new WebView(App.getInstance());
        WebSettings st = wv.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setUserAgentString(DESKTOP_UA);
        // 页面图片不加载：提取仅需 src 字符串，省流量提速
        st.setBlockNetworkImage(true);
        st.setLoadsImagesAutomatically(false);
        wv.setWillNotDraw(true);
        try {
            wv.measure(1280, 720);
            wv.layout(0, 0, 1280, 720);
        } catch (Throwable ignored) {
        }
        return wv;
    }
}
