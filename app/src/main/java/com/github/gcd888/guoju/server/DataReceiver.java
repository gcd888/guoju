package com.github.gcd888.guoju.server;

/**
 * @author pj567
 * @date :2021/1/5
 * @description:
 */
public interface DataReceiver {

    /**
     * @param text
     */
    void onTextReceived(String text);

    /** 扫码推送配置（name 可空，TV 端自动命名；url 自动判型扫描落库）。 */
    void onConfigPushed(String name, String url);

    void onPushReceived(String url);
}
