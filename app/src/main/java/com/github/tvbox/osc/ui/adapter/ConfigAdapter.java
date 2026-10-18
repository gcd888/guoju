package com.github.tvbox.osc.ui.adapter;

import android.view.View;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.ConfigNodeBean;

import java.util.ArrayList;

/**
 * 配置弹窗三列通用列表适配器（名称+删除按钮+生效✓+勾选态）。
 * 勾选态用节点对象引用比对（虚拟节点与上级 url 相同，不可用 url 比对选中态）；
 * 生效✓仍用 url 比对。
 */
public class ConfigAdapter extends BaseQuickAdapter<ConfigNodeBean, BaseViewHolder> {

    /** 当前生效的配置链接，用于标记"✓"。 */
    private String currentUrl = "";
    /** 勾选态节点（列内对象引用，列数据重建后由弹窗置空）。 */
    private ConfigNodeBean selected;

    public ConfigAdapter() {
        super(R.layout.item_config_row, new ArrayList<>());
    }

    public void setCurrentUrl(String url) {
        currentUrl = url == null ? "" : url;
        notifyDataSetChanged();
    }

    /** 设置勾选态节点（传 null 清除），行背景切换为橙色描边选中样式。 */
    public void setSelected(ConfigNodeBean node) {
        selected = node;
        notifyDataSetChanged();
    }

    @Override
    protected void convert(BaseViewHolder helper, ConfigNodeBean item) {
        TextView tvName = helper.getView(R.id.tvName);
        TextView tvSelected = helper.getView(R.id.ivSelected);
        tvName.setText(item.name);
        // 勾选态：节点引用比对，覆盖默认两态背景为选中描边样式
        helper.itemView.setBackgroundResource(item == selected
                ? R.drawable.shape_config_row_selected
                : R.drawable.button_dialog_vod);
        // 生效✓：url 比对（真实生效链接，虚拟节点与上级同 url 时多处同时显示属预期）
        boolean active = !currentUrl.isEmpty() && currentUrl.equals(item.url);
        tvSelected.setVisibility(active ? View.VISIBLE : View.GONE);
        helper.addOnClickListener(R.id.ivDelete);
    }
}
