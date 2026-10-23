package com.github.gcd888.guoju.ui.dialog;

import android.content.Context;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.gcd888.guoju.R;
import com.github.gcd888.guoju.server.ControlManager;
import com.github.gcd888.guoju.ui.tv.QRCodeGen;

import org.jetbrains.annotations.NotNull;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class RemoteDialog extends BaseDialog {
    private ImageView ivQRCode;
    private TextView tvAddress;

    public RemoteDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_remote);
        setCanceledOnTouchOutside(false);
        ivQRCode = findViewById(R.id.ivQRCode);
        tvAddress = findViewById(R.id.tvAddress);
        // 右上角关闭 X：默认聚焦，遥控器确认键直接关闭弹窗
        ImageView ivClose = findViewById(R.id.ivClose);
        ivClose.setOnClickListener(v -> dismiss());
        ivClose.requestFocus();
        refreshQRCode();
    }

    private void refreshQRCode() {
        String address = ControlManager.get().getAddress(false);
        tvAddress.setText(String.format("手机/电脑扫描上方二维码或者直接浏览器访问地址\n%s", address));
        ivQRCode.setImageBitmap(QRCodeGen.generateBitmap(address, AutoSizeUtils.mm2px(getContext(), 240), AutoSizeUtils.mm2px(getContext(), 240)));
    }
}
