package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.chad.library.adapter.base.BaseQuickAdapter;
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
 * 配置弹窗：配置 > 仓库 > 线路 三级钻取。
 * 每行删除按钮（配置级持久删、仓库/线路级会话删）；长按配置=刷新整棵资源树；
 * 线路激活走 API_URL + clearRuntimeCache + 重启首页现有链路（同 WarehouseDialog）。
 */
public class ConfigDialog extends BaseDialog {

    private final ConfigAdapter adapter = new ConfigAdapter();
    /** 层级路径上下文：面包屑展示与返回回退 */
    private final List<String> breadcrumb = new ArrayList<>();
    /** 层栈：栈顶为当前展示层（0=配置/1=仓库/2=线路，层级由节点判型决定） */
    private final List<Level> stack = new ArrayList<>();
    private TextView tvBreadcrumb;
    private TextView tvTip;
    private EditText etName;
    private EditText etUrl;

    private static class Level {
        List<ConfigNodeBean> nodes = new ArrayList<>();
        /** 本层压入时向面包屑添加的条数（虚拟补层为 2：本级名+虚拟仓库名），返回时同步回退 */
        int crumbs;
    }

    public ConfigDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_config);
        setCanceledOnTouchOutside(false);

        tvBreadcrumb = findViewById(R.id.tvBreadcrumb);
        tvTip = findViewById(R.id.tvTip);
        etName = findViewById(R.id.etName);
        etUrl = findViewById(R.id.etUrl);
        TvRecyclerView rvConfig = findViewById(R.id.rvConfig);
        rvConfig.setLayoutManager(new V7LinearLayoutManager(getContext(), V7LinearLayoutManager.VERTICAL, false));
        rvConfig.setAdapter(adapter);

        adapter.setOnItemClickListener((a, view, position) -> onItemClicked(adapter.getItem(position)));
        adapter.setOnItemLongClickListener((a, view, position) -> onItemLongClicked(adapter.getItem(position)));
        adapter.setOnItemChildClickListener((a, view, position) -> {
            if (view.getId() == R.id.ivDelete) {
                onDeleteClicked(adapter.getItem(position));
            }
        });

        findViewById(R.id.ivClose).setOnClickListener(v -> dismiss());
        findViewById(R.id.btnScan).setOnClickListener(v -> new RemoteDialog(getContext()).show());
        findViewById(R.id.btnConfirm).setOnClickListener(v -> onConfirmClicked());

        ConfigRepo.resetSession();
        Level root = new Level();
        root.nodes.addAll(buildConfigNodes());
        stack.add(root);
        refreshUi();
    }

    // ---------------- 层级渲染 ----------------

    private List<ConfigNodeBean> buildConfigNodes() {
        List<ConfigNodeBean> nodes = new ArrayList<>();
        for (ConfigItem item : ConfigRepo.getConfigList()) {
            nodes.add(new ConfigNodeBean(item.name, item.url, ConfigNodeBean.TYPE_CONFIG, false));
        }
        return nodes;
    }

    /** 压入新层并记录其贡献的面包屑条数（crumb 传 null 跳过），保证返回回退同步。 */
    private void pushLevel(Level level, String... crumbs) {
        int added = 0;
        for (String crumb : crumbs) {
            if (crumb != null) {
                breadcrumb.add(crumb);
                added++;
            }
        }
        level.crumbs = added;
        stack.add(level);
        refreshUi();
    }

    private void refreshUi() {
        Level top = stack.get(stack.size() - 1);
        adapter.setNewData(top.nodes);
        adapter.setCurrentUrl(ConfigRepo.getActiveUrl());
        tvBreadcrumb.setText(joinBreadcrumb());
        tvTip.setText(top.nodes.isEmpty() ? "暂无条目，在下方输入链接推送" : "共 " + top.nodes.size() + " 条");
    }

    private String joinBreadcrumb() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < breadcrumb.size(); i++) {
            if (i > 0) sb.append(" > ");
            sb.append(breadcrumb.get(i));
        }
        return sb.toString();
    }

    private void backLevel() {
        if (stack.size() <= 1) {
            dismiss();
            return;
        }
        Level top = stack.remove(stack.size() - 1);
        for (int i = 0; i < top.crumbs && !breadcrumb.isEmpty(); i++) {
            breadcrumb.remove(breadcrumb.size() - 1);
        }
        refreshUi();
    }

    /** 回退到根（配置级）并清空面包屑，供长按刷新/删除/新增后重钻。 */
    private void popToRoot() {
        while (stack.size() > 1) {
            stack.remove(stack.size() - 1);
            if (!breadcrumb.isEmpty()) breadcrumb.remove(breadcrumb.size() - 1);
        }
        breadcrumb.clear();
    }

    /** 用最新持久配置列表原地重建根层（避免 showConfigLevel 重复压栈）。 */
    private void rebuildRootLevel() {
        popToRoot();
        Level root = stack.get(0);
        root.nodes.clear();
        root.nodes.addAll(buildConfigNodes());
        refreshUi();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            backLevel();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------- 条目行为 ----------------

    private void onItemClicked(ConfigNodeBean node) {
        if (node == null) return;
        if (node.type == ConfigNodeBean.TYPE_TERMINAL) {
            applyLine(node); // 终端节点直接激活
        } else {
            fetchAndEnter(node); // 配置/仓库/线路均拉取判型决定下一层
        }
    }

    private void fetchAndEnter(final ConfigNodeBean node) {
        tvTip.setText("正在拉取…");
        ConfigRepo.fetchNode(node.url, new ConfigRepo.NodeCallback() {
            @Override
            public void onSuccess(ConfigRepo.FetchResult result) {
                if (!isShowing()) return;
                enterByType(result, node.url, node.name);
            }

            @Override
            public void onError(String msg) {
                if (!isShowing()) return;
                tvTip.setText(msg);
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 按判型结果进入下一层，缺失层级补虚拟节点保证三级完整。 */
    private void enterByType(ConfigRepo.FetchResult result, String url, String name) {
        if (result.type == ConfigNodeBean.TYPE_WAREHOUSE) {
            // 仓库列表：配置点入 or 仓库套娃，均以本级名加深面包屑、列表替换
            Level level = new Level();
            level.nodes.addAll(result.nodes);
            pushLevel(level, name);
        } else if (result.type == ConfigNodeBean.TYPE_LINE) {
            // urls 型线路列表：缺仓库层 → 面包屑补「本级名 > 虚拟仓库名」，直接展示线路
            Level level = new Level();
            level.nodes.addAll(result.nodes);
            pushLevel(level, name, ConfigRepo.genVirtualName("自定义仓库"));
        } else {
            // TERMINAL：补虚拟仓库 + 单条虚拟线路（url=本级自身，可激活）
            Level level = new Level();
            level.nodes.add(new ConfigNodeBean(ConfigRepo.genVirtualName("自定义线路"), url,
                    ConfigNodeBean.TYPE_TERMINAL, true));
            pushLevel(level, name, ConfigRepo.genVirtualName("自定义仓库"));
        }
    }

    private boolean onItemLongClicked(ConfigNodeBean node) {
        if (node == null) return true;
        if (node.type != ConfigNodeBean.TYPE_CONFIG) {
            return true; // 长按刷新仅配置级
        }
        ConfigRepo.invalidateTree(node.url); // 整棵资源树缓存失效（含已缓存的下级节点）
        popToRoot();
        fetchAndEnter(node); // 重钻该配置
        return true;
    }

    private void onDeleteClicked(final ConfigNodeBean node) {
        if (node == null) return;
        if (node.type == ConfigNodeBean.TYPE_CONFIG) {
            deleteConfig(node);
        } else {
            // 仓库/线路级：会话内移除（无持久拉黑，刷新/重进弹窗恢复）
            confirmDelete("从列表移除「" + node.name + "」？（刷新后恢复）", new Runnable() {
                @Override
                public void run() {
                    ConfigRepo.sessionRemove(node.url);
                    stack.get(stack.size() - 1).nodes.remove(node);
                    refreshUi();
                }
            });
        }
    }

    private void deleteConfig(final ConfigNodeBean node) {
        final String activeUrl = ConfigRepo.getActiveUrl();
        String tip = activeUrl.isEmpty()
                ? "删除「" + node.name + "」？"
                : "删除「" + node.name + "」？若当前源来自该配置将恢复默认。";
        confirmDelete(tip, new Runnable() {
            @Override
            public void run() {
                ConfigRepo.removeConfigItem(node.url);
                // 该配置会话缓存树若含当前生效线路 → 回默认源并重载
                if (!activeUrl.isEmpty() && ConfigRepo.cacheTreeContains(node.url, activeUrl)) {
                    ConfigRepo.setActiveUrl("");
                    Hawk.put(HawkConfig.API_URL, "");
                    SourceViewModel.clearRuntimeCache();
                    dismiss();
                    Intent intent = new Intent(getContext(), HomeActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    getContext().startActivity(intent);
                    return;
                }
                rebuildRootLevel();
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
            Toast.makeText(getContext(), "该链接已存在", Toast.LENGTH_SHORT).show();
            return;
        }
        etName.setText("");
        etUrl.setText("");
        rebuildRootLevel(); // 根层带上新条目并回到配置级
        fetchAndEnter(node); // 加入后自动钻入该配置
    }

    /** 激活线路（照搬 WarehouseDialog 生效链路，另记 CONFIG_ACTIVE 供✓回显）。 */
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
}
