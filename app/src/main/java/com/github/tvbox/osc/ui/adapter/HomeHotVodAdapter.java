package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.ui.tv.focus.FocusScaleHelper;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ImgUtil;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class HomeHotVodAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {

    public HomeHotVodAdapter(ImgUtil.Style style, String tvRate) {
        super(R.layout.item_user_hot_vod, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {
        FocusScaleHelper.bind(helper.itemView);
        FrameLayout tvDel = helper.getView(R.id.delFrameLayout);
        tvDel.setVisibility(HawkConfig.hotVodDelete ? View.VISIBLE : View.GONE);

        // Apple TV 风格货架不放角标
        TextView tvRate = helper.getView(R.id.tvRate);
        tvRate.setVisibility(View.GONE);

        TextView tvNote = helper.getView(R.id.tvNote);
        if (item.note == null || item.note.isEmpty()) {
            tvNote.setVisibility(View.GONE);
        } else {
            tvNote.setText(item.note);
            tvNote.setVisibility(View.VISIBLE);
        }
        helper.setText(R.id.tvName, item.name);
        ImageView ivThumb = helper.getView(R.id.ivThumb);

        String pic = item.pic == null ? "" : item.pic.trim();
        int radius = mContext.getResources().getDimensionPixelSize(R.dimen.atv_radius_card);
        if (!TextUtils.isEmpty(pic)) {
            if (ImgUtil.isBase64Image(pic)) {
                ivThumb.setImageBitmap(ImgUtil.decodeBase64ToBitmap(pic));
            } else {
                ImgUtil.load(pic, ivThumb, radius, AutoSizeUtils.mm2px(mContext, 150), AutoSizeUtils.mm2px(mContext, 190), item.name);
            }
        } else {
            ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name));
        }
    }
}