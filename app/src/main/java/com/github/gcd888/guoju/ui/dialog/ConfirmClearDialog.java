package com.github.gcd888.guoju.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.gcd888.guoju.R;
import com.github.gcd888.guoju.bean.VodInfo;
import com.github.gcd888.guoju.cache.RoomDataManger;
import com.github.gcd888.guoju.cache.VodCollect;
import com.github.gcd888.guoju.ui.activity.CollectActivity;
import com.github.gcd888.guoju.ui.activity.HistoryActivity;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class ConfirmClearDialog extends BaseDialog {
    private final TextView tvYes;
    private final TextView tvNo;

    public ConfirmClearDialog(@NonNull @NotNull Context context, String type) {
        this(context, type, RoomDataManger.MEDIA_TYPE_VOD);
    }

    public ConfirmClearDialog(@NonNull @NotNull Context context, String type, int mediaType) {
        super(context);
        setContentView(R.layout.dialog_confirm);
        setCanceledOnTouchOutside(true);
        tvYes = findViewById(R.id.btnConfirm);
        tvNo = findViewById(R.id.btnCancel);

        tvYes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // if removing all Favorites
                if (type == "Collect") {
                    List<VodCollect> vodInfoList = new ArrayList<>();
                    CollectActivity.collectAdapter.setNewData(vodInfoList);
                    CollectActivity.collectAdapter.notifyDataSetChanged();
                    RoomDataManger.deleteVodCollectAllByType(mediaType);
                    // if removing all History
                } else if (type == "History") {
                    List<VodInfo> vodInfoList = new ArrayList<>();
                    HistoryActivity.historyAdapter.setNewData(vodInfoList);
                    HistoryActivity.historyAdapter.notifyDataSetChanged();
                    RoomDataManger.deleteVodRecordAllByType(mediaType);
                }

                ConfirmClearDialog.this.dismiss();
            }
        });
        tvNo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ConfirmClearDialog.this.dismiss();
            }
        });
    }

}