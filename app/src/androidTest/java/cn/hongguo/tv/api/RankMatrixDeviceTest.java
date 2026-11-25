package cn.hongguo.tv.api;

import android.test.InstrumentationTestCase;
import cn.hongguo.tv.sign.RequestSigner;
import java.util.ArrayList;
import java.util.List;

/** Board matrix: 4 categories, desktop-equivalent board sets, full depth. */
public class RankMatrixDeviceTest extends InstrumentationTestCase {
    public void testMatrix() throws Exception {
        android.content.Context ctx = getInstrumentation().getTargetContext();
        RequestSigner.bind(ctx);
        Api api = new Api(ctx);
        String[][] combos = {
                {"series","recommend"},{"series","new"},
                {"comic","recommend"},{"comic","hot"},{"comic","new"},
                {"short_play","recommend"},{"short_play","hot"},{"short_play","new"},
                {"ai_series","recommend"},{"ai_series","hot"},{"ai_series","new"}
        };
        List<String> failures = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        for (String[] combo : combos) {
            try {
                List<Api.Series> rows = api.rank(combo[0], combo[1], 200);
                summary.append(combo[0]).append('/').append(combo[1]).append('=').append(rows.size()).append(' ');
                if (rows.size() < 17) failures.add(combo[0] + "/" + combo[1] + " only " + rows.size());
            } catch (Exception e) {
                failures.add(combo[0] + "/" + combo[1] + " " + e.getMessage());
            }
        }
        android.util.Log.i("HgMatrix", summary.toString());
        assertTrue("榜单深度不足: " + failures, failures.isEmpty());
    }
}
