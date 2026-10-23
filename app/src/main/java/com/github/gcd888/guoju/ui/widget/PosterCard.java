package com.github.gcd888.guoju.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.gcd888.guoju.R;
import com.github.gcd888.guoju.ui.tv.focus.FocusScaleHelper;
import com.github.gcd888.guoju.util.ImgUtil;

/**
 * Apple TV 风格竖版海报卡（2:3），手机/TV 共用。
 * TV 端自动获得焦点缩放动效（FocusScaleHelper 内部 isTv 分流）。
 */
public class PosterCard extends FrameLayout {

    private final ImageView ivPoster;
    private final TextView tvTitle;
    private final TextView tvBadge;

    public PosterCard(@NonNull Context context) {
        this(context, null);
    }

    public PosterCard(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PosterCard(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        LayoutInflater.from(context).inflate(R.layout.view_poster_card, this, true);
        ivPoster = findViewById(R.id.ivPoster);
        tvTitle = findViewById(R.id.tvTitle);
        tvBadge = findViewById(R.id.tvBadge);
        // 海报卡用放大档 1.10（tvOS 海报聚焦观感），文字类控件保持默认 1.06
        FocusScaleHelper.bind(this, FocusScaleHelper.POSTER_SCALE);
    }

    /** 加载海报图，label 用于无图时的文字占位。 */
    public void setPoster(String url, String label) {
        int radius = getResources().getDimensionPixelSize(R.dimen.atv_radius_card);
        int width = getResources().getDimensionPixelSize(R.dimen.atv_poster_w);
        int height = getResources().getDimensionPixelSize(R.dimen.atv_poster_h);
        ImgUtil.load(url, ivPoster, radius, width, height, label);
    }

    public void setTitle(String title) {
        tvTitle.setText(title == null ? "" : title);
        tvTitle.setVisibility(title == null || title.isEmpty() ? GONE : VISIBLE);
    }

    /** 角标（如「豆瓣热播」「会员」等），传空隐藏。 */
    public void setBadge(String badge) {
        if (badge == null || badge.isEmpty()) {
            tvBadge.setVisibility(GONE);
        } else {
            tvBadge.setText(badge);
            tvBadge.setVisibility(VISIBLE);
        }
    }

    public ImageView getPosterView() {
        return ivPoster;
    }
}
