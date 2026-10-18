package com.github.tvbox.osc.ui.adapter;

import android.view.View;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.ConfigNodeBean;

import java.util.ArrayList;

/**
 * 配置弹窗三级通用列表适配器（名称+删除按钮+生效✓）。
 */
public class ConfigAdapter extends BaseQuickAdapter<ConfigNodeBean, BaseViewHolder> {

    /** 当前生效的配置链接，用于标记"✓"。 */
    private String currentUrl = "";

    public ConfigAdapter() {
        super(R.layout.item_config_row, new ArrayList<>());
    }

    public void setCurrentUrl(String url) {
        currentUrl = url == null ? "" : url;
        notifyDataSetChanged();
    }

    @Override
    protected void convert(BaseViewHolder helper, ConfigNodeBean item) {
        TextView tvName = helper.getView(R.id.tvName);
        TextView tvSelected = helper.getView(R.id.ivSelected);
        tvName.setText(item.name);
        boolean selected = !currentUrl.isEmpty() && currentUrl.equals(item.url);
        tvSelected.setVisibility(selected ? View.VISIBLE : View.GONE);
        helper.addOnClickListener(R.id.ivDelete);
    }
}
