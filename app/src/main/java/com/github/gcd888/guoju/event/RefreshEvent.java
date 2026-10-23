package com.github.gcd888.guoju.event;

/**
 * @author pj567
 * @date :2021/1/6
 * @description:
 */
public class RefreshEvent {
    public static final int TYPE_REFRESH = 0;
    public static final int TYPE_HISTORY_REFRESH = 1;
    public static final int TYPE_QUICK_SEARCH = 2;
    public static final int TYPE_QUICK_SEARCH_SELECT = 3;
    public static final int TYPE_QUICK_SEARCH_WORD = 4;
    public static final int TYPE_QUICK_SEARCH_WORD_CHANGE = 5;
    public static final int TYPE_SEARCH_RESULT = 6;
    public static final int TYPE_QUICK_SEARCH_RESULT = 7;
    public static final int TYPE_API_URL_CHANGE = 8;
    public static final int TYPE_PUSH_URL = 9;
    public static final int TYPE_EPG_URL_CHANGE = 10;
    public static final int TYPE_SETTING_SEARCH_TV = 11;
    public static final int TYPE_SUBTITLE_SIZE_CHANGE = 12;
    public static final int TYPE_FILTER_CHANGE = 13;
    public static final int TYPE_LIVE_API_URL_CHANGE = 14;
    public static final int TYPE_HOME_SOURCE_CHANGE = 15;
    public static final int TYPE_SET_DANMU_SETTINGS = 18;
    public static final int TYPE_DANMU_REFRESH = 19;
    public static final int TYPE_PLAY_QUALITY = 20;
    /** 设置-首页多行开关变化：主页货架/Hero 文字区即时重排（HomeTabFragment.notifyRecStyleChanged）。 */
    public static final int TYPE_HOME_REC_STYLE_CHANGE = 21;
    /** 扫码推送配置开始（obj=推送的 configUrl）：配置弹窗/大码弹窗显示解析遮罩。 */
    public static final int TYPE_PUSH_CONFIG_START = 22;
    /** 扫码推送配置完成（obj=ConfigPushHandler.PushResult）：遮罩消失/大码弹窗关闭/列表刷新。 */
    public static final int TYPE_PUSH_CONFIG_DONE = 23;
    public int type;
    public Object obj;

    public RefreshEvent(int type) {
        this.type = type;
    }

    public RefreshEvent(int type, Object obj) {
        this.type = type;
        this.obj = obj;
    }
}
