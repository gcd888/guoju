package com.github.gcd888.guoju.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.NonNull;

import com.github.gcd888.guoju.bean.Movie;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HongguoRank.parse 纯 JVM 单测（零 Android 依赖）：
 * 覆盖兜底链（mergeLoaderData / legacy r / 通用 data-fn-args 扫描）、
 * 实体解码、payload 校验、条目字段容错。样本见 test/resources/hongguo/。
 *
 * 构造嵌套 payload 的 HTML 用 Gson 序列化整层 args（payload 作为 JSON 字符串内嵌时
 * 引号转义由 Gson 处理，与真实站点 SSR 编码一致），再用 attrEscape 做 HTML 属性编码。
 */
public class HongguoRankTest {

    private static final Gson GSON = new Gson();

    private String resource(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/hongguo/" + name)) {
            assertNotNull("missing test resource: " + name, in);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    /** 最小 HTML 壳：正则只需 data-fn-name="mergeLoaderData"...data-fn-args="..."。 */
    private String mergeShell(String rawAttrValue) {
        return "<html><script async=\"\" data-script-src=\"modern-run-window-fn\""
                + " data-fn-name=\"mergeLoaderData\" data-fn-args=\"" + rawAttrValue + "\"></script></html>";
    }

    /**
     * 构造 merge 结构的 data-fn-args 属性值：args = [route, [{routerDataFnArgs: ["<payload>"]}]]。
     * payload JSON 作为字符串内嵌，整层由 Gson 序列化（内部引号转义正确）。
     * 注意：Gson 会跳过匿名内部类字段，routerDataFnArgs 必须用普通 ArrayList。
     */
    private String argsAttr(String fnRoute, JsonObject payload) {
        return attrEscape(buildArgsJson(fnRoute, payload));
    }

    private String buildArgsJson(String fnRoute, JsonObject payload) {
        List<String> routerArgs = new ArrayList<>();
        routerArgs.add(GSON.toJson(payload));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("key", "content");
        entry.put("routerDataFnName", "p");
        entry.put("routerDataFnArgs", routerArgs);
        List<Object> entries = new ArrayList<>();
        entries.add(entry);
        List<Object> args = new ArrayList<>();
        args.add(fnRoute);
        args.add(entries);
        return GSON.toJson(args);
    }

    /** 把 JSON 按 HTML 属性编码（& " ' < >），与站点 data-fn-args 编码一致。 */
    private static String attrEscape(@NonNull String json) {
        return json.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("'", "&#39;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 构造单个 data-fn-args script 标签（fnName 可为任意值，含属性顺序参数）。 */
    private String scriptTag(String argsAttrValue, String fnName, boolean argsFirst) {
        String nameAttr = "data-fn-name=\"" + fnName + "\"";
        String argsAttr = "data-fn-args=\"" + argsAttrValue + "\"";
        return "<script async=\"\" " + (argsFirst ? argsAttr + " " + nameAttr : nameAttr + " " + argsAttr)
                + "></script>";
    }

    private JsonObject payloadOf(String json) {
        return GSON.fromJson(json, JsonObject.class);
    }

    /** 主链路：真实站点页面截取的 mergeLoaderData 属性值（20 条 + totalPages=5）。 */
    @Test
    public void parseRealMergeSample() throws IOException {
        HongguoRank.RankResult result = HongguoRank.parse(mergeShell(resource("merge_args_attr.txt")));
        assertEquals(20, result.list.size());
        assertEquals(5, result.totalPages);
        assertEquals("payload", result.via);
        Movie.Video first = result.list.get(0);
        assertNotNull(first.id);
        assertNotNull(first.name);
        assertTrue(first.name.length() > 0);
        assertNotNull(first.pic);
        assertTrue(first.pic.startsWith("https://"));
        // meta 新格式（PRD）：热度 · 题材类型 · 影视类型 · 评分
        assertEquals("8038万 · 宫廷/古代/AI剧 · 短剧 · 暂无评分", first.note);
        assertNotNull(first.content);
    }

    /** 兜底链 1：legacy data-fn-name="r"（args[2] 为 payload）。 */
    @Test
    public void parseLegacyFallback() throws IOException {
        HongguoRank.RankResult result = HongguoRank.parse(resource("legacy.html"));
        assertEquals(2, result.list.size()); // 4 条中缺 seriesId / 缺 title 的两条被跳过
        assertEquals(3, result.totalPages);
        assertEquals("测试剧A", result.list.get(0).name);
        // meta 新格式：热度 · 题材类型 · 影视类型 · 评分（payload 无评分字段 → 暂无评分）
        assertEquals("1234万 · 宫廷/古代 · 短剧 · 暂无评分", result.list.get(0).note);
    }

    /** 兜底链 2：未知函数名 + 属性顺序反转，走通用 data-fn-args 扫描。 */
    @Test
    public void parseGenericFallback() throws IOException {
        HongguoRank.RankResult result = HongguoRank.parse(resource("generic.html"));
        assertEquals(2, result.list.size());
        assertEquals(3, result.totalPages);
    }

    /** 通用兜底优先 isSuccess=true 的 payload（多个候选时）。 */
    @Test
    public void genericFallbackPrefersSuccessPayload() {
        String bad = "{\"isSuccess\":false,\"rankList\":[{\"seriesId\":\"9\",\"title\":\"失效剧\"}],\"pagination\":{\"totalPages\":1}}";
        String good = "{\"isSuccess\":true,\"rankList\":[{\"seriesId\":\"1\",\"title\":\"剧\"}],\"pagination\":{\"totalPages\":2}}";
        String html = scriptTag(argsAttr("a/page", payloadOf(bad)), "a", true)
                + scriptTag(argsAttr("b/page", payloadOf(good)), "b", true);
        HongguoRank.RankResult result = HongguoRank.parse(html);
        assertEquals(1, result.list.size());
        assertEquals("剧", result.list.get(0).name);
        assertEquals(2, result.totalPages);
    }

    /** 空 html 报错。 */
    @Test
    public void parseEmptyHtmlThrows() {
        assertParseError("", "rank html empty");
        assertParseError(null, "rank html empty");
    }

    /** 无 payload 且无 DOM 锚点报错：诊断信息含 htmlLen 与特征串命中情况。 */
    @Test
    public void parseNoPayloadThrowsWithDiagnostics() {
        String html = "<html>normal page without rank data-fn-args</html>";
        try {
            HongguoRank.parse(html);
            fail("should throw");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("rank parse failed"));
            assertTrue(e.getMessage(), e.getMessage().contains("htmlLen=" + html.length()));
            assertTrue(e.getMessage(), e.getMessage().contains("hasMerge=false"));
        }
    }

    /** isSuccess=false 报错（方案 1 业务失败 + 页面无 DOM 锚点）。 */
    @Test
    public void parseBadStatusThrows() throws IOException {
        try {
            HongguoRank.parse(resource("badstatus.html"));
            fail("should throw");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("rank parse failed"));
        }
    }

    /** payload 缺 rankList：方案 1 失败，页面无 DOM 锚点时报错。 */
    @Test
    public void parseMissingRankListThrows() {
        String html = scriptTag(argsAttr("m/page", payloadOf("{\"isSuccess\":true}")), "m", true);
        try {
            HongguoRank.parse(html);
            fail("should throw");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("rank parse failed"));
        }
    }

