package com.github.gcd888.guoju.ui.dialog;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.gcd888.guoju.R;
import com.github.gcd888.guoju.api.ConfigRepo;
import com.github.gcd888.guoju.bean.ConfigItem;
import com.github.gcd888.guoju.bean.ConfigNodeBean;
import com.github.gcd888.guoju.ui.activity.HomeActivity;
import com.github.gcd888.guoju.ui.adapter.ConfigAdapter;
import com.github.gcd888.guoju.util.HawkConfig;
import com.github.gcd888.guoju.util.HistoryHelper;
import com.github.gcd888.guoju.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置弹窗（Finder 三栏式）：左=配置 / 中=仓库 / 右=线路，三列联动列表。
 * 点左列配置拉取判型填中/右列；点中列仓库拉取判型填右列；点右列线路为终端时直接激活，
 * 套娃链接拉取后层级借用右列展示下级。选中态用列内 url 比对（列表重建时节点重新 new，
 * 引用必不等；列内 url 唯一，跨列同 url 不冲突——各 adapter 只比对自己列的数据）；
 * 重开弹窗自动恢复上次选中（Hawk 持久化，进程重启后弹窗重开仍恢复）。
 * 类别 Tab（点播/直播/短剧/音乐）按数据段分流：选中配置后自根逐层轮询（多仓→仓库→线路）
 * 探测每条链接实际含有的数据段（sites/lives/dramas/musics），线路列只显示含当前 Tab
 * 对应段的线路，仓库列只显示子树内含该段的仓库；在哪个 Tab 下选线路就只生效到对应模块。
 * 每行删除（配置持久删/仓库线路会话删）；长按配置=失效整棵缓存树重拉；返回键直接关闭。
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

    /** 恢复链进行中：填列时据此接力恢复下游选中；用户点击路径一律先置 false 走下游清空语义 */
    private boolean restoring = false;

    /** 模块定位（设置页四地址入口进入时传入）：非空则 show 后默认展开该模块当前配置的中/右列。 */
    private final String locateUrl;

    private TextView tvTip;
    private EditText etName;
    private EditText etUrl;

    /** 类别 Tab：按链接实际含有的数据段分类，在哪个类别下选线路就只生效到对应模块。 */
    public static final int TYPE_VOD = 0;
    public static final int TYPE_LIVE = 1;
    public static final int TYPE_DRAMA = 2;
    public static final int TYPE_MUSIC = 3;
    private static final String[] TYPE_NAMES = {"点播", "直播", "短剧", "音乐"};
    private static final String[] TYPE_SECTIONS = {
            ConfigRepo.SECTION_SITES, ConfigRepo.SECTION_LIVES,
            ConfigRepo.SECTION_DRAMAS, ConfigRepo.SECTION_MUSICS};

    private final TextView[] tabViews = new TextView[4];
    private int activeType = TYPE_VOD;
    /** 当前链接实际含有的数据段（空 = 尚未判定，四 Tab 全显）。 */
    private final Set<String> knownSections = new LinkedHashSet<>();

    /** 中/右列的全量数据（过滤前）：显示列表 lineNodes/warehouseNodes 按当前 Tab 数据段过滤生成。 */
    private final List<ConfigNodeBean> allWarehouses = new ArrayList<>();
    private final List<ConfigNodeBean> allLines = new ArrayList<>();

    /** 线路级数据段探测（会话内累积）：url → 该链接自身含有的数据段（TERMINAL 判型记录）。 */
    private final Map<String, Set<String>> probeSections = new HashMap<>();
    /** 子树聚合数据段：url → 自身 + 已探测下级的并集（仓库层按"子树内任一线路含该段"过滤）。 */
    private final Map<String, Set<String>> probeSubtree = new HashMap<>();
    /** 探测父链：child url → parent url（子树聚合沿父链上抛）。 */
    private final Map<String, String> probeParent = new HashMap<>();
    /** 当前配置树已探测到的数据段并集（Tab 显隐判定；切换/刷新配置时重置）。 */
    private final Set<String> probedSectionsAll = new LinkedHashSet<>();
    /** 探测完成顺序的 url 列表（当前配置树内，自动应用时按"树内第一条"取线路；重置探测时清空）。 */
    private final List<String> probeOrder = new ArrayList<>();
    /** 探测到的节点名：url → 展示名（线路 url ≠ 配置 url 时设置页仍按名显示）。 */
    private final Map<String, String> probeNames = new HashMap<>();
    /** 进行中的探测请求数（tip 进度展示）。 */
    private int probePending = 0;
    /** 推送链接后置 true：整棵树探测完成后自动把含对应段的线路应用到各模块（见 autoApplyModules）。 */
    private boolean autoApplyOnProbeDone = false;

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
        findViewById(R.id.btnScan).setOnClickListener(v -> new RemoteDialog(getContext()).show());
        findViewById(R.id.btnConfirm).setOnClickListener(v -> onConfirmClicked());

        ConfigRepo.resetSession();
        for (ConfigItem item : ConfigRepo.getConfigList()) {
            configNodes.add(new ConfigNodeBean(item.name, item.url, ConfigNodeBean.TYPE_CONFIG, false));
        }
        configAdapter.setNewData(configNodes);
        updateTabs();
    }

    /** 打开恢复链入口：须在 show 后触发——复用的填列方法带 isShowing 守卫，且 ConfigRepo.CACHE
     * 命中时回调同步返回，构造器里调用会被守卫拦掉。
     * 另：当前无配置时自动展开扫码推送二维码（链接主要经手机扫码推送）；已有配置不弹，直接进三栏。 */
    @Override
    public void show() {
        super.show();
        restoreLastSelection();
        // 模块定位（PRD 4.3）：设置页四地址入口进入时，默认选中该模块当前配置项并展开中/右列
        if (!locateUrl.isEmpty() && !restoring) {
            for (ConfigNodeBean node : configNodes) {
                if (safeUrl(node.url).equals(locateUrl)) {
                    onConfigClicked(node);
                    break;
                }
            }
        }
        // 无历史选中（首次打开/持久选中已清）：默认链选首个配置，填列后自动落到首个仓库/首个线路
        if (Sel.config().isEmpty() && !configNodes.isEmpty()) {
            ConfigNodeBean first = configNodes.get(0);
            Sel.set("config", safeUrl(first.url));
            configAdapter.setSelectedUrl(first.url);
            fetchConfigIntoColumns(first);
        }
        if (ConfigRepo.getConfigList().isEmpty()) {
            new RemoteDialog(getContext()).show();
        }
    }

    /** 初始化单列列表（布局 + 适配器；点击/长按/删除由各列单独挂接）。 */
    private void initColumn(TvRecyclerView rv, ConfigAdapter adapter) {
        rv.setLayoutManager(new V7LinearLayoutManager(getContext(), V7LinearLayoutManager.VERTICAL, false));
        rv.setAdapter(adapter);
    }

    // ---------------- 三列选中与状态 ----------------

    /** 切换类别 Tab（点播/直播/短剧/音乐）：中/右列按数据段过滤，只显示含对应段数据的条目。 */
    private void selectType(int type) {
        activeType = type;
        updateTabs();
        refreshColumnsByType();
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

    /** 并入判型结果的数据段（reset=true 用于新选配置：链接判不出段则四 Tab 全显，不沿用旧配置的判定）。 */
    private void applySections(ConfigRepo.FetchResult result, boolean reset) {
        if (reset) {
            knownSections.clear();
        }
        if (result != null && !result.sections.isEmpty()) {
            knownSections.clear();
            knownSections.addAll(result.sections);
        }
        updateTabs();
    }

    // ---------------- 线路级数据段探测与三列过滤 ----------------

    /**
     * 三列按当前类别 Tab 过滤：线路列=线路自身含该数据段；仓库列=子树内任一线路含该段；
     * 段信息未知（未探测到/探测失败/套娃中间层）的条目不隐藏，探测完成后随刷新收敛。
     */
    private void refreshColumnsByType() {
        String section = TYPE_SECTIONS[activeType];
        warehouseNodes.clear();
        for (ConfigNodeBean n : allWarehouses) {
            Set<String> s = n.url == null ? null : probeSubtree.get(n.url);
            if (s == null || s.contains(section)) warehouseNodes.add(n);
        }
        warehouseAdapter.setNewData(warehouseNodes);
        lineNodes.clear();
        for (ConfigNodeBean n : allLines) {
            Set<String> s = n.url == null ? null : probeSections.get(n.url);
            if (s == null || s.contains(section)) lineNodes.add(n);
        }
        lineAdapter.setNewData(lineNodes);
        updateTip();
    }

    /**
     * 线路级数据段探测入口：从配置根逐层轮询（多仓→仓库→线路，套娃继续下钻，深度≤4）。
     * 探测只记录各链接含有的数据段并驱动三列过滤/Tab 显隐，不改动列内容与选中态；
     * 全部走 ConfigRepo.CACHE（点击路径已拉取过的秒回，未拉取过的后台补拉）。
     */
    private void startProbe(ConfigNodeBean root) {
        probeUrl(root.url, root.name, null, 0);
    }

    /** 探测单节点：缓存命中同步处理，否则后台拉取（进行中计数驱动 tip 进度）；记下节点名供自动应用用。 */
    private void probeUrl(final String url, final String name, final String parentUrl, final int depth) {
        if (depth > 4 || url == null || url.isEmpty()) return;
        if (name != null && !name.isEmpty()) probeNames.put(url, name);
        if (parentUrl != null) probeParent.put(url, parentUrl);
        ConfigRepo.FetchResult cached = ConfigRepo.peekCache(url);
        if (cached != null) {
            onProbed(url, cached, depth);
            return;
        }
        probePending++;
        refreshProbeTip();
        ConfigRepo.fetchNode(url, new ConfigRepo.NodeCallback() {
            @Override
            public void onSuccess(ConfigRepo.FetchResult result) {
                probePending--;
                onProbed(url, result, depth);
            }

            @Override
            public void onError(String msg) {
                probePending--;
                refreshProbeTip(); // 探测失败：段信息未知=不隐藏条目，tip 恢复常规概览
                maybeAutoApply(); // 失败节点段未知：其余探测完成后仍按已知段自动应用
            }
        });
    }

    /** 探测结果处理：记录段 → 下钻 → 沿父链上抛聚合 → 过滤三列 + 刷新 Tab 显隐（+推送后自动应用）。 */
    private void onProbed(String url, ConfigRepo.FetchResult result, int depth) {
        if (probeSubtree.containsKey(url)) {
            // 该 url 已探测过（段为链接自身属性，会话内不变）：复用已聚合段刷新判定即可
            probedSectionsAll.addAll(probeSubtree.get(url));
            if (!probeOrder.contains(url)) probeOrder.add(url);
            refreshColumnsByType();
            updateTabs();
            maybeAutoApply();
            return;
        }
        Set<String> own = new LinkedHashSet<>(result.sections);
        if (result.type == ConfigNodeBean.TYPE_TERMINAL) {
            probeSections.put(url, own);
        }
        probeSubtree.put(url, new LinkedHashSet<>(own));
        probedSectionsAll.addAll(own);
        probeOrder.add(url);
        if (depth < 4) {
            for (ConfigNodeBean child : result.nodes) {
                probeUrl(child.url, child.name, url, depth + 1);
            }
        }
        String p = probeParent.get(url); // 自身段沿父链上抛（父=仓库/线路列表层）
        while (p != null) {
            Set<String> pu = probeSubtree.get(p);
            if (pu == null) {
                pu = new LinkedHashSet<>();
                probeSubtree.put(p, pu);
            }
            pu.addAll(own);
            p = probeParent.get(p);
        }
        refreshProbeTip();
        refreshColumnsByType();
        updateTabs();
        maybeAutoApply();
    }

    /** 探测进度 tip：进行中显示剩余数，结束后恢复常规概览。 */
    private void refreshProbeTip() {
        if (probePending > 0) {
            tvTip.setText("正在检测线路数据… " + probePending);
        } else {
            updateTip();
        }
    }

    /** 重置当前配置树的探测判定（切换/刷新配置时；url 级段缓存保留=链接属性不变）。 */
    private void resetProbeState() {
        probedSectionsAll.clear();
        probeOrder.clear();
        probePending = 0;
        autoApplyOnProbeDone = false;
    }

    // ---------------- 推送后自动应用 ----------------

    /**
     * 自动应用门槛：仅推送链路（onConfirmClicked 置位）触发；等全部探测完成（无同步递归进行中、
     * 无后台请求在飞）且弹窗仍在展示才执行。post 到 tip 队列，让同步递归先走完再判定。
     */
    private void maybeAutoApply() {
        if (!autoApplyOnProbeDone || probePending > 0) return;
        tvTip.post(new Runnable() {
            @Override
            public void run() {
                if (!autoApplyOnProbeDone || probePending > 0 || !isShowing()) return;
                autoApplyOnProbeDone = false;
                autoApplyModules();
            }
        });
    }

    /**
     * 推送链接后自动应用：整棵配置树轮询完成后，按树内顺序取第一条含对应数据段的线路
     * （根配置本身是终端且含该段时优先用根），仅对【未设置过线路】的模块自动写入地址——
     * 点播/直播/短剧/音乐同规则；已设置过线路的模块保持不变（可在对应 Tab 手动换线）。
     * 全部应用完成后 toast 提示并整套重载（设置页地址与各模块数据源一并生效）。
     */
    private void autoApplyModules() {
        String rootUrl = Sel.config();
        if (rootUrl == null || rootUrl.isEmpty()) return;
        SourceViewModel.clearRuntimeCache();
        List<String> applied = new ArrayList<>();
        for (int type = TYPE_VOD; type <= TYPE_MUSIC; type++) {
            String current = Hawk.get(hawkKey(type), "");
            if (current != null && !current.isEmpty()) continue; // 已设置过线路：不覆盖
            String section = TYPE_SECTIONS[type];
            String url = null;
            Set<String> rootSections = probeSections.get(rootUrl);
            if (rootSections != null && rootSections.contains(section)) {
                url = rootUrl; // 根配置即终端：直接作为该模块源
            } else {
                for (String candidate : probeOrder) { // 树内第一条含该段的线路
                    if (candidate.equals(rootUrl)) continue;
                    Set<String> s = probeSections.get(candidate);
                    if (s != null && s.contains(section)) {
                        url = candidate;
                        break;
                    }
                }
            }
            if (url == null) continue; // 该配置不含此段：模块不动
            String name = probeNames.get(url);
            writeModuleSource(type, name == null ? "自定义线路" : name, url);
            applied.add(TYPE_NAMES[type]);
        }
        if (applied.isEmpty()) {
            updateTip();
            return;
        }
        Toast.makeText(getContext(), "已自动应用「" + TextUtils.join("、", applied) + "」源",
                Toast.LENGTH_LONG).show();
        dismiss();
        reloadHome(); // 整套重载：设置页地址与各模块数据源一并生效
    }

    /** 状态栏：当前类别 + 三列条数概览；配置为空时提示推送入口。 */
    private void updateTip() {
        String prefix = TYPE_NAMES[activeType];
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

    /** 按对象引用移除（url 相同的虚拟/真实节点不可用 equals 移除）。 */
    private static boolean removeByRef(List<ConfigNodeBean> list, ConfigNodeBean node) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == node) {
                list.remove(i);
                return true;
            }
        }
        return false;
    }

    /** 查列表中同 url 的虚拟节点（重复点同一 TERMINAL 型条目时复用，避免 genVirtualName 序号递增）。 */
    private static ConfigNodeBean findVirtualByUrl(List<ConfigNodeBean> list, String url) {
        for (ConfigNodeBean n : list) {
            if (n.virtual && n.url != null && n.url.equals(url)) return n;
        }
        return null;
    }

    // ---------------- 打开恢复链 ----------------

    /**
     * 重开弹窗恢复上次选中（Hawk 持久化）：左列找回选中配置（找不到=配置已被删，清三列选中走空态）
     * → 拉取填中/右列（ConfigRepo.CACHE 会话缓存命中秒回，进程重启后缓存必 miss 走真实网络拉取）
     * → 中列按 Sel.warehouse() 恢复选中与高亮 → 右列按 Sel.line() 恢复高亮。各步守卫与用户点击路径一致（url 守卫）。
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
        restoring = true;
        configAdapter.setSelectedUrl(config.url);
        fetchConfigIntoColumns(config);
    }

    /** 恢复链第二步：中列找回上次选中仓库；右列已随配置判型填好则直接恢复线路高亮，否则拉取该仓库填右列。 */
    private void restoreWarehouseSelection() {
        String warehouseUrl = Sel.warehouse();
        if (warehouseUrl.isEmpty()) {
            // 未选过仓库（LINE/TERMINAL 型常见）：右列已随判型填好的仍恢复线路高亮
            if (!allLines.isEmpty()) restoreLineSelection();
            restoring = false;
            return;
        }
        ConfigNodeBean warehouse = findByUrl(allWarehouses, warehouseUrl);
        if (warehouse == null) {
            // 上次选中的仓库已不在：清中右选中
            Sel.set("warehouse", "");
            Sel.set("line", "");
            restoring = false;
            return;
        }
        warehouseAdapter.setSelectedUrl(warehouse.url);
        if (!allLines.isEmpty()) {
            restoreLineSelection(); // LINE/TERMINAL 型配置右列已随判型填好
        } else {
            fetchWarehouseIntoRight(warehouse); // WAREHOUSE 型配置：restoring 保持 true，填右列后接力恢复线路
        }
    }

    /** 恢复链第三步：右列找回上次选中线路并高亮（线路是终端选择态，无需再向下拉取）。 */
    private void restoreLineSelection() {
        restoring = false;
        String lineUrl = Sel.line();
        if (lineUrl.isEmpty()) return;
        if (findByUrl(allLines, lineUrl) != null) {
            lineAdapter.setSelectedUrl(lineUrl);
        } else {
            Sel.set("line", ""); // 上次选中的线路已不在：清选中
        }
    }

    // ---------------- 左列：配置 ----------------

    /** 点左列配置：选中 + 拉取判型填中/右列。 */
    private void onConfigClicked(final ConfigNodeBean node) {
        if (node == null) return;
        restoring = false;
        String url = safeUrl(node.url);
        Sel.set("config", url);
        configAdapter.setSelectedUrl(url);
        knownSections.clear(); // 新选配置：类别判定从头开始（仓库根层判不出段则四 Tab 全显）
        resetProbeState(); // 线路数据段探测判定同步重置（url 级段缓存保留）
        updateTabs();
        fetchConfigIntoColumns(node);
    }

    /** 长按左列配置：失效整棵缓存树重拉，并保留上次的配置/仓库/线路选中（走恢复链找回高亮并接力右列），3 秒成功提示。 */
    private boolean onConfigLongClicked(ConfigNodeBean node) {
        if (node == null) return true;
        if (node.type != ConfigNodeBean.TYPE_CONFIG) return true; // 长按刷新仅配置级
        restoring = true; // 长按刷新=原地重拉：不清下游选中，填列后由恢复链找回仓库/线路选中
        String url = safeUrl(node.url); // 长按视为选中：高亮与刷新内容一致
        Sel.set("config", url);
        configAdapter.setSelectedUrl(url);
        ConfigRepo.invalidateTree(node.url);
        resetProbeState(); // 缓存树已失效：线路数据段判定随重拉重新收敛
        fetchConfigIntoColumns(node);
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

    /** 拉取配置并按判型填充中/右列（WAREHOUSE=仓列表 / LINE=虚拟仓库+真实线路 / TERMINAL=虚拟仓库+虚拟线路）。 */
    private void fetchConfigIntoColumns(final ConfigNodeBean node) {
        tvTip.setText("正在拉取…");
        ConfigRepo.fetchNode(node.url, new ConfigRepo.NodeCallback() {
            @Override
            public void onSuccess(ConfigRepo.FetchResult result) {
                if (!isShowing()) return;
                if (node.url == null || !node.url.equals(Sel.config())) return; // 用户已点别的条目，旧响应作废
                applySections(result, false);
                fillColumnsByConfigType(result, node);
                startProbe(node); // 逐层轮询线路数据段，驱动三列按类别过滤与 Tab 显隐
            }

            @Override
            public void onError(String msg) {
                if (!isShowing()) return;
                tvTip.setText(msg);
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 配置判型结果填列：左列不动，中/右列全量重建后按当前类别过滤显示，下游选中态随之清空（恢复链除外）。 */
    private void fillColumnsByConfigType(ConfigRepo.FetchResult result, ConfigNodeBean config) {
        // 清列前先记下同 url 虚拟节点，填列时复用（重复点同一配置序号不涨）
        ConfigNodeBean reuseWarehouse = findVirtualByUrl(allWarehouses, config.url);
        ConfigNodeBean reuseLine = findVirtualByUrl(allLines, config.url);
        if (!restoring) { // 用户点击：下游选中 url 同步清空，随列重建失效
            Sel.set("warehouse", "");
            Sel.set("line", "");
            warehouseAdapter.setSelectedUrl("");
            lineAdapter.setSelectedUrl("");
        }
        allWarehouses.clear();
        allLines.clear();
        if (result.type == ConfigNodeBean.TYPE_WAREHOUSE) {
            // 中列=真实仓列表，右列清空
            allWarehouses.addAll(result.nodes);
        } else {
            // LINE/TERMINAL 均缺仓库层：补单条虚拟仓库（url=配置 url，点它即重拉配置）
            allWarehouses.add(reuseWarehouse != null ? reuseWarehouse
                    : new ConfigNodeBean(ConfigRepo.genVirtualName("自定义仓库"),
                            config.url, ConfigNodeBean.TYPE_WAREHOUSE, true));
            if (result.type == ConfigNodeBean.TYPE_LINE) {
                allLines.addAll(result.nodes); // 右列=真实线路列表
            } else { // TERMINAL：右列单条虚拟线路（url=配置 url，可激活）
                allLines.add(reuseLine != null ? reuseLine
                        : new ConfigNodeBean(ConfigRepo.genVirtualName("自定义线路"),
                                config.url, ConfigNodeBean.TYPE_TERMINAL, true));
            }
        }
        refreshColumnsByType();
        if (restoring) {
            restoreWarehouseSelection(); // 恢复链：中列继续找回选中并接力右列
            if (Sel.warehouse().isEmpty() && !warehouseNodes.isEmpty()) {
                autoSelectFirstWarehouse(); // 恢复未覆盖到的层级：默认选中首条仓库
            }
        } else if (!warehouseNodes.isEmpty()) {
            autoSelectFirstWarehouse(); // 默认链选：未选仓库时落到第一条（与点击同路径拉取右列）
        }
    }

    // ---------------- 中列：仓库 ----------------

    /** 点中列仓库：选中 + 拉取判型填右列。 */
    private void onWarehouseClicked(final ConfigNodeBean node) {
        if (node == null) return;
        restoring = false;
        String url = safeUrl(node.url);
        Sel.set("warehouse", url);
        warehouseAdapter.setSelectedUrl(url);
        fetchWarehouseIntoRight(node);
    }

    /** 拉取仓库并按判型填右列（用户点击与恢复链共用；url 守卫防旧响应乱序）。 */
    private void fetchWarehouseIntoRight(final ConfigNodeBean node) {
        tvTip.setText("正在拉取…");
        ConfigRepo.fetchNode(node.url, new ConfigRepo.NodeCallback() {
            @Override
            public void onSuccess(ConfigRepo.FetchResult result) {
                if (!isShowing()) return;
                if (node.url == null || !node.url.equals(Sel.warehouse())) return; // 用户已点别的条目，旧响应作废
                applySections(result, false);
                fillRightByWarehouseType(result, node);
            }

            @Override
            public void onError(String msg) {
                if (!isShowing()) return;
                tvTip.setText(msg);
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 仓库判型结果填右列：LINE=真实线路 / WAREHOUSE=套娃仓列表借用右列 / TERMINAL=单条虚拟线路；全量重建后按当前类别过滤。 */
    private void fillRightByWarehouseType(ConfigRepo.FetchResult result, ConfigNodeBean warehouse) {
        ConfigNodeBean reuseLine = findVirtualByUrl(allLines, warehouse.url);
        if (!restoring) { // 用户点击：右列选中 url 同步清空，随列重建失效
            Sel.set("line", "");
            lineAdapter.setSelectedUrl("");
        }
        allLines.clear();
        if (result.type == ConfigNodeBean.TYPE_TERMINAL) {
            // 仓库下挂单终端：补虚拟线路（url=仓库 url）即可激活；已有同 url 虚拟线路则复用
            allLines.add(reuseLine != null ? reuseLine
                    : new ConfigNodeBean(ConfigRepo.genVirtualName("自定义线路"),
                            warehouse.url, ConfigNodeBean.TYPE_TERMINAL, true));
        } else {
            allLines.addAll(result.nodes);
        }
        refreshColumnsByType();
        if (restoring) {
            restoreLineSelection(); // 恢复链：右列找回选中
            if (Sel.line().isEmpty() && !lineNodes.isEmpty()) {
                autoSelectFirstLine(); // 恢复未覆盖到的层级：默认选中首条线路
            }
        } else if (!lineNodes.isEmpty()) {
            autoSelectFirstLine(); // 默认链选：未选线路时落到第一条（仅高亮，不激活）
        }
    }

    /** 默认链选中列：选中第一条仓库并拉取右列（与点击同路径，保证下游照常填充）。 */
    private void autoSelectFirstWarehouse() {
        ConfigNodeBean first = warehouseNodes.isEmpty() ? null : warehouseNodes.get(0);
        if (first == null || first.url == null) return;
        Sel.set("warehouse", first.url);
        warehouseAdapter.setSelectedUrl(first.url);
        fetchWarehouseIntoRight(first);
    }

    /** 默认链选右列：高亮第一条线路；模块尚无源且首条是终端时直接激活配置好（有历史选择则保持不动）。
     * 推送流程例外：树探测完成后 autoApplyModules 统一自动应用各未设置模块，这里不再激活——
     * 避免提前 dismiss+reload 打断探测，导致直播/短剧等其余模块漏应用。 */
    private void autoSelectFirstLine() {
        ConfigNodeBean first = lineNodes.isEmpty() ? null : lineNodes.get(0);
        if (first == null || first.url == null) return;
        Sel.set("line", first.url);
        lineAdapter.setSelectedUrl(first.url);
        if (first.type == ConfigNodeBean.TYPE_TERMINAL
                && !autoApplyOnProbeDone
                && Hawk.get(hawkKey(activeType), "").isEmpty()) {
            applyModuleSource(activeType, first); // 默认配置好：写入模块地址 + 关闭弹窗 + 整套重载
        }
    }

    // ---------------- 右列：线路 ----------------

    /**
     * 点右列线路：终端（虚拟/判型即终端）直接激活；套娃链接拉取后层级借用右列展示下级。
     */
    private void onLineClicked(final ConfigNodeBean node) {
        if (node == null) return;
        restoring = false;
        String url = safeUrl(node.url);
        Sel.set("line", url);
        lineAdapter.setSelectedUrl(url);
        if (node.type == ConfigNodeBean.TYPE_TERMINAL) {
            applyLine(node); // 终端节点直接激活（真实 name/url）
            return;
        }
        tvTip.setText("正在拉取…");
        ConfigRepo.fetchNode(node.url, new ConfigRepo.NodeCallback() {
            @Override
            public void onSuccess(ConfigRepo.FetchResult result) {
                if (!isShowing()) return;
                if (node.url == null || !node.url.equals(Sel.line())) return; // 用户已点别的条目，旧响应作废
                applySections(result, false);
                if (result.type == ConfigNodeBean.TYPE_TERMINAL) {
                    applyLine(node); // 内容即终端配置：用真实 name/url 激活，不再换层
                    return;
                }
                // 层级借用右列：LINE=线路列表 / WAREHOUSE=仓列表，选中随列重建失效
                Sel.set("line", "");
                lineAdapter.setSelectedUrl("");
                allLines.clear();
                allLines.addAll(result.nodes);
                refreshColumnsByType();
            }

            @Override
            public void onError(String msg) {
                if (!isShowing()) return;
                tvTip.setText(msg);
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---------------- 删除 ----------------

    /** 删左列配置：持久移除；若该配置即生效源或其缓存树含生效线路，清 API_URL 重启首页。 */
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
                // 该配置自身即生效源（TERMINAL 经虚拟线路激活时 CONFIG_ACTIVE==config.url，
                // cacheTreeContains 只查子孙查不到根）或其缓存树含生效线路 → 回默认源并重载
                if (!activeUrl.isEmpty()
                        && (node.url.equals(activeUrl) || ConfigRepo.cacheTreeContains(node.url, activeUrl))) {
                    ConfigRepo.setActiveUrl("");
                    Hawk.put(HawkConfig.API_URL, "");
                    SourceViewModel.clearRuntimeCache();
                    dismiss();
                    Intent intent = new Intent(getContext(), HomeActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    getContext().startActivity(intent);
                    return;
                }
                // 重建左列（按 url 保留幸存的选中）并清空中/右列；
                // 删的正是选中项时选中首位（列表非空，中右列已清空无需重拉）
                String configUrl = Sel.config();
                boolean wasSelected = node.url != null && node.url.equals(configUrl);
                rebuildConfigColumn(wasSelected ? null : configUrl);
                if (wasSelected && !configNodes.isEmpty()) {
                    String firstUrl = safeUrl(configNodes.get(0).url);
                    Sel.set("config", firstUrl);
                    configAdapter.setSelectedUrl(firstUrl);
                }
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

    /** 删中列仓库：会话移除 + 列表移除 + 右列清空。 */
    private void onDeleteWarehouse(final ConfigNodeBean node) {
        if (node == null) return;
        confirmDelete("从列表移除「" + node.name + "」？（刷新后恢复）", new Runnable() {
            @Override
            public void run() {
                ConfigRepo.sessionRemove(node.url);
                removeByRef(warehouseNodes, node);
                removeByRef(allWarehouses, node);
                if (node.url != null && node.url.equals(Sel.warehouse())) Sel.set("warehouse", ""); // 删的正是选中项
                warehouseAdapter.notifyDataSetChanged();
                clearRightColumn();
            }
        });
    }

    /** 删右列线路：会话移除 + 列表移除。 */
    private void onDeleteLine(final ConfigNodeBean node) {
        if (node == null) return;
        confirmDelete("从列表移除「" + node.name + "」？（刷新后恢复）", new Runnable() {
            @Override
            public void run() {
                ConfigRepo.sessionRemove(node.url);
                removeByRef(lineNodes, node);
                removeByRef(allLines, node);
                if (node.url != null && node.url.equals(Sel.line())) Sel.set("line", ""); // 删的正是选中项
                lineAdapter.notifyDataSetChanged();
                updateTip();
            }
        });
    }

    /** 清空右列及选中态（删仓库后的下游复位）。 */
    private void clearRightColumn() {
        Sel.set("line", "");
        lineAdapter.setSelectedUrl("");
        allLines.clear();
        lineNodes.clear();
        lineAdapter.setNewData(lineNodes);
        updateTip();
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
        restoring = false;
        knownSections.clear(); // 新推送链接：类别判定从头开始
        resetProbeState();
        autoApplyOnProbeDone = true; // 推送意图=整套采用：树探测完成后自动应用到各模块（见 autoApplyModules）
        updateTabs();
        rebuildConfigColumn(node.url); // 重建左列并选中新条目
        fetchConfigIntoColumns(node); // 加入后自动拉取填中/右列
    }

    /**
     * 激活线路：按当前类别 Tab 只写对应模块的地址（点播/直播/短剧/音乐各自独立生效）并重载配置。
     */
    private void applyLine(final ConfigNodeBean node) {
        final int type = activeType;
        if (node.url.equals(Hawk.get(hawkKey(type), ""))) {
            dismiss();
            return;
        }
        new TipDialog(getContext(), "将「" + node.name + "」设为" + TYPE_NAMES[type] + "源并重载配置？", "取消", "确定",
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

    /** 各模块地址键（点播/直播/短剧/音乐）。 */
    private static String hawkKey(int type) {
        switch (type) {
            case TYPE_LIVE:
                return HawkConfig.LIVE_API_URL;
            case TYPE_DRAMA:
                return HawkConfig.DRAMA_CONFIG_URL;
            case TYPE_MUSIC:
                return HawkConfig.MUSIC_CONFIG_URL;
            case TYPE_VOD:
            default:
                return HawkConfig.API_URL;
        }
    }

    /** 激活线路（手动）：写入对应模块的地址（点播另记生效源与历史，直播记直播历史）后整套重载。 */
    private void applyModuleSource(int type, ConfigNodeBean node) {
        SourceViewModel.clearRuntimeCache();
        writeModuleSource(type, node.name, node.url);
        dismiss();
        reloadHome();
    }

    /** 各模块地址写入（不含重载）：设置页按 rememberUrlName 的名称展示。 */
    private void writeModuleSource(int type, String name, String url) {
        ConfigRepo.rememberUrlName(url, name); // 设置页按名称显示（线路 url ≠ 配置 url 时也能显示名）
        switch (type) {
            case TYPE_LIVE:
                Hawk.put(HawkConfig.LIVE_API_URL, url);
                HistoryHelper.setLiveApiHistory(url);
                break;
            case TYPE_DRAMA:
                Hawk.put(HawkConfig.DRAMA_CONFIG_URL, url);
                break;
            case TYPE_MUSIC:
                Hawk.put(HawkConfig.MUSIC_CONFIG_URL, url);
                break;
            case TYPE_VOD:
            default:
                Hawk.put(HawkConfig.API_URL, url);
                ConfigRepo.setActiveUrl(url);
                HistoryHelper.setApiHistory(url);
                break;
        }
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
