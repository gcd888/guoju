package com.github.gcd888.guoju.ui.dialog;

import android.content.Context;

import androidx.annotation.NonNull;

import com.github.gcd888.guoju.R;

import org.jetbrains.annotations.NotNull;

public class AboutDialog extends BaseDialog {

    public AboutDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_about);
    }
}