    /** 缺 pagination：totalPages 按兜底 = 1。 */
    @Test
    public void parseWithoutPaginationDefaultsToOne() {
        String payload = "{\"isSuccess\":true,\"rankList\":[{\"seriesId\":\"7\",\"title\":\"剧C\"}]}";
        String html = scriptTag(argsAttr("m/page", payloadOf(payload)), "m", true);
        HongguoRank.RankResult result = HongguoRank.parse(html);
        assertEquals(1, result.list.size());
        assertEquals(1, result.totalPages);
    }

    /** pagination.totalPages 为 null/0：按 1 并夹底。 */
    @Test
    public void parsePaginationClampedToMinimumOne() {
        String p1 = "{\"isSuccess\":true,\"rankList\":[],\"pagination\":{\"totalPages\":null}}";
        assertEquals(1, HongguoRank.parse(scriptTag(argsAttr("m/page", payloadOf(p1)), "m", true)).totalPages);
        String p2 = "{\"isSuccess\":true,\"rankList\":[],\"pagination\":{\"totalPages\":0}}";
        assertEquals(1, HongguoRank.parse(scriptTag(argsAttr("m/page", payloadOf(p2)), "m", true)).totalPages);
    }

    /** 实体解码：数字/十六进制实体 + 命名实体（解码顺序 = 数字→命名→&amp;）。 */
    @Test
    public void decodeHtmlNumericAndNamedEntities() {
        assertEquals("\"/'<>&", HongguoRank.decodeHtml("&#34;&#x2F;&#39;&lt;&gt;&amp;"));
        assertEquals("《剧名》", HongguoRank.decodeHtml("&#12298;剧名&#12299;"));
        assertEquals("https://a.b/c?x=1&y=2", HongguoRank.decodeHtml("https:&#x2F;&#x2F;a.b&#x2F;c?x=1&amp;y=2"));
    }

