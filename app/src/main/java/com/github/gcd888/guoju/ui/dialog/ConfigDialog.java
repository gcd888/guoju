package com.github.gcd888.guoju.ui.dialog;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.gcd888.guoju.R;
import com.github.gcd888.guoju.api.ConfigRepo;
import com.github.gcd888.guoju.bean.ConfigItem;
import com.github.gcd888.guoju.bean.ConfigNodeBean;
import com.github.gcd888.guoju.data.AppDataManager;
import com.github.gcd888.guoju.data.ConfigRoute;
import com.github.gcd888.guoju.event.RefreshEvent;
import com.github.gcd888.guoju.server.ConfigPushHandler;
import com.github.gcd888.guoju.server.ControlManager;
import com.github.gcd888.guoju.ui.activity.HomeActivity;
import com.github.gcd888.guoju.ui.adapter.ConfigAdapter;
import com.github.gcd888.guoju.ui.tv.QRCodeGen;
import com.github.gcd888.guoju.util.HawkConfig;
import com.github.gcd888.guoju.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 配置弹窗（Finder 三栏式）：左=配置 / 中=仓库 / 右=线路，三列联动列表，**全部由持久路由表驱动**。
 * 路由表（Room config_route，见 ConfigRoute）：一行=一条线路在「类型×配置×仓库」下的谱系，类型单值；
 * 弹窗打开/切换 Tab/选中钻取均为本地查表渲染（零探测零网络）——分类只在推送/长按刷新时经
 * ConfigRepo.scanTree 全树扫描落表一次。选中态用列内 url 比对；重开弹窗自动恢复上次选中（Hawk 持久化）。
 * 类别 Tab（点播/直播/短剧/音乐）：在哪个 Tab 下选线路就只生效到对应模块（写各模块 Hawk 键）。
 * 推送自动应用（FR-I-07）：扫描落表完成后确定性执行——未设置模块自动应用树内第一条对应线路，
 * 已设置不覆盖（旧版异步接力竞态已移除，见 scanAndRebuild/autoApplyFromTable）。
 * 每行删除（配置持久删+路由表删 / 仓库线路会话删）；长按配置=整树重扫覆盖路由表，当前生效线路（模块键）不碰；
 * 返回键直接关闭。
 */
public class ConfigDialog extends BaseDialog {

    private final ConfigAdapter configAdapter = new ConfigAdapter();
    private final ConfigAdapter warehouseAdapter = new ConfigAdapter();
    private final ConfigAdapter lineAdapter = new ConfigAdapter();

    /** 三列数据（三栏并列展示层级，无层级栈） */
    private final List<ConfigNodeBean> configNodes = new ArrayList<>();
    private final List<ConfigNodeBean> warehouseNodes = new ArrayList<>();
    private final List<ConfigNodeBean> lineNodes = new ArrayList<>();

    /** 选中状态（Hawk 持久化，进程重启后弹窗重开仍恢复）。 */
    private static class Sel {
        static JSONObject read() {
            try {
                return new JSONObject(Hawk.get(HawkConfig.CONFIG_SEL, "{}"));
            } catch (Throwable th) {
                return new JSONObject();
            }
        }

        static String config() { return read().optString("config", ""); }

        static String warehouse() { return read().optString("warehouse", ""); }

        static String line() { return read().optString("line", ""); }

        static void set(String k, String v) {
            JSONObject obj = read();
            try {
                obj.put(k, v == null ? "" : v);
            } catch (Throwable ignored) {
            }
            Hawk.put(HawkConfig.CONFIG_SEL, obj.toString()); // 存 String：Hawk 取回按默认值 String 走，存 JSONObject 会因类型还原导致取值 CCE 被吞
        }

        /** 三列选中一次清空。 */
        static void clear() {
            Hawk.put(HawkConfig.CONFIG_SEL, new JSONObject().toString());
        }
    }

    /** 模块定位（设置页四地址入口进入时传入）：非空则 show 后默认展开该模块当前配置的中/右列。 */
    private final String locateUrl;

    private TextView tvTip;
    private EditText etName;
    private EditText etUrl;

    /** 类别 Tab：在哪个类别下选线路就只生效到对应模块。 */
    public static final int TYPE_VOD = 0;
    public static final int TYPE_LIVE = 1;
    public static final int TYPE_DRAMA = 2;
    public static final int TYPE_MUSIC = 3;

