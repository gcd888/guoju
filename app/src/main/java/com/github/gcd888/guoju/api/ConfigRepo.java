package com.github.gcd888.guoju.api;

import android.text.TextUtils;

import com.github.gcd888.guoju.bean.ConfigItem;
import com.github.gcd888.guoju.bean.ConfigNodeBean;
import com.github.gcd888.guoju.data.AppDataManager;
import com.github.gcd888.guoju.data.ConfigRoute;
import com.github.gcd888.guoju.data.ConfigRouteDao;
import com.github.gcd888.guoju.server.ControlManager;
import com.github.gcd888.guoju.util.HawkConfig;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 配置弹窗数据层：配置 > 仓库 > 线路 三级钻取。
 * 判型递归：storeHouse→仓库层 / urls→线路层 / sites→终端配置（可激活）。
 * 缺失层级由 ConfigDialog 补虚拟节点；无持久拉黑（仓库/线路删除仅会话内）。
 * clan:// 重写逻辑自旧多仓模块吸收。
 */
public final class ConfigRepo {

    public interface NodeCallback {
        void onSuccess(FetchResult result);

        void onError(String msg);
    }

    /** 配置 JSON 根级数据段键（与文件字段同名）。 */
    public static final String SECTION_SITES = "sites";
    public static final String SECTION_LIVES = "lives";
    public static final String SECTION_DRAMAS = "dramas";
    public static final String SECTION_MUSICS = "musics";

    /** 判型结果：type=本 url 的层级形态，nodes=下级列表（TERMINAL 时为空）。 */
    public static class FetchResult {
        public int type;
        public List<ConfigNodeBean> nodes = new ArrayList<>();
        /** 本 url 内容实际含有的数据段（sites/lives/dramas/musics）；仓库/线路列表层无段信息时为空。 */
        public final Set<String> sections = new LinkedHashSet<>();
    }

    /** 会话缓存：url → 判型结果（含下级节点）。 */
    private static final Map<String, FetchResult> CACHE = new HashMap<>();
    /** 会话内删除的仓库/线路 url（本会话不再出现；刷新/重进弹窗恢复）。 */
    private static final List<String> SESSION_REMOVED = new ArrayList<>();

    private ConfigRepo() {
    }

    // ---------------- 一级配置列表（持久） ----------------

    public static List<ConfigItem> getConfigList() {
        ArrayList<ConfigItem> list = Hawk.get(HawkConfig.CONFIG_LIST, new ArrayList<>());
        return list == null ? new ArrayList<>() : list;
    }

    /** 新增配置；url 已存在时返回 false（提示已存在，不重复添加）。 */
    public static boolean addConfigItem(String name, String url) {
        List<ConfigItem> list = getConfigList();
        for (ConfigItem item : list) {
            if (item.url != null && item.url.equals(url)) {
                return false;
            }
        }
        list.add(new ConfigItem(name, url));
        Hawk.put(HawkConfig.CONFIG_LIST, list);
        return true;
    }

