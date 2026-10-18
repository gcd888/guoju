package com.github.tvbox.osc.ui.adapter;

import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.WarehouseBean;

import java.util.ArrayList;

/**
 * 多仓对话框右栏：当前推送链接下的仓（接口）列表。
 */
public class WarehouseAdapter extends BaseQuickAdapter<WarehouseBean, BaseViewHolder> {

    /** 当前生效的接口配置链接，用于标记"当前"。 */
    private String currentApi;

    public WarehouseAdapter() {
        super(R.layout.item_warehouse, new ArrayList<>());
    }

    public void setCurrentApi(String api) {
        currentApi = api;
        notifyDataSetChanged();
    }

    @Override
    protected void convert(BaseViewHolder helper, WarehouseBean item) {
        com.github.tvbox.osc.ui.tv.focus.FocusScaleHelper.bind(helper.itemView);
        TextView tvName = helper.getView(R.id.tvWarehouseName);
        TextView tvUrl = helper.getView(R.id.tvWarehouseUrl);
        TextView tvMark = helper.getView(R.id.tvCurrentMark);
        tvName.setText(item.sourceName);
        tvUrl.setText(item.sourceUrl);
        boolean current = item.sourceUrl != null && item.sourceUrl.equals(currentApi);
        tvMark.setVisibility(current ? View.VISIBLE : View.GONE);
        tvName.setTextColor(ContextCompat.getColor(mContext,
                current ? R.color.atv_accent : R.color.atv_text_primary));
        helper.addOnClickListener(R.id.ivDelete);
    }
}
