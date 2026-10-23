package com.github.gcd888.guoju.ui.adapter;

import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.gcd888.guoju.R;
import com.github.gcd888.guoju.bean.ConfigNodeBean;

import java.util.ArrayList;

/**
 * 配置弹窗三列通用列表适配器（名称+删除按钮+选中态）。
 * 选中态用列内 url 比对：列表重建时节点是重新 new 的对象，引用必不等；
 * 列内 url 唯一有去重保证，跨列同 url 不冲突（各 adapter 只比对自己列的数据）。
 */
public class ConfigAdapter extends BaseQuickAdapter<ConfigNodeBean, BaseViewHolder> {

    /** 选中条目的 url（列内唯一；空串=无选中）。 */
    private String selectedUrl = "";

    public ConfigAdapter() {
        super(R.layout.item_config_row, new ArrayList<>());
    }

    /** 设置选中 url（null 归一为空串），选中行背景切换为橙色描边选中样式。 */
    public void setSelectedUrl(String url) {
        selectedUrl = url == null ? "" : url;
        notifyDataSetChanged();
    }

    @Override
    protected void convert(BaseViewHolder helper, ConfigNodeBean item) {
        TextView tvName = helper.getView(R.id.tvName);
        tvName.setText(item.name);
        // 选中态：列内 url 比对，覆盖默认两态背景为橙色描边选中样式
        // （✓ 标记已弃用，ivSelected 控件在布局里默认 gone，闲置即可）
        helper.itemView.setBackgroundResource(item.url != null && item.url.equals(selectedUrl)
                ? R.drawable.shape_config_row_selected
                : R.drawable.button_dialog_vod);
        helper.addOnClickListener(R.id.ivDelete);
    }
}
