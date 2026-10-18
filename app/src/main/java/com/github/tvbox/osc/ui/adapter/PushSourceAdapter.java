package com.github.tvbox.osc.ui.adapter;

import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;

import java.util.ArrayList;

/**
 * 多仓对话框左栏：推送链接列表。
 */
public class PushSourceAdapter extends BaseQuickAdapter<String, BaseViewHolder> {

    /** 当前左栏选中的推送链接，高亮显示。 */
    private String selectedUrl;

    public PushSourceAdapter() {
        super(R.layout.item_push_source, new ArrayList<>());
    }

    public void setSelectedUrl(String url) {
        selectedUrl = url;
        notifyDataSetChanged();
    }

    @Override
    protected void convert(BaseViewHolder helper, String item) {
        com.github.tvbox.osc.ui.tv.focus.FocusScaleHelper.bind(helper.itemView);
        TextView tvSourceUrl = helper.getView(R.id.tvSourceUrl);
        tvSourceUrl.setText(item);
        boolean selected = item.equals(selectedUrl);
        tvSourceUrl.setTextColor(ContextCompat.getColor(mContext,
                selected ? R.color.atv_accent : R.color.atv_text_secondary));
        helper.addOnClickListener(R.id.ivDelete);
    }
}
