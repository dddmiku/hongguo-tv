package cn.hongguo.tv.ui;

import android.os.Bundle;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import cn.hongguo.tv.account.Account;

/** Debug-only transport; production search workers and result handlers remain unchanged. */
public final class SearchFixtureActivity extends SearchActivity {
    public static final String SLOW_QUERY = "慢查询 A";
    public static final String FAST_QUERY = "成功查询 B";
    public static final String RESULT_TITLE = "新查询的受控结果";
    public final CountDownLatch slowStarted = new CountDownLatch(1);
    public final CountDownLatch releaseSlow = new CountDownLatch(1);
    public final AtomicReference<Thread> slowThread = new AtomicReference<>();
    public boolean slowSuccess;

    @Override protected void onCreate(Bundle saved) {
        if (!getPackageName().endsWith(".qa")) throw new IllegalStateException("只允许独立 QA 测试包");
        if (Account.load(this).loggedIn()) throw new IllegalStateException("搜索 QA 需要未登录的独立测试包");
        super.onCreate(saved);
    }

    @Override protected JSONObject requestSearch(String query) throws Exception {
        if (SLOW_QUERY.equals(query)) {
            slowThread.set(Thread.currentThread());
            slowStarted.countDown();
            if (!releaseSlow.await(8, TimeUnit.SECONDS)) throw new IOException("受控搜索等待超时");
            if (!slowSuccess) return new JSONObject().put("code", 1099);
            return success("已结束页面的旧结果");
        }
        if (FAST_QUERY.equals(query)) return success(RESULT_TITLE);
        throw new IOException("未定义的受控查询");
    }

    private JSONObject success(String title) throws Exception {
        JSONObject series = new JSONObject().put("series_id", "9913377000000000001")
                .put("title", title).put("cover", "").put("episode_cnt", 12);
        JSONObject cell = new JSONObject().put("video_data", new JSONArray().put(series));
        JSONObject tab = new JSONObject().put("data", new JSONArray().put(cell));
        return new JSONObject().put("code", 0).put("search_tabs", new JSONArray().put(tab));
    }
}