    public static ConfigItem removeConfigItem(String url) {
        List<ConfigItem> list = getConfigList();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).url != null && list.get(i).url.equals(url)) {
                ConfigItem removed = list.remove(i);
                Hawk.put(HawkConfig.CONFIG_LIST, list);
                CACHE.remove(url);
                return removed;
            }
        }
        return null;
    }

    public static String getActiveUrl() {
        return Hawk.get(HawkConfig.CONFIG_ACTIVE, "");
    }

    public static void setActiveUrl(String url) {
        Hawk.put(HawkConfig.CONFIG_ACTIVE, url == null ? "" : url);
    }

    // ---------------- 生效地址名称（url → 名称 持久化映射） ----------------

    /** 记住某生效地址的展示名（选线路时与写 API_URL 同步调用；设置页按此显示名称而非链接）。 */
    public static void rememberUrlName(String url, String name) {
        if (url == null || url.isEmpty() || name == null || name.isEmpty()) return;
        try {
            JSONObject map = new JSONObject(Hawk.get(HawkConfig.CONFIG_URL_NAMES, "{}"));
            map.put(url, name);
            Hawk.put(HawkConfig.CONFIG_URL_NAMES, map.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 生效地址展示名：持久映射 → 配置列表精确匹配 → 原 url；空 → "未配置"。 */
    public static String displayNameFor(String url) {
        if (url == null || url.isEmpty()) return "未配置";
        try {
            JSONObject map = new JSONObject(Hawk.get(HawkConfig.CONFIG_URL_NAMES, "{}"));
            String name = map.optString(url, "");
            if (!name.isEmpty()) return name;
        } catch (Throwable ignored) {
        }
        for (ConfigItem item : getConfigList()) {
            if (url.equals(item.url)) return item.name;
        }
        return url;
    }

    // ---------------- 命名 ----------------

    /** 自定义配置N：N=现有「自定义配置」最大序号+1。 */
    public static String genConfigName() {
        int max = 0;
        for (ConfigItem item : getConfigList()) {
            max = Math.max(max, seqOf(item.name, "自定义配置"));
        }
        return "自定义配置" + (max + 1);
    }

    private static int seqOf(String name, String prefix) {
        if (name == null || !name.startsWith(prefix)) {
            return 0;
        }
        try {
            return Integer.parseInt(name.substring(prefix.length()));
        } catch (Throwable th) {
            return 0;
        }
    }

    // ---------------- 会话内删除（无持久拉黑） ----------------

    public static void sessionRemove(String url) {
        if (!SESSION_REMOVED.contains(url)) {
            SESSION_REMOVED.add(url);
        }
    }

    public static void resetSession() {
        SESSION_REMOVED.clear();
    }

    // ---------------- 拉取与判型 ----------------

    /**
     * 失效整棵会话缓存子树（长按刷新配置用；含 root 自身，遍历已缓存下级）。
     * 设计语义「仓库/线路删除=会话内移除，刷新或重进弹窗后恢复」：该树内被会话删除的
     * url 一并解除拉黑，其余 url 的会话删除保留（长按刷新=恢复该树）。
     */
    public static void invalidateTree(String rootUrl) {
        List<String> pending = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        List<String> treeUrls = new ArrayList<>();
        pending.add(rootUrl);
        visited.add(rootUrl);
        while (!pending.isEmpty()) {
            String cur = pending.remove(0);
            treeUrls.add(cur);
            FetchResult cached = CACHE.remove(cur);
            if (cached == null) continue;
            for (ConfigNodeBean n : cached.nodes) {
                if (visited.add(n.url)) {
                    pending.add(n.url);
                }
            }
        }
        SESSION_REMOVED.removeAll(treeUrls);
    }

    /** 检查某 url 是否被会话删除（弹窗渲染过滤用；重开弹窗经 resetSession 恢复）。 */
    public static boolean isSessionRemoved(String url) {
        return url != null && SESSION_REMOVED.contains(url);
    }

    /** 拉取并判型（带会话缓存；SESSION_REMOVED 过滤下级节点）。 */
    public static void fetchNode(final String url, final NodeCallback callback) {
        if (TextUtils.isEmpty(url)) {
            callback.onError("链接为空");
            return;
        }
        FetchResult cached = CACHE.get(url);
        if (cached != null) {
            callback.onSuccess(applySessionFilter(cached));
            return;
        }
        OkGo.<String>get(toRequestUrl(url)).execute(new AbsCallback<String>() {
            @Override
            public void onSuccess(Response<String> response) {
                try {
                    FetchResult result = parseNodes(response.body(), url);
                    CACHE.put(url, result);
                    callback.onSuccess(applySessionFilter(result));
                } catch (Throwable th) {
                    callback.onError("解析失败: " + th.getMessage());
                }
            }

            @Override
            public String convertResponse(okhttp3.Response response) throws Throwable {
                return response.body() == null ? "" : response.body().string();
            }

            @Override
            public void onError(Response<String> response) {
                super.onError(response);
                Throwable t = response.getException();
                callback.onError("拉取失败: " + (t == null ? "网络错误" : t.getMessage()));
            }
        });
    }

    private static FetchResult applySessionFilter(FetchResult src) {
        if (SESSION_REMOVED.isEmpty() || src.nodes.isEmpty()) {
            return src;
        }
        FetchResult out = new FetchResult();
        out.type = src.type;
        out.sections.addAll(src.sections);
        for (ConfigNodeBean node : src.nodes) {
            if (!SESSION_REMOVED.contains(node.url)) {
                out.nodes.add(node);
            }
        }
        return out;
    }

    /**
     * 判型核心：storeHouse→仓库列表 / urls→线路列表 / 含任一数据段（sites/lives/dramas/musics）
     * 或 spider→终端。同时记录根级实际含有的数据段（供弹窗类别 Tab 判显隐）。
     * 纯 JVM 逻辑（不依赖 android TextUtils/Log），JUnit 可直接验证（ConfigRepoTest）。
     * 无法识别时抛 JSONException。
     */
    public static FetchResult parseNodes(String content, String fileUrl) throws org.json.JSONException {
        FetchResult result = new FetchResult();
        if (blank(content)) {
            throw new org.json.JSONException("内容为空");
        }
        JSONObject root = new JSONObject(content);
        for (String key : new String[]{SECTION_SITES, SECTION_LIVES, SECTION_DRAMAS, SECTION_MUSICS}) {
            if (root.optJSONArray(key) != null) {
                result.sections.add(key);
            }
        }
        if (root.optJSONArray("storeHouse") != null) {
            result.type = ConfigNodeBean.TYPE_WAREHOUSE;
            JSONArray array = root.optJSONArray("storeHouse");
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String sourceUrl = item.optString("sourceUrl", "").trim();
                if (blank(sourceUrl)) continue;
                String name = item.optString("sourceName", "").trim();
                if (blank(name)) name = sourceUrl;
                result.nodes.add(new ConfigNodeBean(name, fixClanLink(fileUrl, sourceUrl),
                        ConfigNodeBean.TYPE_WAREHOUSE, false));
            }
            return result;
        }
        if (root.optJSONArray("urls") != null) {
            result.type = ConfigNodeBean.TYPE_LINE;
            JSONArray array = root.optJSONArray("urls");
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String url = item.optString("url", "").trim();
                if (blank(url)) continue;
                String name = item.optString("name", "").trim();
                if (blank(name)) name = url;
                result.nodes.add(new ConfigNodeBean(name, fixClanLink(fileUrl, url),
                        ConfigNodeBean.TYPE_LINE, false));
            }
            return result;
        }
        // 终端判定放宽：只要有任一数据段即视为可激活的完整配置（只含直播/短剧/音乐的链接也合法）
        if (!result.sections.isEmpty() || !blank(root.optString("spider", ""))) {
            result.type = ConfigNodeBean.TYPE_TERMINAL;
            return result;
        }
        throw new org.json.JSONException("无法识别的配置格式");
    }

    /** 空串判定（纯 JVM，避免 android.text.TextUtils 使 parseNodes 无法单测）。 */
    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ---------------- clan 协议 ----------------

    /** clan:// 文件转 http 请求地址；本地 localhost 由 ControlManager 提供。 */
    public static String toRequestUrl(String url) {
        if (url != null && url.startsWith("clan://")) {
            if (url.startsWith("clan://localhost/")) {
                return url.replace("clan://localhost/", ControlManager.get().getAddress(true) + "file/");
            }
            String host = url.substring(7);
            int end = host.indexOf('/');
            return "http://" + host.substring(0, end) + "/file/" + host.substring(end + 1);
        }
        return url;
    }

    /** clan://localhost/x 相对多仓文件主机重写（影视仓 clanContentFix 参照）。 */
    private static String fixClanLink(String fileUrl, String link) {
        if (link == null || !link.startsWith("clan://localhost/")) {
            return link;
        }
        String httpFileUrl = fileUrl != null && fileUrl.startsWith("clan://") ? toRequestUrl(fileUrl) : fileUrl;
        String fix;
        if (httpFileUrl != null && httpFileUrl.contains("/file/")) {
            fix = httpFileUrl.substring(0, httpFileUrl.indexOf("/file/") + 6);
        } else if (httpFileUrl != null && httpFileUrl.startsWith("http")) {
            int idx = httpFileUrl.indexOf('/', httpFileUrl.indexOf("//") + 2);
            fix = idx < 0 ? httpFileUrl + "/" : httpFileUrl.substring(0, idx + 1);
        } else {
            fix = ControlManager.get().getAddress(true) + "file/";
        }
        return link.replace("clan://localhost/", fix);
    }

    // ---------------- 配置树扫描 + 路由表（持久：类型|配置|仓库|线路 谱系） ----------------

    /** 扫描结果节点：url → 判型/名称/数据段/父节点。type 未知（拉取失败）时为 -1。 */
    public static class ScanNode {
        public String url;
        public String name;
        public int type = -1;
        public final Set<String> sections = new LinkedHashSet<>();
        /** 父节点 url；根为 null。 */
        public String parentUrl;
    }

    public interface ScanCallback {
        /** 全部节点扫描完成（成功或失败均回调一次，主线程）。 */
        void onDone(Map<String, ScanNode> nodes, List<String> order);
    }

    /**
     * 深度优先扫描配置树（深度≤4），产出全节点判型与父子谱系。
     * 完成判定用对称计数（入队前 +1、回调必 -1），无静默失败口；结果经主线程 post 回调。
     * 节点拉取失败保留为 type=-1 的叶子（其谱系已知，只是无分类）。
     */
    public static void scanTree(String rootUrl, final ScanCallback callback) {
        final Map<String, ScanNode> nodes = new LinkedHashMap<>();
        final List<String> order = new ArrayList<>();
        final AtomicInteger pending = new AtomicInteger(1);
        scanUrl(rootUrl, null, null, 0, nodes, order, pending, callback);
    }

    private static void scanUrl(final String url, final String name, final String parentUrl, final int depth,
                                final Map<String, ScanNode> nodes, final List<String> order,
                                final AtomicInteger pending, final ScanCallback callback) {
        if (depth > 4 || url == null || url.isEmpty()) {
            finishScan(pending, nodes, order, callback);
            return;
        }
        ScanNode node = new ScanNode();
        node.url = url;
        node.name = name;
        node.parentUrl = parentUrl;
        nodes.put(url, node);
        order.add(url);
        fetchNode(url, new NodeCallback() {
            @Override
            public void onSuccess(FetchResult result) {
                node.type = result.type;
                node.sections.addAll(result.sections);
                if (depth < 4) {
                    for (ConfigNodeBean child : result.nodes) {
                        pending.incrementAndGet();
                        scanUrl(child.url, child.name, url, depth + 1, nodes, order, pending, callback);
                    }
                }
                finishScan(pending, nodes, order, callback);
            }

            @Override
            public void onError(String msg) {
                // 失败节点保留：谱系已入树，只是无判型无分类
                finishScan(pending, nodes, order, callback);
            }
        });
    }

    /** 计数归零 = 全树扫描完成；统一主线程 post，规避同步递归在回调里重入。 */
    private static void finishScan(final AtomicInteger pending, final Map<String, ScanNode> nodes,
                                   final List<String> order, final ScanCallback callback) {
        if (pending.decrementAndGet() != 0) {
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                callback.onDone(nodes, order);
            }
        });
    }

    /** 数据段 → 模块类型（0=点播 1=直播 2=短剧 3=音乐）；未知段返回 null。 */
    public static Integer sectionToType(String section) {
        if (SECTION_SITES.equals(section)) return 0;
        if (SECTION_LIVES.equals(section)) return 1;
        if (SECTION_DRAMAS.equals(section)) return 2;
        if (SECTION_MUSICS.equals(section)) return 3;
        return null;
    }

    /**
     * 扫描结果落路由表：先删该配置旧行再按树拓扑序插入（幂等，行序=DFS 先序）。
     * 一条终端线路含 N 个数据段 → N 行；类型单值。
     * 行序必须按树拓扑重构（兄弟顺序=JSON 列表序）：扫描完成序受网络时序影响 ≠ 列表序，
     * 否则"自动应用/默认选中"会落到第二个仓库的第一条线路。
     * 虚拟层规则：根即终端 → 仓库/线路地址=配置地址；线路直接挂配置下（LINE 型根）→ 虚拟仓库地址=配置地址；
     * 套娃中间层 → 仓库=线路的直接父节点。
     */
    public static int rebuildRouteRows(String rootUrl, Map<String, ScanNode> nodes, List<String> order) {
        ScanNode root = nodes.get(rootUrl);
        String rootName = root != null && root.name != null && !root.name.trim().isEmpty()
                ? root.name.trim() : "自定义配置";
        List<ConfigRoute> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (root != null && root.type == ConfigNodeBean.TYPE_TERMINAL) {
            // 根即终端：仓库/线路均为虚拟层，单条线路
            for (String section : root.sections) {
                Integer type = sectionToType(section);
                if (type == null) continue;
                rows.add(newRouteRow(type, rootName, rootUrl, "自定义仓库", rootUrl, rootName, rootUrl, now));
            }
        } else {
            for (ScanNode n : canonicalOrder(rootUrl, nodes, order)) {
                if (n.type != ConfigNodeBean.TYPE_TERMINAL) continue;
                ScanNode parent = n.parentUrl == null ? null : nodes.get(n.parentUrl);
                String lineName = n.name == null || n.name.trim().isEmpty() ? n.url : n.name.trim();
                String lineUrl = n.url;
                String warehouseName;
                String warehouseUrl;
                if (n.parentUrl != null && n.parentUrl.equals(rootUrl)
                        && root != null && root.type == ConfigNodeBean.TYPE_LINE) {
                    // LINE 型根：线路直接挂配置下 → 虚拟仓库
                    warehouseName = "自定义仓库";
                    warehouseUrl = rootUrl;
                } else if (parent != null) {
                    warehouseName = parent.name == null || parent.name.trim().isEmpty() ? n.parentUrl : parent.name.trim();
                    warehouseUrl = n.parentUrl;
                } else {
                    warehouseName = "自定义仓库";
                    warehouseUrl = rootUrl;
                }
                for (String section : n.sections) {
                    Integer type = sectionToType(section);
                    if (type == null) continue;
                    rows.add(newRouteRow(type, rootName, rootUrl, warehouseName, warehouseUrl, lineName, lineUrl, now));
                }
            }
        }
        ConfigRouteDao dao = AppDataManager.get().getConfigRouteDao();
        dao.deleteByConfigUrl(rootUrl);
        if (!rows.isEmpty()) {
            dao.insertAll(rows);
        }
        return rows.size();
    }

    /** 组装一行路由（避免重复字段赋值散落）。 */
    private static ConfigRoute newRouteRow(int type, String configName, String configUrl,
                                           String warehouseName, String warehouseUrl,
                                           String lineName, String lineUrl, long now) {
        ConfigRoute row = new ConfigRoute();
        row.type = type;
        row.configName = configName;
        row.configUrl = configUrl;
        row.warehouseName = warehouseName;
        row.warehouseUrl = warehouseUrl;
        row.lineName = lineName;
        row.lineUrl = lineUrl;
        row.updateTime = now;
        return row;
    }

    /**
     * 树拓扑序（DFS 先序）：兄弟相对顺序=各节点 JSON 列表序（扫描时同步入队，天然保序），
     * 与网络完成时序无关。
     */
    private static List<ScanNode> canonicalOrder(String rootUrl, Map<String, ScanNode> nodes, List<String> order) {
        Map<String, List<ScanNode>> childrenMap = new LinkedHashMap<>();
        Set<String> linked = new HashSet<>();
        for (String url : order) {
            ScanNode n = nodes.get(url);
            if (n == null || n.parentUrl == null || !linked.add(url)) continue;
            List<ScanNode> list = childrenMap.get(n.parentUrl);
            if (list == null) {
                list = new ArrayList<>();
                childrenMap.put(n.parentUrl, list);
            }
            list.add(n);
        }
        List<ScanNode> out = new ArrayList<>();
        dfsChildren(rootUrl, childrenMap, out);
        return out;
    }

    private static void dfsChildren(String url, Map<String, List<ScanNode>> childrenMap, List<ScanNode> out) {
        List<ScanNode> children = childrenMap.get(url);
        if (children == null) return;
        for (ScanNode child : children) {
            out.add(child);
            dfsChildren(child.url, childrenMap, out);
        }
    }
}
