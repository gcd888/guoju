package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ConfigRepo;
import com.github.tvbox.osc.bean.ConfigItem;
import com.github.tvbox.osc.bean.ConfigNodeBean;
import com.github.tvbox.osc.ui.activity.HomeActivity;
import com.github.tvbox.osc.ui.adapter.ConfigAdapter;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置弹窗（Finder 三栏式）：左=配置 / 中=仓库 / 右=线路，三列联动列表。
 * 点左列配置拉取判型填中/右列；点中列仓库拉取判型填右列；点右列线路为终端时直接激活，
 * 套娃链接拉取后层级借用右列展示下级。勾选态用节点引用比对（虚拟节点与上级同 url）；
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

    /** 三列勾选态（节点对象引用；列数据被替换时置空，不能用 url 比对——虚拟节点与上级同 url） */
    private ConfigNodeBean selectedConfig;
    private ConfigNodeBean selectedWarehouse;
    private ConfigNodeBean selectedLine;

    private TextView tvTip;
    private EditText etName;
    private EditText etUrl;

    public ConfigDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_config);
        setCanceledOnTouchOutside(false);

        tvTip = findViewById(R.id.tvTip);
        etName = findViewById(R.id.etName);
        etUrl = findViewById(R.id.etUrl);

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
        // 生效✓三列各自比对展示（切换源/删除生效源均会关闭弹窗，无需动态刷新）
        String activeUrl = ConfigRepo.getActiveUrl();
        configAdapter.setCurrentUrl(activeUrl);
        warehouseAdapter.setCurrentUrl(activeUrl);
        lineAdapter.setCurrentUrl(activeUrl);
        updateTip();
    }

    /** 初始化单列列表（布局 + 适配器；点击/长按/删除由各列单独挂接）。 */
    private void initColumn(TvRecyclerView rv, ConfigAdapter adapter) {
        rv.setLayoutManager(new V7LinearLayoutManager(getContext(), V7LinearLayoutManager.VERTICAL, false));
        rv.setAdapter(adapter);
    }

    // ---------------- 三列勾选与状态 ----------------

    /** 状态栏：三列条数概览；配置为空时提示推送入口。 */
    private void updateTip() {
        if (configNodes.isEmpty()) {
            tvTip.setText("暂无配置，在下方输入链接推送");
            return;
        }
        tvTip.setText("配置 " + configNodes.size() + " · 仓库 " + warehouseNodes.size() + " · 线路 " + lineNodes.size());
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

    // ---------------- 左列：配置 ----------------

    /** 点左列配置：勾选 + 拉取判型填中/右列。 */
    private void onConfigClicked(final ConfigNodeBean node) {
        if (node == null) return;
        selectedConfig = node;
        configAdapter.setSelected(node);
        fetchConfigIntoColumns(node);
    }

    /** 长按左列配置：勾选该配置并失效整棵缓存树（含恢复该树会话删除）重新拉取填列。 */
    private boolean onConfigLongClicked(ConfigNodeBean node) {
        if (node == null) return true;
        if (node.type != ConfigNodeBean.TYPE_CONFIG) return true; // 长按刷新仅配置级
        selectedConfig = node; // 长按视为选中：勾选与刷新内容一致
        configAdapter.setSelected(node);
        ConfigRepo.invalidateTree(node.url);
        fetchConfigIntoColumns(node);
        return true;
    }

    /** 拉取配置并按判型填充中/右列（WAREHOUSE=仓列表 / LINE=虚拟仓库+真实线路 / TERMINAL=虚拟仓库+虚拟线路）。 */
    private void fetchConfigIntoColumns(final ConfigNodeBean node) {
        tvTip.setText("正在拉取…");
        ConfigRepo.fetchNode(node.url, new ConfigRepo.NodeCallback() {
            @Override
            public void onSuccess(ConfigRepo.FetchResult result) {
                if (!isShowing()) return;
                if (node != selectedConfig) return; // 用户已点别的条目，旧响应作废
                fillColumnsByConfigType(result, node);
            }

            @Override
            public void onError(String msg) {
                if (!isShowing()) return;
                tvTip.setText(msg);
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 配置判型结果填列：左列不动，中/右列整体重建，下游勾选态随之清空。 */
    private void fillColumnsByConfigType(ConfigRepo.FetchResult result, ConfigNodeBean config) {
        selectedWarehouse = null;
        selectedLine = null;
        warehouseAdapter.setSelected(null);
        lineAdapter.setSelected(null);
        // 清列前先记下同 url 虚拟节点，填列时复用（重复点同一配置序号不涨）
        ConfigNodeBean reuseWarehouse = findVirtualByUrl(warehouseNodes, config.url);
        ConfigNodeBean reuseLine = findVirtualByUrl(lineNodes, config.url);
        warehouseNodes.clear();
        lineNodes.clear();
        if (result.type == ConfigNodeBean.TYPE_WAREHOUSE) {
            // 中列=真实仓列表，右列清空
            warehouseNodes.addAll(result.nodes);
        } else {
            // LINE/TERMINAL 均缺仓库层：补单条虚拟仓库（url=配置 url，点它即重拉配置）
            warehouseNodes.add(reuseWarehouse != null ? reuseWarehouse
                    : new ConfigNodeBean(ConfigRepo.genVirtualName("自定义仓库"),
                            config.url, ConfigNodeBean.TYPE_WAREHOUSE, true));
            if (result.type == ConfigNodeBean.TYPE_LINE) {
                lineNodes.addAll(result.nodes); // 右列=真实线路列表
            } else { // TERMINAL：右列单条虚拟线路（url=配置 url，可激活）
                lineNodes.add(reuseLine != null ? reuseLine
                        : new ConfigNodeBean(ConfigRepo.genVirtualName("自定义线路"),
                                config.url, ConfigNodeBean.TYPE_TERMINAL, true));
            }
        }
        warehouseAdapter.setNewData(warehouseNodes);
        lineAdapter.setNewData(lineNodes);
        updateTip();
    }

    // ---------------- 中列：仓库 ----------------

    /** 点中列仓库：勾选 + 拉取判型填右列。 */
    private void onWarehouseClicked(final ConfigNodeBean node) {
        if (node == null) return;
        selectedWarehouse = node;
        warehouseAdapter.setSelected(node);
        tvTip.setText("正在拉取…");
        ConfigRepo.fetchNode(node.url, new ConfigRepo.NodeCallback() {
            @Override
            public void onSuccess(ConfigRepo.FetchResult result) {
                if (!isShowing()) return;
                if (node != selectedWarehouse) return; // 用户已点别的条目，旧响应作废
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

    /** 仓库判型结果填右列：LINE=真实线路 / WAREHOUSE=套娃仓列表借用右列 / TERMINAL=单条虚拟线路。 */
    private void fillRightByWarehouseType(ConfigRepo.FetchResult result, ConfigNodeBean warehouse) {
        selectedLine = null;
        lineAdapter.setSelected(null);
        ConfigNodeBean reuseLine = findVirtualByUrl(lineNodes, warehouse.url);
        lineNodes.clear();
        if (result.type == ConfigNodeBean.TYPE_TERMINAL) {
            // 仓库下挂单终端：补虚拟线路（url=仓库 url）即可激活；已有同 url 虚拟线路则复用
            lineNodes.add(reuseLine != null ? reuseLine
                    : new ConfigNodeBean(ConfigRepo.genVirtualName("自定义线路"),
                            warehouse.url, ConfigNodeBean.TYPE_TERMINAL, true));
        } else {
            lineNodes.addAll(result.nodes);
        }
        lineAdapter.setNewData(lineNodes);
        updateTip();
    }

    // ---------------- 右列：线路 ----------------

    /**
     * 点右列线路：终端（虚拟/判型即终端）直接激活；套娃链接拉取后层级借用右列展示下级。
     */
    private void onLineClicked(final ConfigNodeBean node) {
        if (node == null) return;
        selectedLine = node;
        lineAdapter.setSelected(node);
        if (node.type == ConfigNodeBean.TYPE_TERMINAL) {
            applyLine(node); // 终端节点直接激活（真实 name/url）
            return;
        }
        tvTip.setText("正在拉取…");
        ConfigRepo.fetchNode(node.url, new ConfigRepo.NodeCallback() {
            @Override
            public void onSuccess(ConfigRepo.FetchResult result) {
                if (!isShowing()) return;
                if (node != selectedLine) return; // 用户已点别的条目，旧响应作废
                if (result.type == ConfigNodeBean.TYPE_TERMINAL) {
                    applyLine(node); // 内容即终端配置：用真实 name/url 激活，不再换层
                    return;
                }
                // 层级借用右列：LINE=线路列表 / WAREHOUSE=仓列表，勾选随列重建失效
                selectedLine = null;
                lineAdapter.setSelected(null);
                lineNodes.clear();
                lineNodes.addAll(result.nodes);
                lineAdapter.setNewData(lineNodes);
                updateTip();
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
                // 重建左列（按 url 保留幸存的勾选）并清空中/右列；
                // 删的正是选中项时勾选首位（列表非空，中右列已清空无需重拉）
                boolean wasSelected = selectedConfig == node;
                rebuildConfigColumn(wasSelected ? null
                        : (selectedConfig == null ? null : selectedConfig.url));
                if (wasSelected && !configNodes.isEmpty()) {
                    selectedConfig = configNodes.get(0);
                    configAdapter.setSelected(selectedConfig);
                }
            }
        });
    }

    /** 用最新持久配置列表重建左列，清空中/右列；keepUrl 幸存时恢复其勾选。 */
    private void rebuildConfigColumn(String keepUrl) {
        configNodes.clear();
        for (ConfigItem item : ConfigRepo.getConfigList()) {
            configNodes.add(new ConfigNodeBean(item.name, item.url, ConfigNodeBean.TYPE_CONFIG, false));
        }
        selectedConfig = null;
        if (keepUrl != null) {
            for (ConfigNodeBean node : configNodes) {
                if (node.url != null && node.url.equals(keepUrl)) {
                    selectedConfig = node;
                    break;
                }
            }
        }
        configAdapter.setNewData(configNodes);
        configAdapter.setSelected(selectedConfig);
        clearMiddleAndRight();
        updateTip();
    }

    /** 清空中/右列及其勾选态（删配置/重钻后的下游复位）。 */
    private void clearMiddleAndRight() {
        selectedWarehouse = null;
        selectedLine = null;
        warehouseAdapter.setSelected(null);
        lineAdapter.setSelected(null);
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
                if (selectedWarehouse == node) selectedWarehouse = null;
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
                if (selectedLine == node) selectedLine = null;
                lineAdapter.notifyDataSetChanged();
                updateTip();
            }
        });
    }

    /** 清空右列及勾选态（删仓库后的下游复位）。 */
    private void clearRightColumn() {
        selectedLine = null;
        lineAdapter.setSelected(null);
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
            Toast.makeText(getContext(), "该链接已存在", Toast.LENGTH_SHORT).show();
            return;
        }
        etName.setText("");
        etUrl.setText("");
        rebuildConfigColumn(node.url); // 重建左列并勾选新条目
        fetchConfigIntoColumns(node); // 加入后自动拉取填中/右列
    }

    /** 激活线路（沿用既有生效链路，另记 CONFIG_ACTIVE 供✓回显）。 */
    private void applyLine(final ConfigNodeBean node) {
        String oldApi = Hawk.get(HawkConfig.API_URL, "");
        if (node.url.equals(oldApi)) {
            dismiss();
            return;
        }
        new TipDialog(getContext(), "切换到「" + node.name + "」并重载配置？", "取消", "确定",
                new TipDialog.OnListener() {
                    @Override
                    public void left() {
                    }

                    @Override
                    public void right() {
                        SourceViewModel.clearRuntimeCache();
                        Hawk.put(HawkConfig.API_URL, node.url);
                        ConfigRepo.setActiveUrl(node.url);
                        HistoryHelper.setApiHistory(node.url);
                        dismiss();
                        // 整套配置重载：重建 HomeActivity（useCache=false 全量 loadConfig）
                        Intent intent = new Intent(getContext(), HomeActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        getContext().startActivity(intent);
                    }

                    @Override
                    public void cancel() {
                    }
                }).show();
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