    /** &amp; 最后解码：字面 &quot; 文本（&amp;quot;）不应被二次解码成引号。 */
    @Test
    public void decodeHtmlAmpersandLast() {
        // &amp;quot; 是字面文本 "&quot;"，解码后应为 &quot; 而非 "
        assertEquals("&quot;", HongguoRank.decodeHtml("&amp;quot;"));
        // &amp;#34; 数字正则匹配不到（&# 被打断），保持字面
        assertEquals("&#34;", HongguoRank.decodeHtml("&amp;#34;"));
    }

    /** 条目容错：缺 seriesId/title 跳过；tags 支持字符串数组与 {name} 对象数组；meta 空段自动跳过。 */
    @Test
    public void rankItemFieldTolerance() throws IOException {
        HongguoRank.RankResult result = HongguoRank.parse(resource("legacy.html"));
        assertEquals("1234万 · 宫廷/古代 · 短剧 · 暂无评分", result.list.get(0).note);
        // 对象数组 tags（{name}）拼接
        assertEquals("999万 · 都市/甜宠 · 短剧 · 暂无评分", result.list.get(1).note);
        // 缺 id/title 的两条被跳过
        assertEquals(2, result.list.size());
    }

    /** 真实样本 payload 内含 unicode 转义（cover 用 unicode 编码的斜杠表示 /），解析后应为正常 URL。 */
    @Test
    public void payloadUnicodeEscapeHandled() throws IOException {
        HongguoRank.RankResult result = HongguoRank.parse(mergeShell(resource("merge_args_attr.txt")));
        for (Movie.Video video : result.list) {
            if (video.pic != null && video.pic.contains("byteimg.com")) {
                assertTrue(video.pic, video.pic.startsWith("https://"));
                return;
            }
        }
        fail("no byteimg cover found in real sample");
    }

    /**
     * 方案 2 兜底（真实页面、方案 1 全灭）：删掉数据管道 script 的真实页面，
     * 引号闭合正则全部失效 → DOM 内容锚点解析应得到 20 条 + 分页。
     * 同时覆盖设备日志证实的 B 形态失败场景（服务端转义差异不影响可见 DOM）。
     */
    @Test
    public void parseDomAnchorsFallbackFromRealPage() throws IOException {
        HongguoRank.RankResult result = HongguoRank.parse(resource("dom_only.html"));
        assertEquals(20, result.list.size());
        assertTrue("totalPages should follow page links", result.totalPages >= 2);
        assertEquals("dom", result.via);
        Movie.Video first = result.list.get(0);
        assertEquals("大唐憨婿之我姐姐是太子妃第四季", first.name);
        assertNotNull(first.id);
        assertTrue(first.pic != null && first.pic.startsWith("https://"));
        // meta 新格式（PRD）：DOM 有评分 li（评分9.0）→ "9.0 分"
        assertEquals("8038万 · 宫廷/古代/AI剧 · 短剧 · 9.0 分", first.note);
        assertNotNull(first.content);
        assertTrue(first.content.length() >= 15);
    }

    /**
     * 真实设备落盘页（2026-10-23）：legacy r 标签 + data-fn-args 单引号包裹形态
     * （此前固定双引号正则全灭误入 DOM 兜底），修复后方案 1 应直接命中 payload。
     */
    @Test
    public void parseRealSingleQuoteLegacySample() throws IOException {
        HongguoRank.RankResult result = HongguoRank.parse(resource("real_squote.html"));
        assertEquals("payload", result.via);
        assertEquals(20, result.list.size());
        assertEquals(5, result.totalPages);
        assertEquals("大唐憨婿之我姐姐是太子妃第四季", result.list.get(0).name);
        assertTrue(result.list.get(0).pic.startsWith("https://"));
    }

