package com.github.gcd888.guoju.util;

import com.github.gcd888.guoju.bean.Movie;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.StringCallback;
import com.lzy.okgo.model.Response;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 红果短剧官网热播榜（hongguoduanju.com/rank/hot-drama）。两层解析方案：
 *
 * 方案 1（主）：SSR 数据管道 payload——页面内嵌 data-fn-name="mergeLoaderData" 脚本，
 * 路由数据为 [route, [{ key, routerDataFnArgs: ["<payload JSON 字符串>"] }]]，payload 含
 * isSuccess + rankList + pagination。条目字段：seriesId/title/cover/heatText/statusTags/
 * favoriteText/likeText/tags/description。引号闭合正则三级提取：merge → legacy "r" → 通用扫描。
 *
 * 方案 2（兜底）：DOM 内容锚点宽松解析——以剧名 h2 为条目边界、热度匹配 "8038万热度"
 * 文本模式、类型取"含 ≥2 个短 span 的段落"（span 间 SVG 图标自动忽略）、简介取较长纯文本
 * p 段落。完全忽略 class 名与属性转义形态，对服务端差异化响应（属性值内含原始引号导致
 * 方案 1 引号闭合正则全灭）与数据管道改版免疫。
 *
 * 热播列表条目已含展示所需的全部字段（名称/热度/类型/简介），无需进详情页。
 * {@link #parse(String)} 为纯 JVM 逻辑（jsoup + gson 均纯 Java），JUnit 可直接验证（HongguoRankTest）。
 */
public class HongguoRank {
    private static final String SITE = "https://hongguoduanju.com";
    private static final String HOT_DRAMA_ROUTE = "hot-drama";
    // 浏览器 UA 池随机（与点播豆瓣同策略）：官网是给浏览器看的 SSR 页面，浏览器身份最稳；
    // 设备日志证实 App Cronet UA 会被服务端按指纹/UA 差异化响应（返回属性值含原始引号的
    // B 形态页面导致解析失败），浏览器 UA 桌面实测拿到的页面结构一致且可解析
    private static final String REQUEST_TAG = "hongguo_rank";
    // 属性提取引号自适应（2026-10-23 设备落盘页证实）：服务端对 data-fn-args 会混用双引号（值内引号
    // 全部 &quot; 转义）与单引号两种包裹形态，固定双引号正则在单引号页面上全灭 → 误入 DOM 兜底。
    // 反向引用 \1 保证开闭引号配对；值内不含未转义的同型引号，惰性匹配到首个闭合定界符即整值。
    // DOTALL：值可能含换行（资源/响应均可能出现），`.` 必须跨行。
    private static final Pattern MERGE_PATTERN = Pattern.compile(
            "data-fn-name=[\"']mergeLoaderData[\"'][^>]*data-fn-args=([\"'])(.+?)\\1", Pattern.DOTALL);
    private static final Pattern LEGACY_PATTERN = Pattern.compile(
            "data-fn-name=[\"']r[\"'][^>]*data-fn-args=([\"'])(.+?)\\1", Pattern.DOTALL);
    private static final Pattern ARGS_PATTERN =
            Pattern.compile("data-fn-args=([\"'])(.+?)\\1", Pattern.DOTALL);
    private static final Pattern NUMERIC_ENTITY_PATTERN = Pattern.compile("&#(\\d+);");
    private static final Pattern HEX_ENTITY_PATTERN = Pattern.compile("&#x([0-9a-fA-F]+);");
    // DOM 锚点（方案 2 兜底）："8038万热度" 文本模式 / 详情链接 series_id / 翻页链接 /
    // 条目 li 数值段（评分9.0 / 8038万热度）
    private static final Pattern HEAT_TEXT_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?(?:万|亿)?)热度");
    private static final Pattern SCORE_TEXT_PATTERN = Pattern.compile("评分(\\d+(?:\\.\\d+)?)");
    private static final Pattern SERIES_ID_PATTERN = Pattern.compile("series_id=(\\d+)");
    private static final Pattern PAGE_LINK_PATTERN = Pattern.compile("[?&]page=(\\d+)");

    /** 解析结果：热播列表 + 总页数 + 解析路径（via="payload"=方案1正则 / "dom"=方案2锚点兜底）。 */
    public static class RankResult {
        public final List<Movie.Video> list;
        public final int totalPages;
        public final String via;

        RankResult(List<Movie.Video> list, int totalPages, String via) {
            this.list = list;
            this.totalPages = totalPages;
            this.via = via;
        }
    }

    /** 热播榜回调：onSuccess 回调在主线程。 */
    public interface Callback {
        void onSuccess(List<Movie.Video> list, int totalPages);

        void onError(String msg);
    }

    public static void loadHotDrama(int page, final Callback callback) {
        String url = SITE + "/rank/" + HOT_DRAMA_ROUTE + (page > 1 ? "?page=" + page : "");
        OkGo.<String>get(url)
                .tag(REQUEST_TAG)
                .headers("User-Agent", UA.randomOne())
                .headers("Accept-Language", "zh-CN,zh;q=0.9")
                .execute(new StringCallback() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            RankResult result = parse(response.body());
                            // 解析路径日志：via=payload（方案1正则）/ dom（方案2锚点兜底）。
                            // 走兜底时把原始 HTML 落盘（日志同目录），可离线核实站点形态与锚点偏差
                            String rawNote = "";
                            if ("dom".equals(result.via)) {
                                String path = LogWriter.saveRaw(
                                        "hongguo_page_" + System.currentTimeMillis() + ".html", response.body());
                                rawNote = " rawPage=" + path;
                            }
                            LogWriter.i("hongguo rank parsed via=" + result.via
                                    + " items=" + result.list.size() + " pages=" + result.totalPages + rawNote);
                            if (callback != null) callback.onSuccess(result.list, result.totalPages);
                        } catch (Throwable th) {
                            // 两方案全失败：同样落盘原始 HTML 供离线排查
                            String path = LogWriter.saveRaw(
                                    "hongguo_page_failed_" + System.currentTimeMillis() + ".html", response.body());
                            LogWriter.e("hongguo rank parse failed: " + th.getMessage() + " rawPage=" + path);
                            if (callback != null) callback.onError("热播数据解析失败");
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        LogWriter.e("hongguo rank request failed: " + response.code());
                        if (callback != null) callback.onError("热播数据请求失败");
                    }
                });
    }

    public static void cancel() {
        try {
            OkGo.getInstance().cancelTag(REQUEST_TAG);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 纯 JVM 解析（零 Android 依赖，可单测）：HTML → 兜底链提取 payload → 条目转 Video。
     * 失败抛 IllegalStateException，message 携带诊断信息（htmlLen/特征串命中情况），
     * 便于设备端日志定位站点结构变化。
     */
    public static RankResult parse(String html) {
        if (html == null || html.isEmpty()) {
            throw new IllegalStateException("rank html empty");
        }
        // 方案 1：正则提取 SSR 数据管道 payload（mergeLoaderData → legacy r → 通用 data-fn-args 扫描）
        JsonObject payload = extractPayload(html);
        if (payload != null && payload.has("isSuccess") && payload.get("isSuccess").getAsBoolean()
                && payload.has("rankList") && payload.get("rankList").isJsonArray()) {
            return fromPayload(payload, "payload");
        }
        // 方案 2 兜底：DOM 内容锚点宽松解析（剧名 h2 边界，忽略 class 与属性转义形态）——
        // 服务端差异化响应（属性值含原始引号）/数据管道改版时，可见内容锚点依然稳定
        RankResult loose = parseByDomAnchors(html);
        if (loose != null && !loose.list.isEmpty()) {
            return loose;
        }
        throw new IllegalStateException("rank parse failed (htmlLen=" + html.length()
                + " hasMerge=" + html.contains("mergeLoaderData")
                + " hasArgs=" + html.contains("data-fn-args")
                + " hasRankList=" + html.contains("rankList")
                + " payload=" + (payload == null ? "null" : "invalid")
                + " domItems=" + (loose == null ? -1 : loose.list.size()) + ")");
    }

    /** 方案 1 数据落地：payload → 条目列表 + 分页。 */
    private static RankResult fromPayload(JsonObject payload, String via) {
        JsonArray rankList = payload.getAsJsonArray("rankList");
        List<Movie.Video> list = new ArrayList<>();
        for (JsonElement element : rankList) {
            if (!element.isJsonObject()) continue;
            Movie.Video video = rankItemToVideo(element.getAsJsonObject());
            if (video != null) list.add(video);
        }
        int totalPages = 1;
        if (payload.has("pagination") && payload.get("pagination").isJsonObject()) {
            JsonObject pagination = payload.getAsJsonObject("pagination");
            totalPages = pagination.has("totalPages") && !pagination.get("totalPages").isJsonNull()
                    ? Math.max(1, pagination.get("totalPages").getAsInt()) : 1;
        }
        return new RankResult(list, totalPages, via);
    }

    /**
     * 方案 2 兜底：按 DOM 内容锚点宽松解析，完全忽略 class 名与数据管道结构：
     * 条目切分以剧名 h2 为边界；热度匹配 "8038万热度" 文本模式；类型取"含 ≥2 个短 span
     * 的段落"（span 间夹的 SVG 分隔图标自动忽略）；简介取较长的纯文本 p 段落；
     * 海报取条目内首个 img；总页数取页面内 page=N 链接最大值。
     * 服务端转义差异/属性截断不影响可见 DOM，此路径对方案 1 的失败形态免疫。
     */
    private static RankResult parseByDomAnchors(String html) {
        try {
            Document doc = Jsoup.parse(html);
            List<Movie.Video> list = new ArrayList<>();
            for (Element h2 : doc.select("h2")) {
                String name = h2.text().trim();
                if (name.isEmpty()) continue;
                Element entry = findEntryContainer(h2);
                if (entry == null) continue;
                Movie.Video video = new Movie.Video();
                video.name = name;
                video.id = seriesIdOf(h2, entry);
                Elements imgs = entry.select("img[src]");
                video.pic = imgs.isEmpty() ? null : imgs.first().attr("src");
                video.note = buildMeta(entry);
                video.content = longestParagraph(entry);
                list.add(video);
            }
            if (list.isEmpty()) return null;
            return new RankResult(list, domTotalPages(doc), "dom");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 条目容器定位：从 h2 逐层上溯，取最后一个"恰好包含 1 个 h2"的祖先（条目根，
     * 含封面 img 与全部文本内容）；继续上溯进入列表容器时 h2 数 >1 即停。
     */
    private static Element findEntryContainer(Element h2) {
        Element cur = h2;
        Element best = null;
        for (int i = 0; i < 8 && cur.parent() != null; i++) {
            cur = cur.parent();
            if (cur.select("h2").size() != 1) break; // 越过条目根进入列表容器
            best = cur;
        }
        return best;
    }

    /** 条目 id：h2 id 形如 rank-title-{seriesId} 优先；否则找条目内 series_id= 链接；兜底用剧名。 */
    private static String seriesIdOf(Element h2, Element entry) {
        String h2Id = h2.id();
        if (h2Id.startsWith("rank-title-")) return h2Id.substring("rank-title-".length());
        for (Element a : entry.select("a[href]")) {
            Matcher m = SERIES_ID_PATTERN.matcher(a.attr("href"));
            if (m.find()) return m.group(1);
        }
        return h2.text().trim();
    }

    /** meta 行（与方案 1 排版一致：热度 · 题材类型 · 影视类型 · 评分，评分缺失 → 暂无评分）。 */
    private static String buildMeta(Element entry) {
        StringBuilder meta = new StringBuilder();
        String heat = liByPattern(entry, HEAT_TEXT_PATTERN);
        if (!heat.isEmpty()) meta.append(heat);
        String tags = categoriesOf(entry);
        if (!tags.isEmpty()) meta = meta.length() > 0 ? meta.append(" · ").append(tags) : meta.append(tags);
        if (meta.length() > 0) meta.append(" · ");
        meta.append("短剧");
        String score = liByPattern(entry, SCORE_TEXT_PATTERN);
        meta.append(" · ").append(score.isEmpty() ? "暂无评分" : score + " 分");
        return meta.toString();
    }

    /** 条目 li 文本按正则提取数值段（评分9.0 → 9.0 / 8038万热度 → 8038万），无命中回查整容器文本。 */
    private static String liByPattern(Element entry, Pattern pattern) {
        for (Element li : entry.select("li")) {
            Matcher m = pattern.matcher(li.text());
            if (m.find()) return m.group(1);
        }
        Matcher m = pattern.matcher(entry.text());
        return m.find() ? m.group(1) : "";
    }

    /** 类型段：找"含 ≥2 个短 span 的 p 段落"，span 文本以 / 拼接（夹在 span 间的 svg 自动忽略）。 */
    private static String categoriesOf(Element entry) {
        for (Element p : entry.select("p")) {
            Elements spans = p.select("span");
            if (spans.size() < 2) continue;
            StringBuilder sb = new StringBuilder();
            boolean allShort = true;
            for (Element span : spans) {
                String t = span.text().trim();
                if (t.isEmpty() || t.length() > 6) {
                    allShort = false;
                    break;
                }
                if (sb.length() > 0) sb.append("/");
                sb.append(t);
            }
            if (allShort && sb.length() > 0) return sb.toString();
        }
        return "";
    }

    /** 简介：容器内最长的 p 纯文本（≥15 字才采信，排除热度/类型等短段落）。 */
    private static String longestParagraph(Element entry) {
        Element best = null;
        int bestLen = 0;
        for (Element p : entry.select("p")) {
            String t = p.text().trim();
            if (t.length() > bestLen) {
                bestLen = t.length();
                best = p;
            }
        }
        return bestLen >= 15 && best != null ? best.text().trim() : null;
    }

    /** 总页数：页面内 page=N 链接的最大值（缺失按 1）。 */
    private static int domTotalPages(Document doc) {
        int totalPages = 1;
        for (Element a : doc.select("a[href]")) {
            Matcher m = PAGE_LINK_PATTERN.matcher(a.attr("href"));
            if (m.find()) {
                try {
                    totalPages = Math.max(totalPages, Integer.parseInt(m.group(1)));
                } catch (Throwable ignored) {
                }
            }
        }
        return totalPages;
    }

    /** 兜底链提取 payload：mergeLoaderData → legacy r → 通用扫描 data-fn-args。 */
    private static JsonObject extractPayload(String html) {
        Matcher merge = MERGE_PATTERN.matcher(html);
        if (merge.find()) {
            JsonObject payload = parseRouterPayloadSafely(decodeHtml(merge.group(2)));
            if (payload != null) return payload;
        }
        Matcher legacy = LEGACY_PATTERN.matcher(html);
        if (legacy.find()) {
            try {
                JsonArray args = JsonParser.parseString(decodeHtml(legacy.group(2))).getAsJsonArray();
                if (args.size() >= 3 && args.get(2).isJsonObject()) {
                    return args.get(2).getAsJsonObject();
                }
            } catch (Throwable ignored) {
            }
        }
        // 通用兜底：不筛函数名，扫全部 data-fn-args，取含 rankList 的 payload（优先 isSuccess=true）；
        // 无 rankList 命中时退回首个可解析的 router payload，让上层校验报出精确的 invalid 诊断
        JsonObject fallback = null;
        Matcher args = ARGS_PATTERN.matcher(html);
        while (args.find()) {
            JsonObject candidate = parseRouterPayloadSafely(decodeHtml(args.group(2)));
            if (candidate == null) continue;
            if (candidate.has("rankList") && candidate.get("rankList").isJsonArray()) {
                boolean success = candidate.has("isSuccess") && candidate.get("isSuccess").getAsBoolean();
                if (success) return candidate;
                if (fallback == null) fallback = candidate;
            } else if (fallback == null) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    /**
     * mergeLoaderData/通用结构：args = [route, [{ key, routerDataFnArgs: ["<payload JSON>"] }]]。
     * 逐 entry 解析 routerDataFnArgs[0] 为 payload JSON，返回首个成功对象。
     */
    private static JsonObject parseRouterPayloadSafely(String argsJson) {
        try {
            JsonArray args = JsonParser.parseString(argsJson).getAsJsonArray();
            if (args.size() >= 2 && args.get(1).isJsonArray()) {
                return extractRouterPayload(args.get(1).getAsJsonArray());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 在 [{ key, routerDataFnArgs: [...] }] 列表中提取首个 payload JSON 对象。 */
    private static JsonObject extractRouterPayload(JsonArray entries) {
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) continue;
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("routerDataFnArgs") && obj.get("routerDataFnArgs").isJsonArray()) {
                JsonArray routerArgs = obj.getAsJsonArray("routerDataFnArgs");
                if (routerArgs.size() > 0 && routerArgs.get(0).isJsonPrimitive()) {
                    try {
                        return JsonParser.parseString(routerArgs.get(0).getAsString()).getAsJsonObject();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static Movie.Video rankItemToVideo(JsonObject item) {
        String id = firstString(item, "seriesId", "series_id", "id");
        String name = firstString(item, "title", "seriesTitle", "series_name");
        if (id == null || id.isEmpty() || name == null || name.isEmpty()) return null;
        Movie.Video video = new Movie.Video();
        video.id = id;
        video.name = name;
        video.pic = firstString(item, "cover", "seriesCover", "series_cover");
        // meta 行（对齐点播排版 PRD：热度 · 题材类型 · 影视类型 · 评分；单行）：
        //   例："8038万 · 宫廷/古代/AI剧 · 短剧 · 暂无评分"
        //   payload 无评分字段 → 评分段固定"暂无评分"
        String heat = firstString(item, "heatText");
        String tags = tagsString(item);
        StringBuilder meta = new StringBuilder();
        if (heat != null && !heat.isEmpty()) meta.append(heat.replace("热度", ""));
        if (!tags.isEmpty()) meta = meta.length() > 0 ? meta.append(" · ").append(tags) : meta.append(tags);
        if (meta.length() > 0) meta.append(" · ");
        meta.append("短剧 · 暂无评分");
        video.note = meta.toString();
        // 简介直接来自热播列表条目的 description 字段（页面已含，无需进详情页）
        video.content = firstString(item, "description", "intro", "seriesIntro");
        return video;
    }

    /** 默认取 "tags" 字段的标签拼接。 */
    private static String tagsString(JsonObject item) {
        return tagsStringFromField(item, "tags");
    }

    /**
     * 从指定字段名提取标签拼接字符串。
     * 支持数组（字符串数组或含 name 字段的对象数组）和原始字符串。
     * 用于 tags（宫廷/古代/AI剧）和 statusTags（新剧）等。
     */
    private static String tagsStringFromField(JsonObject item, String field) {
        if (!item.has(field) || item.get(field).isJsonNull()) return "";
        JsonElement tags = item.get(field);
        StringBuilder builder = new StringBuilder();
        if (tags.isJsonArray()) {
            for (JsonElement tag : tags.getAsJsonArray()) {
                String name = tag.isJsonPrimitive() ? tag.getAsString()
                        : (tag.isJsonObject() && tag.getAsJsonObject().has("name")
                        ? tag.getAsJsonObject().get("name").getAsString() : null);
                if (name != null && !name.isEmpty()) {
                    if (builder.length() > 0) builder.append("/");
                    builder.append(name);
                }
            }
        } else if (tags.isJsonPrimitive()) {
            builder.append(tags.getAsString());
        }
        return builder.toString();
    }

    private static String firstString(JsonObject item, String... keys) {
        for (String key : keys) {
            if (item.has(key) && !item.get(key).isJsonNull()) {
                try {
                    String value = item.get(key).getAsString();
                    if (value != null && !value.isEmpty()) return value;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
     * SSR 属性值实体还原（先 JSON.parse 前调用）。
     * 顺序：数字/十六进制实体 → 命名实体 → &amp; 最后
     * （数字正则匹配不到 "&amp;#34;"，顺序无回滚风险；&amp; 必须最后防二次解码）。
     */
    static String decodeHtml(String value) {
        if (value == null) return "";
        String result = decodeNumericEntities(value);
        return result
                .replace("&quot;", "\"")
                .replace("&#x2F;", "/")
                .replace("&#47;", "/")
                .replace("&#x27;", "'")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    /** 数字（&#34;）与十六进制（&#x22;）实体还原，非法码点原样保留。 */
    private static String decodeNumericEntities(String value) {
        if (!value.contains("&#")) return value;
        Matcher numeric = NUMERIC_ENTITY_PATTERN.matcher(value);
        StringBuffer sb = new StringBuffer();
        while (numeric.find()) {
            numeric.appendReplacement(sb, Matcher.quoteReplacement(codePointToString(parseCodePoint(numeric.group(1), 10))));
        }
        numeric.appendTail(sb);
        Matcher hex = HEX_ENTITY_PATTERN.matcher(sb.toString());
        sb = new StringBuffer();
        while (hex.find()) {
            hex.appendReplacement(sb, Matcher.quoteReplacement(codePointToString(parseCodePoint(hex.group(1), 16))));
        }
        hex.appendTail(sb);
        return sb.toString();
    }

    private static int parseCodePoint(String digits, int radix) {
        try {
            return Integer.parseInt(digits, radix);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String codePointToString(int codePoint) {
        if (codePoint <= 0) return "";
        try {
            return new String(Character.toChars(codePoint));
        } catch (Throwable ignored) {
            return "";
        }
    }
}
