package cn.hongguo.tv.ui;

import android.os.Bundle;
import java.io.IOException;
import java.lang.reflect.Field;
import cn.hongguo.tv.account.Account;
import cn.hongguo.tv.api.Api;
import cn.hongguo.tv.net.Http;

/** Exists only in the .qa debug APK and blocks transport before PlayerActivity starts a worker. */
public final class SeasonPlayerFixtureActivity extends PlayerActivity {
    @Override protected void onCreate(Bundle saved) {
        if (!getPackageName().endsWith(".qa")) throw new IllegalStateException("只允许独立 QA 测试包");
        if (Account.load(this).loggedIn()) throw new IllegalStateException("离线 QA 请使用未登录的独立测试包");
        super.onCreate(saved);
        try {
            Api offline = new Api(this);
            Field signer = Api.class.getDeclaredField("signerAdapter");
            signer.setAccessible(true);
            signer.set(offline, (Http.Signer) (url, headers, body) -> { throw new IOException("受控离线"); });
            Field api = PlayerActivity.class.getDeclaredField("api");
            api.setAccessible(true);
            api.set(this, offline);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
