package com.github.gcd888.guoju.ui.adapter;

import android.text.TextUtils;
import android.util.Log;
import android.widget.ImageView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.gcd888.guoju.R;
import com.github.gcd888.guoju.api.ApiConfig;
import com.github.gcd888.guoju.bean.Movie;
import com.github.gcd888.guoju.util.ImgUtil;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class FastSearchAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {
    private static final String TAG = "FastSearchAdapter";

    public FastSearchAdapter() {
        super(R.layout.item_search, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {
        helper.setText(R.id.tvName, item.name);
        helper.setText(R.id.tvSite, ApiConfig.get().getSource(item.sourceKey).getName());
        helper.setVisible(R.id.tvNote, item.note != null && !item.note.isEmpty());
        if (item.note != null && !item.note.isEmpty()) helper.setText(R.id.tvNote, item.note);
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        if (!TextUtils.isEmpty(item.pic)) {
            ImgUtil.load(item.pic, ivThumb, AutoSizeUtils.mm2px(mContext, 10), AutoSizeUtils.mm2px(mContext, 214), AutoSizeUtils.mm2px(mContext, 280), item.name);
        } else {
            Log.d(TAG, "empty image for item: " + item.name);
            ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name));
        }
    }
}