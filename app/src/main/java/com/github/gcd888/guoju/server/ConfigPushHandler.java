package com.github.gcd888.guoju.server;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import com.github.gcd888.guoju.api.ConfigRepo;
import com.github.gcd888.guoju.bean.ConfigItem;
import com.github.gcd888.guoju.event.RefreshEvent;
import com.github.gcd888.guoju.ui.activity.HomeActivity;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 扫码推送配置处理：与 ConfigDialog.onConfirmClicked 同一链路——
 * 入配置列表（重名复用原条目）→ ConfigRepo.scanTree 全树扫描判型 → rebuildRouteRows 路由表整组落库（幂等）→
 * applyUnsetModules 自动应用未设置模块（已设置不覆盖）→ 有应用则整套重载首页。
 * 类型自动识别（sites/lives/dramas/musics），不区分点播/直播。
 * 推送开始/结束发 TYPE_PUSH_CONFIG_START/DONE 事件，前台配置弹窗/大码弹窗显示解析遮罩反馈。
 * 回调来自 NanoHTTPD 线程，Hawk/UI/启动 Activity 统一主线程执行。
 */
public final class ConfigPushHandler {

    /** 推送结果（TYPE_PUSH_CONFIG_DONE 事件载荷）。 */
    public static class PushResult {
        public final boolean ok;          // 路由表落库成功（rows > 0）
        public final String configUrl;    // 推送的配置链接（弹窗据此刷新/选中）
        public final List<String> applied; // 自动应用的模块类型名（空=未应用）

        public PushResult(boolean ok, String configUrl, List<String> applied) {
            this.ok = ok;
            this.configUrl = configUrl;
            this.applied = applied;
        }
    }

    private static final String TAG_PERF = "PushPerf"; // 排查用，验证后移除

    private ConfigPushHandler() {
    }

    /** 手机页面推送的配置入口（name 可空，TV 端自动命名）。 */
    public static void handle(final Context context, final String name, final String url) {
        if (TextUtils.isEmpty(url)) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                push(context.getApplicationContext(), name == null ? "" : name.trim(), url.trim());
            }
        });
    }

    private static void push(final Context context, String name, final String url) {
        final long pushStart = android.os.SystemClock.elapsedRealtime(); // 排查用，验证后移除
        android.util.Log.d(TAG_PERF, "收到推送 name=" + name + " url=" + url);
        if (TextUtils.isEmpty(name)) {
            name = ConfigRepo.genConfigName(); // 自定义配置N
        }
        if (!ConfigRepo.addConfigItem(name, url)) {
            // 已存在的链接：复用原条目名，重新扫描整组覆盖路由表
            for (ConfigItem item : ConfigRepo.getConfigList()) {
                if (item.url != null && item.url.equals(url)) {
                    name = item.name;
                    break;
                }
            }
        }
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_PUSH_CONFIG_START, url));
        ConfigRepo.scanTree(url, new ConfigRepo.ScanCallback() {
            @Override
            public void onDone(Map<String, ConfigRepo.ScanNode> nodes, List<String> order) {
                long routeStart = android.os.SystemClock.elapsedRealtime(); // 排查用，验证后移除
                int rows = ConfigRepo.rebuildRouteRows(url, nodes, order);
                List<String> applied = rows > 0
                        ? ConfigRepo.applyUnsetModules(url)
                        : new ArrayList<String>();
                android.util.Log.d(TAG_PERF, "路由表落库+自动应用 耗时="
                        + (android.os.SystemClock.elapsedRealtime() - routeStart) + "ms 行数=" + rows);
                // 先发事件（弹窗隐藏遮罩/大码弹窗关闭/列表刷新），再 Toast/重载
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_PUSH_CONFIG_DONE,
                        new PushResult(rows > 0, url, applied)));
                android.util.Log.d(TAG_PERF, "推送全流程完成 总耗时="
                        + (android.os.SystemClock.elapsedRealtime() - pushStart) + "ms 成功=" + (rows > 0));
                if (rows == 0) {
                    // 链接不可达/格式不识别：条目保留，重进弹窗点击该条目会自动重扫
                    toast(context, "配置检测失败，请检查链接");
                    return;
                }
                if (applied.isEmpty()) {
                    toast(context, "推送成功");
                    return;
                }
                toast(context, "推送成功，已自动应用「" + TextUtils.join("、", applied) + "」源");
                Intent intent = new Intent(context, HomeActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                context.startActivity(intent);
            }
        });
    }

    private static void toast(Context context, String msg) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
    }
}