    /** 单引号属性形态的最小构造回归：legacy r + data-fn-args='...'。 */
    @Test
    public void parseLegacySingleQuoteAttr() {
        String payload = "{\"isSuccess\":true,\"rankList\":[{\"seriesId\":\"12\",\"title\":\"单引号剧\"}],\"pagination\":{\"totalPages\":4}}";
        String argsJson = "[\"rank/x/page\",\"content\"," + GSON.toJson(payloadOf(payload)) + "]";
        String html = "<html><script async data-fn-name=\"r\" data-script-src=\"modern-run-router-data-fn\""
                + " data-fn-args='" + attrEscape(argsJson) + "'></script></html>";
        HongguoRank.RankResult result = HongguoRank.parse(html);
        assertEquals("payload", result.via);
        assertEquals(1, result.list.size());
        assertEquals("单引号剧", result.list.get(0).name);
        assertEquals(4, result.totalPages);
    }

    /** 方案 2 锚点容错：迷你 DOM（忽略 class 名）——h2 边界/热度模式/短 span 段落/长 p 简介。 */
    @Test
    public void parseDomAnchorsIgnoreClassNames() {
        String html = "<html><body><div class='whatever-list'>"
                + card("888", "剧乙", "2233万热度", "古装", "权谋", "热播中",
                "这是第二部剧的足够长的简介段落用来验证条目切分是否互不串扰内容正确。")
                + card("777", "剧甲", "1234万热度", "都市", "甜宠", "热映",
                "这是一个足够长的简介段落用来验证简介锚点逻辑是否正确取到最长的纯文本段落。")
                + "</div></body></html>";
        HongguoRank.RankResult result = HongguoRank.parse(html);
        assertEquals(2, result.list.size());
        assertEquals("dom", result.via);
        Movie.Video v = result.list.get(1);
        assertEquals("剧甲", v.name);
        assertEquals("777", v.id);
        assertEquals("https://img.example/777.jpg", v.pic);
        // meta 新格式：li 提取评分/热度（收藏/点赞不再进 meta）
        assertEquals("1234万 · 都市/甜宠 · 短剧 · 9.5 分", v.note);
        assertTrue(v.content, v.content.startsWith("这是一个足够长的简介段落"));
        assertEquals("剧乙", result.list.get(0).name);
        assertEquals("2233万 · 古装/权谋 · 短剧 · 9.5 分", result.list.get(0).note);
    }

    /** 迷你条目卡片（class 名随意，验证锚点只认标签与文本内容）。 */
    private static String card(String id, String name, String heat, String tag1, String tag2,
                               String status, String desc) {
        return "<div class='card-x'>"
                + "<a href='/detail?series_id=" + id + "'><picture><img src='https://img.example/" + id + ".jpg' alt='" + name + "封面'/></picture></a>"
                + "<div class='content-x'>"
                + "<a href='/detail?series_id=" + id + "'><h2 id='rank-title-" + id + "'>" + name + "</h2></a>"
                + "<p class='m-x'><span class='i-x'><svg viewBox='0 0 2 2'></svg>" + heat + "</span></p>"
                + "<ul class='e-x'><li class='st-x'>" + status + "</li><li class='sc-x'><svg viewBox='0 0 2 2'></svg>评分9.5</li>"
                + "<li>55.5万收藏</li><li>77.7万点赞</li></ul>"
                + "<p class='c-x'><span>" + tag1 + "</span><svg viewBox='0 0 2 2'></svg><span>" + tag2 + "</span></p>"
                + "<p class='d-x'>" + desc + "</p>"
                + "</div></div>";
    }

    /** 方案 1 失败 + 无 DOM 锚点（如空壳页）：报错且诊断含 domItems=-1。 */
    @Test
    public void parseBothSchemesFailThrows() {
        String html = "<html><script>var x = 1;</script><body>no rank content</body></html>";
        try {
            HongguoRank.parse(html);
            fail("should throw");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("rank parse failed"));
            assertTrue(e.getMessage(), e.getMessage().contains("domItems=-1"));
        }
    }

    private static void assertParseError(String html, String expectedPart) {
        try {
            HongguoRank.parse(html);
            fail("should throw for input: " + html);
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(expectedPart));
        }
    }
}