    private final TextView[] tabViews = new TextView[4];
    private int activeType = TYPE_VOD;

    /** 当前配置的路由表行（持久表 → 会话渲染源，行序=扫描序）。 */
    private final List<ConfigRoute> routeRows = new ArrayList<>();
    /** 中/右列渲染数据（由路由表按当前 Tab 过滤生成）。 */
    private final List<ConfigNodeBean> allWarehouses = new ArrayList<>();
    private final List<ConfigNodeBean> allLines = new ArrayList<>();

    /** 配置树扫描进行中：完成前不做默认激活/自动应用（避免半棵树误应用）。 */
    private boolean scanning = false;

    public ConfigDialog(@NonNull @NotNull Context context) {
        this(context, "", TYPE_VOD);
    }

    public ConfigDialog(@NonNull @NotNull Context context, String locateUrl) {
        this(context, locateUrl, TYPE_VOD);
    }

    public ConfigDialog(@NonNull @NotNull Context context, String locateUrl, int defaultType) {
        super(context);
        this.locateUrl = locateUrl == null ? "" : locateUrl;
        this.activeType = (defaultType >= TYPE_VOD && defaultType <= TYPE_MUSIC) ? defaultType : TYPE_VOD;
        setContentView(R.layout.dialog_config);
        setCanceledOnTouchOutside(false);
        // 订阅扫码推送事件：开始/结束控制解析遮罩（dismiss 时注销，防泄漏）
        EventBus.getDefault().register(this);
        setOnDismissListener(dialog -> EventBus.getDefault().unregister(ConfigDialog.this));

        tvTip = findViewById(R.id.tvTip);
        etName = findViewById(R.id.etName);
        etUrl = findViewById(R.id.etUrl);

        tabViews[TYPE_VOD] = findViewById(R.id.tabVod);
        tabViews[TYPE_LIVE] = findViewById(R.id.tabLive);
        tabViews[TYPE_DRAMA] = findViewById(R.id.tabDrama);
        tabViews[TYPE_MUSIC] = findViewById(R.id.tabMusic);
        for (int i = 0; i < tabViews.length; i++) {
            final int type = i;
            tabViews[i].setOnClickListener(v -> selectType(type));
        }

        initColumn(findViewById(R.id.rvConfig), configAdapter);
        initColumn(findViewById(R.id.rvWarehouse), warehouseAdapter);
        initColumn(findViewById(R.id.rvLine), lineAdapter);

        configAdapter.setOnItemClickListener((a, view, position) -> onConfigClicked(configAdapter.getItem(position)));
        configAdapter.setOnItemLongClickListener((a, view, position) -> onConfigLongClicked(configAdapter.getItem(position)));
        configAdapter.setOnItemChildClickListener((a, view, position) -> {
            if (view.getId() == R.id.ivDelete) onDeleteConfig(configAdapter.getItem(position));
        });
        warehouseAdapter.setOnItemClickListener((a, view, position) -> onWarehouseClicked(warehouseAdapter.getItem(position)));
        warehouseAdapter.setOnItemChildClickListener((a, view, position) -> {
            if (view.getId() == R.id.ivDelete) onDeleteWarehouse(warehouseAdapter.getItem(position));
        });
        lineAdapter.setOnItemClickListener((a, view, position) -> onLineClicked(lineAdapter.getItem(position)));
        lineAdapter.setOnItemChildClickListener((a, view, position) -> {
            if (view.getId() == R.id.ivDelete) onDeleteLine(lineAdapter.getItem(position));
        });

        findViewById(R.id.ivClose).setOnClickListener(v -> dismiss());
        findViewById(R.id.btnConfirm).setOnClickListener(v -> onConfirmClicked());
        initPushQR();

        ConfigRepo.resetSession();
        for (ConfigItem item : ConfigRepo.getConfigList()) {
            configNodes.add(new ConfigNodeBean(item.name, item.url, ConfigNodeBean.TYPE_CONFIG, false));
        }
        configAdapter.setNewData(configNodes);
        updateTabs();
    }

