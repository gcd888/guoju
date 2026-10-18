package com.github.tvbox.osc.api;

import android.text.TextUtils;

import com.github.tvbox.osc.bean.WarehouseBean;
import com.github.tvbox.osc.util.HawkConfig;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 多仓数据层：管理推送链接列表与影视仓 storeHouse 解析。
 * 层级：仓 > 接口 > 站源；推送链接(多仓文件) → storeHouse[] 仓 → 每仓即一份接口配置。
 * 支持 http(s) 与 clan:// 协议（clan 本地条目按多仓文件所在主机重写）。
 */
public final class WarehouseRepo {

    /** 左栏推送链接列表操作回调。 */
    public interface WarehouseCallback {
       public void onSuccess(List<WarehouseBean> list);

        void onError(String msg);
    }

    /** 解析结果过滤黑名单（用户已删除的仓条目）。 */
    private static List<WarehouseBean> filterBlacklist(List<WarehouseBean> list) {
        List<String> blacklist = getWarehouseBlacklist();
        if (blacklist.isEmpty()) {
            return list;
        }
        List<WarehouseBean> result = new ArrayList<>();
        for (WarehouseBean bean : list) {
            if (!blacklist.contains(bean.sourceUrl)) {
                result.add(bean);
            }
        }
        return result;
    }

    private WarehouseRepo() {
    }

    public static List<String> getPushSources() {
        ArrayList<String> list = Hawk.get(HawkConfig.PUSH_SOURCE_LIST, new ArrayList<>());
        return list == null ? new ArrayList<>() : list;
    }

    public static void savePushSources(List<String> sources) {
        Hawk.put(HawkConfig.PUSH_SOURCE_LIST, sources == null ? new ArrayList<String>() : new ArrayList<>(sources));
    }

    public static void addPushSource(String url) {
        List<String> list = getPushSources();
        if (!list.contains(url)) {
            list.add(url);
            savePushSources(list);
        }
    }

    public static void removePushSource(String url) {
        List<String> list = getPushSources();
        list.remove(url);
        savePushSources(list);
    }

    /** 黑名单：用户手动删除的仓条目（按配置链接记录），解析后过滤。 */
    public static List<String> getWarehouseBlacklist() {
        ArrayList<String> list = Hawk.get(HawkConfig.WAREHOUSE_BLACKLIST, new ArrayList<>());
        return list == null ? new ArrayList<>() : list;
    }

    public static void addWarehouseBlacklist(String sourceUrl) {
        List<String> list = getWarehouseBlacklist();
        if (!list.contains(sourceUrl)) {
            list.add(sourceUrl);
            Hawk.put(HawkConfig.WAREHOUSE_BLACKLIST, new ArrayList<>(list));
        }
    }

    /** 拉取并解析一个推送链接下的多仓列表（重拉整棵资源树入口）。 */
    public static void fetchStoreHouse(String url, final WarehouseCallback callback) {
        if (TextUtils.isEmpty(url)) {
            callback.onError("链接为空");
            return;
        }
        String requestUrl = toRequestUrl(url);
        OkGo.<String>get(requestUrl).execute(new AbsCallback<String>() {
            @Override
            public void onSuccess(Response<String> response) {
                try {
                    List<WarehouseBean> list = filterBlacklist(parseStoreHouse(url, response.body()));
                    if (list.isEmpty()) {
                        callback.onError("未找到 storeHouse 多仓数据");
                    } else {
                        callback.onSuccess(list);
                    }
                } catch (Throwable th) {
                    callback.onError("多仓解析失败: " + th.getMessage());
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

    /**
     * 解析影视仓 storeHouse 格式：
     * {"storeHouse":[{"sourceName":"名","sourceUrl":"配置链接"}]}
     */
    public static List<WarehouseBean> parseStoreHouse(String fileUrl, String content) throws org.json.JSONException {
        List<WarehouseBean> result = new ArrayList<>();
        if (TextUtils.isEmpty(content)) {
            return result;
        }
        JSONObject root = new JSONObject(content);
        JSONArray array = root.optJSONArray("storeHouse");
        if (array == null) {
            return result;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String name = item.optString("sourceName", "").trim();
            String sourceUrl = item.optString("sourceUrl", "").trim();
            if (TextUtils.isEmpty(sourceUrl)) {
                continue;
            }
            if (TextUtils.isEmpty(name)) {
                name = sourceUrl;
            }
            result.add(new WarehouseBean(name, fixClanLink(fileUrl, sourceUrl)));
        }
        return result;
    }

    /** clan:// 文件转 http 请求地址；本地 localhost 由 ControlManager 提供。 */
    public static String toRequestUrl(String url) {
        if (url != null && url.startsWith("clan://")) {
            if (url.startsWith("clan://localhost/")) {
                return url.replace("clan://localhost/", com.github.tvbox.osc.server.ControlManager.get().getAddress(true) + "file/");
            }
            String host = url.substring(7);
            int end = host.indexOf('/');
            return "http://" + host.substring(0, end) + "/file/" + host.substring(end + 1);
        }
        return url;
    }

    /**
     * 重写仓条目里的 clan 链接：clan://localhost/x 相对多仓文件主机，
     * 参照影视仓 clanContentFix 逻辑，替换为其主机根前缀。
     */
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
            fix = com.github.tvbox.osc.server.ControlManager.get().getAddress(true) + "file/";
        }
        return link.replace("clan://localhost/", fix);
    }
}
