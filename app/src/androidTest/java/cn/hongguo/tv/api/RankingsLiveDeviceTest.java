package cn.hongguo.tv.api;

import android.test.InstrumentationTestCase;
import android.content.Intent;
import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import cn.hongguo.tv.account.Account;
import cn.hongguo.tv.sign.RequestSigner;
import cn.hongguo.tv.ui.RankingsActivity;
import cn.hongguo.tv.ui.RankingsLiveFixtureActivity;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Guest-only live API checks, including the series entry's playable episode metadata. */
public class RankingsLiveDeviceTest extends InstrumentationTestCase {
    private Api api;
    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue(getInstrumentation().getTargetContext().getPackageName().endsWith(".qa"));
        assertFalse(Account.load(getInstrumentation().getTargetContext()).loggedIn());
        RequestSigner.bind(getInstrumentation().getTargetContext());
        api = new Api(getInstrumentation().getTargetContext());
    }

    public void testSeriesRecommendationReturnsRankedSeriesAndAPlayableEntry() throws Exception {
        List<Api.Series> rows = verify("series", "recommend");
        Api.Series first = rows.get(0);
        assertTrue("官方系列剧季说明缺失", first.category.contains("季"));
        assertFalse("官方推荐信息缺失", first.hot.isEmpty());
        JSONObject detail = api.episodes(first.seriesId);
        JSONObject data = detail.optJSONObject("data");
        JSONObject entry = data == null ? null : data.optJSONObject(first.seriesId);
        assertNotNull("系列入口无法加载真实剧集", entry);
        assertNotNull("系列入口没有剧集元数据", entry.optJSONObject("video_data"));
        assertTrue("系列入口可播放集列表为空", Api.parseEpisodes(detail, first.seriesId).length() > 0);
    }

    public void testNewSeasonBoardReturnsOfficialSeasonDescriptions() throws Exception {
        List<Api.Series> rows = verify("series", "new");
        assertTrue(rows.get(0).category.contains("季"));
        assertFalse(rows.get(0).hot.isEmpty());
    }

    public void testExistingComicRecommendationStillWorks() throws Exception {
        verify("comic", "recommend");
    }

    public void testActualRankingsScreenLoadsAvailableTopFiftyAndOpensPlayableEpisodes() throws Exception {
        RankingsLiveFixtureActivity activity = (RankingsLiveFixtureActivity) getInstrumentation().startActivitySync(
                new Intent(getInstrumentation().getTargetContext(), RankingsLiveFixtureActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        try {
            Field property = RankingsActivity.class.getDeclaredField("results");
            property.setAccessible(true);
            LinearLayout results = (LinearLayout) property.get(activity);
            waitFor("真实排行榜请求未结束", () -> activity.returned != null && main(() -> results.getChildCount() > 0));
            List<Api.Series> expected = activity.returned;
            assertTrue("真实榜单没有可用条目", expected.size() > 0 && expected.size() <= 50);
            Field statusProperty = RankingsActivity.class.getDeclaredField("status");
            statusProperty.setAccessible(true);
            android.widget.TextView statusView = (android.widget.TextView) statusProperty.get(activity);
            android.os.Bundle rendered = new android.os.Bundle();
            rendered.putInt("renderedRankingRows", main(results::getChildCount));
            rendered.putString("rankingStatus", main(() -> statusView.getText().toString()));
            getInstrumentation().sendStatus(2, rendered);
            assertEquals("真实排行榜与上游可用条目不符", expected.size(), (int) main(results::getChildCount));
            for (int index = 0; index < expected.size(); index++) {
                final int position = index;
                assertEquals("页面改变了同次官方响应的排序", "rank-item:" + expected.get(index).seriesId,
                        main(() -> results.getChildAt(position).getTag()));
            }
            assertTrue(main(() -> activity.getWindow().getDecorView().findViewWithTag("rank-category:series").isShown()));
            assertTrue(main(() -> activity.getWindow().getDecorView().findViewWithTag("rank-board:new").isShown()));
            assertTrue(main(() -> {
                Rect visible = new Rect();
                View first = results.getChildAt(0);
                return first.getGlobalVisibleRect(visible) && visible.width() > 0
                        && visible.left >= 0 && visible.right <= activity.getResources().getDisplayMetrics().widthPixels
                        && first.getContentDescription().toString().startsWith("第 1 名");
            }));
            main(() -> { View first = results.getChildAt(0); first.requestFocus(); return first.performClick(); });
            waitFor("系列剧入口没有实际载入可选的第一集", () -> {
                AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
                if (root == null) return false;
                try { return !root.findAccessibilityNodeInfosByText("播放第 1 集").isEmpty(); }
                finally { root.recycle(); }
            });
            getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            waitFor("关闭详情后未回到排行榜", activity::hasWindowFocus);
            assertTrue(main(() -> activity.getCurrentFocus() == results.getChildAt(0)));
        } finally {
            if (!activity.hasWindowFocus()) getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            main(() -> { activity.finish(); return null; });
            getInstrumentation().waitForIdleSync();
        }
    }

    private <T> T main(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> result.set(action.get()));
        return result.get();
    }

    private void waitFor(String message, Supplier<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.get()) return;
            Thread.sleep(50);
        }
        fail(message);
    }

    private List<Api.Series> verify(String category, String board) throws Exception {
        List<Api.Series> rows = api.rank(category, board, 12);
        assertEquals("跨页榜单不足12项", 12, rows.size());
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            Api.Series row = rows.get(index);
            assertTrue(row.seriesId.matches("[0-9]+"));
            assertFalse(row.title.isEmpty());
            assertEquals(index + 1, row.rank);
            assertTrue("榜单包含重复系列", ids.add(row.seriesId));
        }
        return rows;
    }
}
