package com.onesay.assistant.v2.pclink;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * [pclink] 系统分享菜单里的「OneSay · 下到电脑」：别的 App 分享一段文字 / 链接过来 → 挑出第一个 http(s) 链接 → 选清晰度 → 发给电脑下载。
 * 只弹选择框，一定要你点「下载」才发；没配对 / 电脑没连上只提示，不在手机本地下载。透明页面，选完就关。
 */
public final class PcShareActivity extends Activity {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        String text = null;
        if (in != null && Intent.ACTION_SEND.equals(in.getAction())) {
            CharSequence t = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t == null) t = in.getStringExtra(Intent.EXTRA_SUBJECT);
            text = t == null ? null : t.toString();
        }
        String url = PcMedia.tidy(text);
        if (url == null) {
            Toast.makeText(this, "分享的内容里没有 http / https 链接", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (!PcLink.active(this)) {
            Toast.makeText(this, "需要连上电脑：先在 OneSay「电脑联动」里扫码配对", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        PcMediaAndroid.chooser(this, url, new Runnable() {
            @Override public void run() { finish(); }
        });
    }
}
