package com.github.gcd888.guoju.ui.dialog;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.gcd888.guoju.R;

import org.jetbrains.annotations.NotNull;

public class TipDialog extends BaseDialog {

    public TipDialog(@NonNull @NotNull Context context, String tip, String left, String right, OnListener listener) {
        super(context);
        setContentView(R.layout.dialog_tip);
        setCanceledOnTouchOutside(false);
        // 压暗背景：常叠在配置等弹窗之上，无遮罩时两层面板同为深灰难以分辨
        android.view.Window w = getWindow();
        if (w != null) {
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.6f;
            w.setAttributes(lp);
        }
        TextView tipInfo = findViewById(R.id.tipInfo);
        TextView leftBtn = findViewById(R.id.leftBtn);
        TextView rightBtn = findViewById(R.id.rightBtn);
        tipInfo.setText(tip);
        leftBtn.setText(left);
        rightBtn.setText(right);
        // 点击任一按钮后统一关闭弹窗再执行回调：回调方可空实现（如"取消"），
        // 修复"点取消不关窗像失效"的问题；dismiss 幂等，回调内再 dismiss 无副作用
        leftBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.left();
                dismiss();
            }
        });
        rightBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.right();
                dismiss();
            }
        });
        setOnCancelListener(new OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                listener.cancel();
            }
        });
    }

    public interface OnListener {
        void left();

        void right();

        void cancel();
    }
}