    /** 打开恢复链入口：须在 show 后触发。恢复全部走路由表本地渲染（零网络）。 */
    @Override
    public void show() {
        super.show();
        restoreLastSelection();
        // 模块定位（PRD 4.3）：设置页四地址入口进入时，默认选中该模块当前配置项并展开中/右列
        if (!locateUrl.isEmpty()) {
            for (ConfigNodeBean node : configNodes) {
                if (safeUrl(node.url).equals(locateUrl)) {
                    onConfigClicked(node);
                    break;
                }
            }
        }
        // 无历史选中（首次打开/持久选中已清）：默认选中首个配置，表驱动落到首个仓库/首条线路
        if (Sel.config().isEmpty() && !configNodes.isEmpty()) {
            onConfigClicked(configNodes.get(0));
        }
    }

    /** 扫码推送区（右上常驻）：生成推送服务二维码 + 展示访问地址；扫码或点击/确认键弹大码弹窗。 */
    private void initPushQR() {
        String address = ControlManager.get().getAddress(false) + "config.html";
        TextView tvPushUrl = findViewById(R.id.tvPushUrl);
        tvPushUrl.setText(address);
        ImageView ivQRCode = findViewById(R.id.ivQRCode);
        ivQRCode.setImageBitmap(QRCodeGen.generateBitmap(address,
                AutoSizeUtils.mm2px(getContext(), 80), AutoSizeUtils.mm2px(getContext(), 80), 2));
        // 点击/确认键弹大码弹窗：大二维码 + 完整地址 + 推送提示
        findViewById(R.id.flQRCode).setOnClickListener(v -> new QrCodeDialog(getContext(), address).show());
    }

