package cn.hongguo.tv.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import cn.hongguo.tv.account.Account;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Tests the real rankings worker and focus graph with controlled responses. */
public class RankingsDeviceTest extends InstrumentationTestCase {
    private Activity activity;
    private RankingsFixtureActivity rankings;

    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue(getInstrumentation().getTargetContext().getPackageName().endsWith(".qa"));
        assertFalse(Account.load(getInstrumentation().getTargetContext()).loggedIn());
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (rankings != null) rankings.releaseSlow.countDown();
            if (activity != null) {
                main(() -> { if (!activity.isDestroyed()) activity.finish(); return null; });
                waitFor("榜单页面没有销毁", activity::isDestroyed);
            }
            getInstrumentation().waitForIdleSync();
        } finally { super.tearDown(); }
    }

    private void open() {
        rankings = (RankingsFixtureActivity) getInstrumentation().startActivitySync(
                new Intent(getInstrumentation().getTargetContext(), RankingsFixtureActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        activity = rankings;
        waitFor("默认系列剧推荐榜未显示", () -> title().contains("series/recommend"));
    }

    public void testSeriesBoardsAndRemoteCanReachTheRankedResult() {
        open();
        assertNotNull(view("rank-category:series"));
        assertNotNull(view("rank-board:new"));
        assertNull(view("rank-board:hot"));
        main(() -> rankings.getWindow().getDecorView().findViewWithTag("rank-category:series").requestFocus());
        getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        waitFor("系列剧分类向下未到推荐榜", () -> focusIs("rank-board:recommend"));
        getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("新季榜没有请求与显示真实选择", () -> title().contains("series/new"));
        assertTrue(rankings.requests.contains("series/new"));
        getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        waitFor("榜单向下未到第一名", () -> focusIs("rank-item:9913377000000000081"));
        getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        waitFor("第一名向上未回到已选新季榜", () -> focusIs("rank-board:new"));
        click("rank-category:comic");
        waitFor("漫剧榜未保留", () -> title().contains("comic/new"));
        assertNotNull(view("rank-board:hot"));
    }

    public void testLateBoardResultCannotReplaceNewSelection() throws Exception {
        checkLate(false);
    }

    public void testLateBoardErrorCannotReplaceNewSelection() throws Exception {
        checkLate(true);
    }

    private void checkLate(boolean fail) throws Exception {
        open();
        rankings.slowNew = true;
        rankings.failSlow = fail;
        click("rank-board:new");
        assertTrue(rankings.slowStarted.await(4, TimeUnit.SECONDS));
        assertEquals("切榜时保留了旧榜内容", 0, (int) main(() -> results().getChildCount()));
        click("rank-board:recommend");
        waitFor("新选择没有先显示", () -> title().contains("series/recommend"));
        String before = title();
        rankings.releaseSlow.countDown();
        assertTrue(rankings.slowFinished.await(4, TimeUnit.SECONDS));
        getInstrumentation().waitForIdleSync();
        assertEquals(before, title());
        assertNull(view("rank-retry"));
    }

    public void testFailureAndEmptyBoardCanBeRetried() {
        open();
        rankings.failNewOnce = true;
        click("rank-board:new");
        waitFor("失败后未提供重试", () -> view("rank-retry") != null);
        click("rank-retry");
        waitFor("重试没有重新请求原榜单", () -> title().contains("series/new"));
        assertEquals(2, rankings.newAttempts.get());
        click("rank-board:recommend");
        waitFor("未回到推荐榜", () -> title().contains("series/recommend"));
        rankings.emptyNew = true;
        click("rank-board:new");
        waitFor("空榜单未提供刷新", () -> view("rank-retry") != null);
        assertTrue(main(() -> ((TextView) field("status")).getText().toString()).contains("暂时没有内容"));
        rankings.emptyNew = false;
        click("rank-retry");
        waitFor("空榜单刷新后没有恢复内容", () -> title().contains("series/new"));
    }

    public void testHomeEntryAndBackRestoreTheRankNavigation() {
        MainActivity home = (MainActivity) getInstrumentation().startActivitySync(
                new Intent(getInstrumentation().getTargetContext(), MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        activity = home;
        waitFor("首页排行入口不存在", () -> home.hasWindowFocus()
                && home.getWindow().getDecorView().findViewWithTag("nav-rank") != null);
        Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(RankingsActivity.class.getName(), null, false);
        Activity opened = null;
        try {
            main(() -> {
                View nav = home.getWindow().getDecorView().findViewWithTag("nav-rank");
                nav.requestFocus();
                return nav.performClick();
            });
            opened = getInstrumentation().waitForMonitorWithTimeout(monitor, 10000);
            assertNotNull("排行入口没有打开榜单页", opened);
            final Activity rankPage = opened;
            waitFor("榜单页没有进入前台", rankPage::hasWindowFocus);
            getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            waitFor("返回后没有恢复排行入口焦点", () -> home.hasWindowFocus()
                    && home.getCurrentFocus() != null && "nav-rank".equals(home.getCurrentFocus().getTag()));
        } finally {
            getInstrumentation().removeMonitor(monitor);
            if (opened != null) {
                Activity target = opened;
                main(() -> { if (!target.isDestroyed()) target.finish(); return null; });
            }
        }
    }

    private LinearLayout results() { return (LinearLayout) field("results"); }
    private String title() {
        return main(() -> results().getChildCount() == 1
                && results().getChildAt(0).getContentDescription() != null
                ? results().getChildAt(0).getContentDescription().toString() : "");
    }
    private boolean focusIs(String tag) {
        return main(() -> rankings.getCurrentFocus() != null && tag.equals(rankings.getCurrentFocus().getTag()));
    }
    private View view(String tag) { return main(() -> rankings.getWindow().getDecorView().findViewWithTag(tag)); }
    private void click(String tag) {
        assertTrue("控件不可操作：" + tag, main(() -> {
            View target = rankings.getWindow().getDecorView().findViewWithTag(tag);
            if (target == null) return false;
            target.requestFocus();
            return target.performClick();
        }));
    }
    private Object field(String name) {
        try { Field field = RankingsActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(rankings); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private <T> T main(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> result.set(action.get()));
        return result.get();
    }
    private void waitFor(String message, Supplier<Boolean> condition) {
        long deadline = System.currentTimeMillis() + 12000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.get()) return;
            try { Thread.sleep(25); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        }
        fail(message);
    }
}
