package cn.hongguo.tv.ui;

import android.os.Bundle;
import cn.hongguo.tv.account.Account;
import cn.hongguo.tv.api.Api;
import java.util.ArrayList;
import java.util.List;

/** Records the same real response delivered to the production rankings view. */
public final class RankingsLiveFixtureActivity extends RankingsActivity {
    public volatile List<Api.Series> returned;

    @Override protected void onCreate(Bundle saved) {
        if (!getPackageName().endsWith(".qa") || Account.load(this).loggedIn())
            throw new IllegalStateException("实时排行榜验收仅限未登录的独立 QA 包");
        super.onCreate(saved);
    }

    @Override protected List<Api.Series> requestRank(String category, String board) throws Exception {
        List<Api.Series> rows = super.requestRank(category, board);
        returned = new ArrayList<>(rows);
        return rows;
    }
}
