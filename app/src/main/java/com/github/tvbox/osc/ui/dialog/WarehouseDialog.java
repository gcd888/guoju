package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.WarehouseRepo;
import com.github.tvbox.osc.bean.WarehouseBean;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.adapter.PushSourceAdapter;
import com.github.tvbox.osc.ui.adapter.WarehouseAdapter;
import com.github.tvbox.osc.ui.activity.HomeActivity;
import com.github.tvbox.osc.ui.tv.QRCodeGen;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 多仓推送对话框（参考影视仓）：
 * 左栏 = 推送链接列表（可添加/删除/刷新重拉整棵资源树）；
 * 右栏 = 当前推送链接下的多仓接口列表（sourceName 展示、可切换、可删除）；
 * 顶部保留扫码远程推送二维码。
 * 选中接口后整套配置重载并回首页。
 */
public class WarehouseDialog extends BaseDialog {

    private final PushSourceAdapter sourceAdapter = new PushSourceAdapter();
    private final WarehouseAdapter warehouseAdapter = new WarehouseAdapter();
    private String selectedSource;

    public WarehouseDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_warehouse);
        setCanceledOnTouchOutside(false);

        TvRecyclerView rvPushSources = findViewById(R.id.rvPushSources);
        TvRecyclerView rvWarehouses = findViewById(R.id.rvWarehouses);
        TextView tvAddress = findViewById(R.id.tvAddress);
        ImageView ivQRCode = findViewById(R.id.ivQRCode);
        View btnAddSource = findViewById(R.id.btnAddSource);
        View btnRefreshTree = findViewById(R.id.btnRefreshTree);
        View ivClose = findViewById(R.id.ivClose);
        TextView tvWarehouseTip = findViewById(R.id.tvWarehouseTip);

        rvPushSources.setLayoutManager(new V7LinearLayoutManager(getContext(), V7LinearLayoutManager.VERTICAL, false));
        rvPushSources.setAdapter(sourceAdapter);
        rvWarehouses.setLayoutManager(new V7LinearLayoutManager(getContext(), V7LinearLayoutManager.VERTICAL, false));
        rvWarehouses.setAdapter(warehouseAdapter);

        sourceAdapter.setOnItemClickListener((adapter, view, position) -> {
            String url = sourceAdapter.getItem(position);
            if (url != null) {
                selectPushSource(url, tvWarehouseTip);
            }
        });
        sourceAdapter.setOnItemChildClickListener((adapter, view, position) -> {
            if (view.getId() == R.id.ivDelete) {
                String url = sourceAdapter.getItem(position);
                confirmDelete("删除该推送链接及其资源树？", () -> {
                    WarehouseRepo.removePushSource(url);
                    refreshSourceList();
                    if (url != null && url.equals(selectedSource)) {
                        selectedSource = null;
                        warehouseAdapter.setNewData(new java.util.ArrayList<>());
                        tvWarehouseTip.setText("接口列表（仓 > 接口 > 站源）");
                    }
                });
            }
        });
        sourceAdapter.setOnItemLongClickListener((adapter, view, position) -> {
            // 长按链接 = 刷新该链接整棵资源树
            String url = sourceAdapter.getItem(position);
            selectPushSource(url, tvWarehouseTip);
            return true;
        });

        warehouseAdapter.setCurrentApi(Hawk.get(HawkConfig.API_URL, ""));
        warehouseAdapter.setOnItemClickListener((adapter, view, position) -> {
            WarehouseBean bean = warehouseAdapter.getItem(position);
            if (bean != null) {
                applyWarehouse(bean);
            }
        });
        warehouseAdapter.setOnItemChildClickListener((adapter, view, position) -> {
            if (view.getId() == R.id.ivDelete) {
                WarehouseBean bean = warehouseAdapter.getItem(position);
                confirmDelete("删除该仓接口？", () -> {
                    if (bean != null) {
                        WarehouseRepo.addWarehouseBlacklist(bean.sourceUrl);
                        warehouseAdapter.remove(position);
                        Toast.makeText(getContext(), "已删除", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        btnAddSource.setOnClickListener(v -> showAddSourceDialog(tvWarehouseTip));
        btnRefreshTree.setOnClickListener(v -> {
            if (selectedSource == null) {
                Toast.makeText(getContext(), "请先选择推送链接", Toast.LENGTH_SHORT).show();
                return;
            }
            fetchWarehouses(selectedSource, tvWarehouseTip);
        });
        ivClose.setOnClickListener(v -> dismiss());

        // 扫码远程推送二维码（保留原能力）
        String address = ControlManager.get().getAddress(false);
        tvAddress.setText(String.format("手机扫码或浏览器访问 %s 推送配置", address));
        ivQRCode.setImageBitmap(QRCodeGen.generateBitmap(address + "api.html",
                AutoSizeUtils.mm2px(getContext(), 120), AutoSizeUtils.mm2px(getContext(), 120)));

        refreshSourceList();
    }

    private void refreshSourceList() {
        sourceAdapter.setNewData(new java.util.ArrayList<>(WarehouseRepo.getPushSources()));
        sourceAdapter.setSelectedUrl(selectedSource);
    }

    private void selectPushSource(String url, TextView tipView) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        selectedSource = url;
        sourceAdapter.setSelectedUrl(url);
        if (!WarehouseRepo.getPushSources().contains(url)) {
            WarehouseRepo.addPushSource(url);
            sourceAdapter.setNewData(new java.util.ArrayList<>(WarehouseRepo.getPushSources()));
        }
        fetchWarehouses(url, tipView);
    }

    /** 重拉该推送链接下的整棵资源树。 */
    private void fetchWarehouses(String url, TextView tipView) {
        tipView.setText("正在拉取资源树…");
        WarehouseRepo.fetchStoreHouse(url, new WarehouseRepo.WarehouseCallback() {
            @Override
            public void onSuccess(java.util.List<WarehouseBean> list) {
                if (getOwnerActivity() == null && !isShowing()) {
                    return;
                }
                warehouseAdapter.setNewData(list);
                tipView.setText("接口列表（" + list.size() + " 个仓）");
            }

            @Override
            public void onError(String msg) {
                warehouseAdapter.setNewData(new java.util.ArrayList<>());
                tipView.setText(msg);
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void applyWarehouse(WarehouseBean bean) {
        String oldApi = Hawk.get(HawkConfig.API_URL, "");
        if (bean.sourceUrl.equals(oldApi)) {
            dismiss();
            return;
        }
        new TipDialog(getContext(), "切换到「" + bean.sourceName + "」并重载配置？", "取消", "确定",
                new TipDialog.OnListener() {
                    @Override
                    public void left() {
                    }

                    @Override
                    public void right() {
                        SourceViewModel.clearRuntimeCache();
                        Hawk.put(HawkConfig.API_URL, bean.sourceUrl);
                        HistoryHelper.setApiHistory(bean.sourceUrl);
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

    private void showAddSourceDialog(TextView tipView) {
        final EditText input = new EditText(getContext());
        input.setHint("输入多仓/接口链接");
        input.setSingleLine(true);
        FrameLayout container = new FrameLayout(getContext());
        int pad = AutoSizeUtils.mm2px(getContext(), 20);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        new androidx.appcompat.app.AlertDialog.Builder(getContext())
                .setTitle("添加推送链接")
                .setView(container)
                .setPositiveButton("确定", (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (TextUtils.isEmpty(url)) {
                        return;
                    }
                    WarehouseRepo.addPushSource(url);
                    refreshSourceList();
                    selectPushSource(url, tipView);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(String tip, Runnable action) {
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
