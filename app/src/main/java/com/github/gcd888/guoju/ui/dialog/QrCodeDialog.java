package com.github.gcd888.guoju.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.gcd888.guoju.R;
import com.github.gcd888.guoju.event.RefreshEvent;
import com.github.gcd888.guoju.ui.tv.QRCodeGen;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import org.jetbrains.annotations.NotNull;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 推送配置二维码弹窗：大二维码（白底衬框 + 静区）+ 完整地址 + 推送提示。
 * 点击配置弹窗右上角小二维码弹出；右上角 X 默认聚焦，遥控器确认键直接关闭。
 * 手机推送期间（TYPE_PUSH_CONFIG_START）朦胧转圈遮罩，完成（DONE）自动关闭本弹窗，
 * 露出下层配置弹窗正常展示（数据刷新由配置弹窗自行处理）。
 */
public class QrCodeDialog extends BaseDialog {

    public QrCodeDialog(@NonNull @NotNull Context context, String address) {
        super(context);
        setContentView(R.layout.dialog_qr_code);
        setCanceledOnTouchOutside(false);
        // 订阅扫码推送事件：开始转圈遮罩，完成自动关闭（dismiss 时注销，防泄漏）
        EventBus.getDefault().register(this);
        setOnDismissListener(dialog -> EventBus.getDefault().unregister(QrCodeDialog.this));
        ImageView ivClose = findViewById(R.id.ivClose);
        ivClose.setOnClickListener(v -> dismiss());
        ivClose.requestFocus();
        TextView tvAddress = findViewById(R.id.tvAddress);
        tvAddress.setText(address);
        ImageView ivQRCode = findViewById(R.id.ivQRCode);
        ivQRCode.setImageBitmap(QRCodeGen.generateBitmap(address,
                AutoSizeUtils.mm2px(getContext(), 240), AutoSizeUtils.mm2px(getContext(), 240), 2));
    }

    /** 推送事件：开始朦胧转圈；完成后自动关闭本弹窗（成功/失败均关闭，结果由 Toast 提示）。 */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPushEvent(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_PUSH_CONFIG_START) {
            findViewById(R.id.flPushLoading).setVisibility(View.VISIBLE);
        } else if (event.type == RefreshEvent.TYPE_PUSH_CONFIG_DONE) {
            dismiss();
        }
    }
}