    /** 扫码推送事件：开始朦胧转圈遮罩；结束隐藏遮罩并刷新左列（自动选中刚推送的配置）。 */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPushEvent(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_PUSH_CONFIG_START) {
            findViewById(R.id.flPushLoading).setVisibility(View.VISIBLE);
        } else if (event.type == RefreshEvent.TYPE_PUSH_CONFIG_DONE) {
            findViewById(R.id.flPushLoading).setVisibility(View.GONE);
            if (event.obj instanceof ConfigPushHandler.PushResult) {
                ConfigPushHandler.PushResult result = (ConfigPushHandler.PushResult) event.obj;
                if (result.ok && !TextUtils.isEmpty(result.configUrl)) {
                    rebuildConfigColumn(result.configUrl); // 左列刷新并选中新推送配置，中/右列渲染路由表
                }
            }
        }
    }

    /** 初始化单列列表（布局 + 适配器；点击/长按/删除由各列单独挂接）。 */
    private void initColumn(TvRecyclerView rv, ConfigAdapter adapter) {
        rv.setLayoutManager(new V7LinearLayoutManager(getContext(), V7LinearLayoutManager.VERTICAL, false));
        rv.setAdapter(adapter);
    }

    // ---------------- 三列选中与状态 ----------------

    /** 切换类别 Tab：路由表按类型重过滤（本地渲染，零网络）。 */
    private void selectType(int type) {
        activeType = type;
        updateTabs();
        renderColumnsFromTable();
    }

    /**
     * 类别 Tab（点播/直播/短剧/音乐）：四类常显——即使当前配置树暂无对应数据段也保留入口，
     * 切换后三列按段过滤（无该段时线路列为空，属正常态）。
     */
    private void updateTabs() {
        for (int i = 0; i < tabViews.length; i++) {
            tabViews[i].setVisibility(View.VISIBLE);
            tabViews[i].setSelected(i == activeType);
        }
        updateTip();
    }

    /** 读当前配置的路由表行（持久真源 → 会话渲染源）。 */
    private void loadRouteRows(String configUrl) {
        routeRows.clear();
        routeRows.addAll(AppDataManager.get().getConfigRouteDao().getByConfigUrl(configUrl));
    }

    /**
     * 中/右列由路由表渲染（零网络）：
     * 中列 = 该配置下当前类型有线路的去重仓库组（保持扫描序，会话删除组过滤）；
     * 仓库/线路选中态：Sel 命中则恢复高亮，否则默认第一条（仅高亮，激活只发生在推送自动应用/用户点击）。
     */
    private void renderColumnsFromTable() {
        allWarehouses.clear();
        Set<String> seenWarehouse = new LinkedHashSet<>();
        for (ConfigRoute row : routeRows) {
            if (row.type != activeType) continue;
            if (ConfigRepo.isSessionRemoved(row.warehouseUrl)) continue;
            if (seenWarehouse.add(row.warehouseUrl)) {
                allWarehouses.add(new ConfigNodeBean(row.warehouseName, row.warehouseUrl,
                        ConfigNodeBean.TYPE_WAREHOUSE, row.warehouseUrl.equals(Sel.config())));
            }
        }
        warehouseNodes.clear();
        warehouseNodes.addAll(allWarehouses);
        warehouseAdapter.setNewData(warehouseNodes);
        String selWarehouse = Sel.warehouse();
        boolean warehouseHit = false;
        for (ConfigNodeBean w : warehouseNodes) {
            if (w.url != null && w.url.equals(selWarehouse)) {
                warehouseAdapter.setSelectedUrl(selWarehouse);
                warehouseHit = true;
                break;
            }
        }
        if (!warehouseHit) {
            String first = warehouseNodes.isEmpty() ? "" : safeUrl(warehouseNodes.get(0).url);
            Sel.set("warehouse", first);
            warehouseAdapter.setSelectedUrl(first);
        }
        fillRightFromTable();
        updateTip();
    }

    /** 右列由路由表渲染（选中仓库 + 当前类型的去重线路），并恢复/默认线路高亮。 */
    private void fillRightFromTable() {
        allLines.clear();
        String warehouseUrl = Sel.warehouse();
        Set<String> seenLine = new LinkedHashSet<>();
        for (ConfigRoute row : routeRows) {
            if (row.type != activeType) continue;
            if (row.warehouseUrl == null || !row.warehouseUrl.equals(warehouseUrl)) continue;
            if (ConfigRepo.isSessionRemoved(row.lineUrl)) continue;
            if (seenLine.add(row.lineUrl)) {
                allLines.add(new ConfigNodeBean(row.lineName, row.lineUrl,
                        ConfigNodeBean.TYPE_TERMINAL, row.lineUrl.equals(Sel.config())));
            }
        }
        lineNodes.clear();
        lineNodes.addAll(allLines);
        lineAdapter.setNewData(lineNodes);
        String selLine = Sel.line();
        boolean lineHit = false;
        for (ConfigNodeBean l : lineNodes) {
            if (l.url != null && l.url.equals(selLine)) {
                lineAdapter.setSelectedUrl(selLine);
                lineHit = true;
                break;
            }
        }
        if (!lineHit && !lineNodes.isEmpty()) {
            String first = safeUrl(lineNodes.get(0).url);
            Sel.set("line", first);
            lineAdapter.setSelectedUrl(first);
        } else if (lineNodes.isEmpty()) {
            Sel.set("line", "");
            lineAdapter.setSelectedUrl("");
        }
    }

    // ---------------- 配置树扫描 → 路由表落库 → 推送自动应用 ----------------

    /**
     * 推送/长按刷新后重建：扫描配置树 → 路由表整组覆盖（持久）→ 表驱动重渲染 →
     * （推送路径）各未设置模块自动应用树内第一条对应线路。
     * 确定性时机：扫描完成计数对称（入队前 +1、回调必 -1），完成即执行，不再有旧版
     * 探测计数器/主线程 post 的静默失败口与竞态（已设置模块跳过，绝不覆盖用户选择）。
     */
    private void scanAndRebuild(final String rootUrl, final boolean fromPush) {
        scanning = true;
        tvTip.setText("正在检测配置数据…");
        ConfigRepo.scanTree(rootUrl, new ConfigRepo.ScanCallback() {
            @Override
            public void onDone(Map<String, ConfigRepo.ScanNode> nodes, List<String> order) {
                scanning = false;
                ConfigRepo.rebuildRouteRows(rootUrl, nodes, order);
                if (!isShowing() || !rootUrl.equals(Sel.config())) return; // 弹窗已关/用户已切配置
                loadRouteRows(rootUrl);
                renderColumnsFromTable();
                updateTip();
                if (fromPush) {
                    autoApplyFromTable();
                }
            }
        });
    }

    /**
     * 推送自动应用（FR-I-07）：调 ConfigRepo.applyUnsetModules 共享核心——四个模块逐个检查，
     * 未设置（键为空）→ 取路由表中该类型第一行写入；已设置不覆盖；该配置不含对应类型 → 模块不动。
     * 全部应用后提示并整套重载。
     */
    private void autoApplyFromTable() {
        List<String> applied = ConfigRepo.applyUnsetModules(Sel.config());
        if (applied.isEmpty()) {
            updateTip();
            return;
        }
        Toast.makeText(getContext(), "已自动应用「" + TextUtils.join("、", applied) + "」源", Toast.LENGTH_LONG).show();
        dismiss();
        reloadHome(); // 整套重载：设置页地址与各模块数据源一并生效
    }

    // ---------------- 推送与激活 ----------------

    /** 状态栏：当前类别 + 三列条数概览；配置为空时提示推送入口。 */
    private void updateTip() {
        String prefix = ConfigRepo.TYPE_NAMES[activeType];
        if (configNodes.isEmpty()) {
            tvTip.setText(prefix + " · 暂无配置，在下方输入链接推送");
            return;
        }
        tvTip.setText(prefix + " · 配置 " + configNodes.size() + " · 仓库 " + warehouseNodes.size()
                + " · 线路 " + lineNodes.size());
    }

    /** null 归一为空串（选中 url 全程非 null）。 */
    private static String safeUrl(String url) {
        return url == null ? "" : url;
    }

    /** 查列表中首个同 url 的节点（选中 url 比对用，列内 url 唯一）。 */
    private static ConfigNodeBean findByUrl(List<ConfigNodeBean> list, String url) {
        for (ConfigNodeBean n : list) {
            if (n.url != null && n.url.equals(url)) return n;
        }
        return null;
    }

    // ---------------- 打开恢复链 ----------------

    /**
     * 重开弹窗恢复上次选中（Hawk 持久化，全表驱动零网络）：
     * 左列找回选中配置（找不到=配置已被删，清选中走空态）→ 读路由表渲染中/右列，
     * 仓库/线路选中恢复与默认链选由 renderColumnsFromTable/fillRightFromTable 统一处理。
     */
    private void restoreLastSelection() {
        String configUrl = Sel.config();
        if (configUrl.isEmpty()) return;
        ConfigNodeBean config = findByUrl(configNodes, configUrl);
        if (config == null) {
            // 上次选中的配置已被删：清持久化选中走空态
            Sel.clear();
            return;
        }
        configAdapter.setSelectedUrl(config.url);
        loadRouteRows(config.url);
        renderColumnsFromTable();
    }

    // ---------------- 左列：配置 ----------------

    /** 点左列配置：选中 + 读路由表渲染中/右列（表缺失时补一次扫描建表，如旧版本升级场景）。 */
    private void onConfigClicked(final ConfigNodeBean node) {
        if (node == null) return;
        String url = safeUrl(node.url);
        Sel.set("config", url);
        configAdapter.setSelectedUrl(url);
        updateTabs();
        if (AppDataManager.get().getConfigRouteDao().countByConfigUrl(url) > 0) {
            loadRouteRows(url);
            renderColumnsFromTable();
        } else {
            scanAndRebuild(url, false);
        }
    }

    /**
     * 长按左列配置：失效缓存整树重扫，路由表整组覆盖——当前生效线路（四模块键）一律不碰，
     * 刷新后用户选择自动保留（新行含同线路地址即恢复"使用中"标记），3 秒成功提示。
     */
    private boolean onConfigLongClicked(ConfigNodeBean node) {
        if (node == null) return true;
        if (node.type != ConfigNodeBean.TYPE_CONFIG) return true; // 长按刷新仅配置级
        String url = safeUrl(node.url); // 长按视为选中：高亮与刷新内容一致
        Sel.set("config", url);
        configAdapter.setSelectedUrl(url);
        ConfigRepo.invalidateTree(url); // 清内存缓存 + 解除该树会话删除
        scanAndRebuild(url, false);
        showTimedToast("刷新缓存成功", 3000);
        return true;
    }

    /** 显示指定时长的 Toast（LENGTH_LONG 约 3.5 秒，到时主动 cancel 精确控制展示时长）。 */
    private void showTimedToast(String msg, long durationMs) {
        final Toast toast = Toast.makeText(getContext(), msg, Toast.LENGTH_LONG);
        toast.show();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                toast.cancel();
            }
        }, durationMs);
    }

    // ---------------- 中列：仓库 ----------------

    /** 点中列仓库组：选中 + 表驱动渲染右列（零网络）。 */
    private void onWarehouseClicked(final ConfigNodeBean node) {
        if (node == null) return;
        String url = safeUrl(node.url);
        Sel.set("warehouse", url);
        warehouseAdapter.setSelectedUrl(url);
        Sel.set("line", ""); // 换仓库组：线路选中重置
        lineAdapter.setSelectedUrl("");
        fillRightFromTable();
    }

    // ---------------- 右列：线路 ----------------

    /** 点右列线路：路由表右列均为已判型终端行，直接按当前类别 Tab 激活（写对应模块键）。 */
    private void onLineClicked(final ConfigNodeBean node) {
        if (node == null) return;
        String url = safeUrl(node.url);
        Sel.set("line", url);
        lineAdapter.setSelectedUrl(url);
        applyLine(node);
    }

    // ---------------- 删除 ----------------

    /** 删左列配置：持久移除（含路由表行）；若该配置即生效源或其路由表含生效线路，清 API_URL 重启首页。 */
    private void onDeleteConfig(final ConfigNodeBean node) {
        if (node == null) return;
        final String activeUrl = ConfigRepo.getActiveUrl();
        String tip = activeUrl.isEmpty()
                ? "删除「" + node.name + "」？"
                : "删除「" + node.name + "」？若当前源来自该配置将恢复默认。";
        confirmDelete(tip, new Runnable() {
            @Override
            public void run() {
                ConfigRepo.removeConfigItem(node.url);
                // 路由表整组删除（配置谱系随之清除）
                AppDataManager.get().getConfigRouteDao().deleteByConfigUrl(node.url);
                // 该配置自身即生效源（TERMINAL 经虚拟线路激活时 CONFIG_ACTIVE==config.url，
                // 路由表只记终端行查不到根）或其路由表含生效线路 → 回默认源并重载
                boolean treeHasActive = false;
                for (ConfigRoute row : AppDataManager.get().getConfigRouteDao().getByConfigUrl(node.url)) {
                    if (row.lineUrl != null && row.lineUrl.equals(activeUrl)) {
                        treeHasActive = true;
                        break;
                    }
                }
                if (!activeUrl.isEmpty()
                        && (node.url.equals(activeUrl) || treeHasActive)) {
                    ConfigRepo.setActiveUrl("");
                    Hawk.put(HawkConfig.API_URL, "");
                    SourceViewModel.clearRuntimeCache();
                    dismiss();
                    Intent intent = new Intent(getContext(), HomeActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    getContext().startActivity(intent);
                    return;
                }
                // 重建左列（按 url 保留幸存的选中）并重渲染中/右列（表驱动）；
                // 删的正是选中项时选中首位
                String configUrl = Sel.config();
                boolean wasSelected = node.url != null && node.url.equals(configUrl);
                rebuildConfigColumn(wasSelected ? null : configUrl);
            }
        });
    }

    /** 用最新持久配置列表重建左列，清空中/右列；keepUrl 幸存时恢复其选中。 */
    private void rebuildConfigColumn(String keepUrl) {
        configNodes.clear();
        for (ConfigItem item : ConfigRepo.getConfigList()) {
            configNodes.add(new ConfigNodeBean(item.name, item.url, ConfigNodeBean.TYPE_CONFIG, false));
        }
        Sel.set("config", "");
        if (keepUrl != null) {
            for (ConfigNodeBean node : configNodes) {
                if (node.url != null && node.url.equals(keepUrl)) {
                    Sel.set("config", node.url);
                    break;
                }
            }
        }
        configAdapter.setNewData(configNodes);
        configAdapter.setSelectedUrl(Sel.config());
        clearMiddleAndRight();
        if (!Sel.config().isEmpty()) {
            // 幸存配置：读路由表重渲染中/右列（默认落到首个仓库组/首条线路）
            loadRouteRows(Sel.config());
            renderColumnsFromTable();
        }
        updateTip();
    }

    /** 清空中/右列及其选中态（删配置/推送后的下游复位）。 */
    private void clearMiddleAndRight() {
        Sel.set("warehouse", "");
        Sel.set("line", "");
        warehouseAdapter.setSelectedUrl("");
        lineAdapter.setSelectedUrl("");
        allWarehouses.clear();
        allLines.clear();
        warehouseNodes.clear();
        lineNodes.clear();
        warehouseAdapter.setNewData(warehouseNodes);
        lineAdapter.setNewData(lineNodes);
    }

    /** 删中列仓库：会话移除（路由表行保留，重开弹窗/刷新恢复）+ 表驱动重渲染。 */
    private void onDeleteWarehouse(final ConfigNodeBean node) {
        if (node == null) return;
        confirmDelete("从列表移除「" + node.name + "」？（重开弹窗或刷新后恢复）", new Runnable() {
            @Override
            public void run() {
                ConfigRepo.sessionRemove(node.url);
                if (node.url != null && node.url.equals(Sel.warehouse())) {
                    Sel.set("warehouse", ""); // 删的正是选中项：renderColumnsFromTable 默认落首个
                    Sel.set("line", "");
                }
                renderColumnsFromTable();
            }
        });
    }

    /** 删右列线路：会话移除（路由表行保留，重开弹窗/刷新恢复）+ 右列重渲染。 */
    private void onDeleteLine(final ConfigNodeBean node) {
        if (node == null) return;
        confirmDelete("从列表移除「" + node.name + "」？（重开弹窗或刷新后恢复）", new Runnable() {
            @Override
            public void run() {
                ConfigRepo.sessionRemove(node.url);
                if (node.url != null && node.url.equals(Sel.line())) {
                    Sel.set("line", ""); // 删的正是选中项：fillRightFromTable 默认落首条
                }
                fillRightFromTable();
                updateTip();
            }
        });
    }

    // ---------------- 推送与激活 ----------------

    private void onConfirmClicked() {
        String url = etUrl.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(getContext(), "请输入配置链接", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = etName.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            name = ConfigRepo.genConfigName();
        }
        final ConfigNodeBean node = new ConfigNodeBean(name, url, ConfigNodeBean.TYPE_CONFIG, false);
        if (!ConfigRepo.addConfigItem(node.name, node.url)) {
            // 已存在的链接：复用原条目继续推送流程（重选+重探测+自动应用未设置模块），不再拒绝
            for (ConfigItem item : ConfigRepo.getConfigList()) {
                if (url.equals(item.url)) {
                    node.name = item.name;
                    break;
                }
            }
            Toast.makeText(getContext(), "该链接已存在，重新检测数据段", Toast.LENGTH_SHORT).show();
        }
        etName.setText("");
        etUrl.setText("");
        updateTabs();
        rebuildConfigColumn(node.url); // 重建左列并选中新条目（路由表尚无该配置 → 中右列空）
        scanAndRebuild(node.url, true); // 全树扫描落路由表 → 表驱动渲染 → 自动应用未设置模块
    }

    /**
     * 激活线路：按当前类别 Tab 只写对应模块的地址（点播/直播/短剧/音乐各自独立生效）并重载配置。
     */
    private void applyLine(final ConfigNodeBean node) {
        final int type = activeType;
        if (node.url.equals(Hawk.get(ConfigRepo.moduleHawkKey(type), ""))) {
            dismiss();
            return;
        }
        new TipDialog(getContext(), "将「" + node.name + "」设为" + ConfigRepo.TYPE_NAMES[type] + "源并重载配置？", "取消", "确定",
                new TipDialog.OnListener() {
                    @Override
                    public void left() {
                    }

                    @Override
                    public void right() {
                        applyModuleSource(type, node);
                    }

                    @Override
                    public void cancel() {
                    }
                }).show();
    }

    /** 激活线路（手动）：写入对应模块的地址（点播另记生效源与历史，直播记直播历史）后整套重载。 */
    private void applyModuleSource(int type, ConfigNodeBean node) {
        SourceViewModel.clearRuntimeCache();
        ConfigRepo.writeModuleSource(type, node.name, node.url);
        dismiss();
        reloadHome();
    }

    /** 整套配置重载：重建 HomeActivity（useCache=false 全量 loadConfig）。 */
    private void reloadHome() {
        Intent intent = new Intent(getContext(), HomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        getContext().startActivity(intent);
    }

    private void confirmDelete(String tip, final Runnable action) {
        new TipDialog(getContext(), tip, "取消", "删除", new TipDialog.OnListener() {
            @Override
            public void left() {
            }

            @Override
            public void right() {
                action.run();
            }

            @Override
            public void cancel() {
            }
        }).show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            dismiss(); // 三栏结构已表达层级，返回键直接关闭
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
