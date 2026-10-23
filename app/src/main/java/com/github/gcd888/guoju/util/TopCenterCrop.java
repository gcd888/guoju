package com.github.gcd888.guoju.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Hero 全屏底图裁剪（顶部对齐）：等比缩放铺满目标尺寸后，垂直方向从图片顶部开始向下截取。
 * <p>
 * 与 Glide 内置 CenterCrop 的区别：CenterCrop 双向居中裁剪——竖版海报放大铺满 16:9 屏时
 * 顶部/底部各裁一半，片名 Logo（多位于画面上部）容易被裁掉；本实现垂直 dy=0 从上往下截，
 * 水平超出时仍居中截（超宽横幅两侧等量裁）。
 */
public class TopCenterCrop extends BitmapTransformation {
    private static final String ID = "com.github.gcd888.guoju.util.TopCenterCrop";
    private static final byte[] ID_BYTES = ID.getBytes(StandardCharsets.UTF_8);

    @Override
    protected Bitmap transform(@NonNull BitmapPool pool, @NonNull Bitmap toTransform,
                               int outWidth, int outHeight) {
        if (outWidth <= 0 || outHeight <= 0
                || (toTransform.getWidth() == outWidth && toTransform.getHeight() == outHeight)) {
            return toTransform;
        }
        Matrix matrix = new Matrix();
        // 等比缩放铺满：取较大缩放比，保证两维均不小于目标（与 CenterCrop 一致）
        float scale = Math.max(
                outWidth / (float) toTransform.getWidth(),
                outHeight / (float) toTransform.getHeight());
        matrix.setScale(scale, scale);
        // 水平居中（dx≤0 为水平超出量的一半）、垂直顶部对齐（dy=0，从上往下截取）
        float dx = (outWidth - toTransform.getWidth() * scale) * 0.5f;
        matrix.postTranslate(dx, 0f);
        Bitmap result = pool.get(outWidth, outHeight,
                toTransform.getConfig() != null ? toTransform.getConfig() : Bitmap.Config.ARGB_8888);
        result.setHasAlpha(toTransform.hasAlpha());
        Canvas canvas = new Canvas(result);
        canvas.drawBitmap(toTransform, matrix, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        canvas.setBitmap(null);
        return result;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TopCenterCrop;
    }

    @Override
    public int hashCode() {
        return ID.hashCode();
    }

    @Override
    public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
        messageDigest.update(ID_BYTES);
    }
}
