package cn.hongguo.tv.ui;

import android.os.Bundle;
import cn.hongguo.tv.account.Account;
import cn.hongguo.tv.api.Api;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated data for board switching, request races and remote focus. */
public final class RankingsFixtureActivity extends RankingsActivity {
    public final List<String> requests = new CopyOnWriteArrayList<>();
    public final CountDownLatch slowStarted = new CountDownLatch(1);
    public final CountDownLatch releaseSlow = new CountDownLatch(1);
    public final CountDownLatch slowFinished = new CountDownLatch(1);
    public final AtomicInteger newAttempts = new AtomicInteger();
    public volatile boolean slowNew, failSlow, failNewOnce, emptyNew;

    @Override protected void onCreate(Bundle saved) {
        if (!getPackageName().endsWith(".qa") || Account.load(this).loggedIn())
            throw new IllegalStateException("排行榜夹具仅限未登录的独立 QA 包");
        super.onCreate(saved);
    }

    @Override protected List<Api.Series> requestRank(String category, String board) throws Exception {
        requests.add(category + "/" + board);
        if ("new".equals(board)) {
            int attempt = newAttempts.incrementAndGet();
            if (slowNew) {
                slowStarted.countDown();
                try {
                    if (!releaseSlow.await(10, TimeUnit.SECONDS)) throw new IOException("受控等待超时");
                    if (failSlow) throw new IOException("旧榜单的受控错误");
                    return result("旧榜单结果");
                } finally { slowFinished.countDown(); }
            }
            if (failNewOnce && attempt == 1) throw new IOException("受控加载失败");
            if (emptyNew) return Collections.emptyList();
        }
        return result(category + "/" + board);
    }

    private List<Api.Series> result(String title) {
        Api.Series series = new Api.Series();
        series.seriesId = "9913377000000000081";
        series.title = title;
        series.episodeCount = 12;
        series.rank = 1;
        return Collections.singletonList(series);
    }
}
