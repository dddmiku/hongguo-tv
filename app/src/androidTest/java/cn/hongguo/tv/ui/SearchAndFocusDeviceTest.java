package cn.hongguo.tv.ui;

import android.app.Activity;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import cn.hongguo.tv.account.Account;
import cn.hongguo.tv.api.Api;
import cn.hongguo.tv.cache.LibraryRepository;

/** Exercises the original search worker and home render/focus restoration in the .qa app. */
public class SearchAndFocusDeviceTest extends InstrumentationTestCase {
    private Activity activity;
    private SearchFixtureActivity search;

    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue(getInstrumentation().getTargetContext().getPackageName().endsWith(".qa"));
        assertFalse("请使用未登录的独立 QA 包", Account.load(getInstrumentation().getTargetContext()).loggedIn());
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (search != null) {
                search.releaseSlow.countDown();
                Thread worker = search.slowThread.get();
                if (worker != null) worker.join(3000);
            }
            if (activity != null) {
                main(() -> { if (!activity.isDestroyed()) activity.finish(); return null; });
                waitFor("上一项测试的 Activity 没有销毁", activity::isDestroyed);
            }
            getInstrumentation().waitForIdleSync();
        } finally { super.tearDown(); }
    }

    private <T> T main(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> result.set(action.get()));
        return result.get();
    }

    private Activity open(Class<? extends Activity> type) {
        Intent intent = new Intent(getInstrumentation().getTargetContext(), type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = getInstrumentation().startActivitySync(intent);
        getInstrumentation().waitForIdleSync();
        assertFalse("测试返回了正在结束或已销毁的 Activity",
                main(() -> activity.isFinishing() || activity.isDestroyed()));
        return activity;
    }

    private Object field(Class<?> owner, Object target, String name) {
        try { Field value = owner.getDeclaredField(name); value.setAccessible(true); return value.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void set(Class<?> owner, Object target, String name, Object value) {
        try { Field property = owner.getDeclaredField(name); property.setAccessible(true); property.set(target, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void invoke(Class<?> owner, Object target, String name, Class<?>[] types, Object... args) {
        try { Method method = owner.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(target, args); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void submit(String query) {
        boolean submitted = main(() -> {
            if (search.isFinishing() || search.isDestroyed()) return false;
            input().setText(query);
            return go().performClick();
        });
        assertTrue("提交查询前夹具 Activity 已结束", submitted);
    }

    private void beginSlowSearch(boolean success) throws Exception {
        search = (SearchFixtureActivity) open(SearchFixtureActivity.class);
        search.slowSuccess = success;
        submit(SearchFixtureActivity.SLOW_QUERY);
        assertTrue("搜索线程没有进入受控延迟", search.slowStarted.await(3, TimeUnit.SECONDS));
    }

    private void releaseSlowSearch() throws Exception {
        search.releaseSlow.countDown();
        Thread worker = search.slowThread.get();
        worker.join(3000);
        assertFalse("旧搜索线程没有结束", worker.isAlive());
        getInstrumentation().waitForIdleSync();
    }

    private String status() {
        return main(() -> ((TextView) field(SearchActivity.class, search, "status")).getText().toString());
    }

    private LinearLayout results() { return (LinearLayout) field(SearchActivity.class, search, "results"); }
    private EditText input() { return (EditText) field(SearchActivity.class, search, "input"); }
    private View go() { return (View) field(SearchActivity.class, search, "go"); }

    public void testSearchFailureClearsPreviousResultsAndRetriesSameQuery() throws Exception {
        search = (SearchFixtureActivity) open(SearchFixtureActivity.class);
        submit(SearchFixtureActivity.FAST_QUERY);
        waitFor("结果没有聚焦到首行", () -> results().getChildCount() == 1 && results().getChildAt(0).hasFocus());
        submit(SearchFixtureActivity.RETRY_QUERY);
        waitFor("失败未清理旧作品并聚焦重试", () -> results().getChildCount() == 1
                && results().getChildAt(0) instanceof TextView && results().getChildAt(0).hasFocus()
                && "重试搜索".contentEquals(((TextView) results().getChildAt(0)).getText()));
        assertTrue(main(() -> results().getChildAt(0).performClick()));
        waitFor("原词重试没有返回作品", () -> results().getChildCount() == 1
                && SearchFixtureActivity.RESULT_TITLE.contentEquals(results().getChildAt(0).getContentDescription()));
        assertEquals(2, search.retryRequests.get());
    }

    public void testEmptySearchCanReturnToSelectQuery() throws Exception {
        search = (SearchFixtureActivity) open(SearchFixtureActivity.class);
        submit(SearchFixtureActivity.EMPTY_QUERY);
        waitFor("没有结果时未聚焦改词", () -> results().getChildCount() == 1 && results().getChildAt(0).hasFocus());
        assertTrue(main(() -> results().getChildAt(0).performClick()));
        assertTrue(main(() -> input().hasFocus() && input().getSelectionStart() == 0
                && input().getSelectionEnd() == input().length()));
    }

    public void testDelayedResultsRespectUserFocus() throws Exception {
        beginSlowSearch(true);
        assertTrue(main(() -> input().requestFocus()));
        releaseSlowSearch();
        assertEquals(1, (int) main(() -> results().getChildCount()));
        assertSame(main(this::input), main(search::getCurrentFocus));
    }

    private void waitFor(String message, Supplier<Boolean> predicate) throws Exception {
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until) {
            if (main(predicate)) return;
            Thread.sleep(20);
        }
        fail(message);
    }

    public void testLateBusinessErrorCannotReplaceNewSearchSuccess() throws Exception {
        beginSlowSearch(false);
        submit(SearchFixtureActivity.FAST_QUERY);
        waitFor("新查询没有完成", () -> results().getChildCount() == 1
                && "找到 1 部".contentEquals(((TextView) field(SearchActivity.class, search, "status")).getText()));
        String before = status();
        releaseSlowSearch();
        assertEquals("旧查询业务错误覆盖了新查询成功状态", before, status());
        assertEquals(1, (int) main(() -> results().getChildCount()));
        assertEquals(SearchFixtureActivity.RESULT_TITLE, main(() -> results().getChildAt(0).getContentDescription().toString()));
    }

    public void testFinishingSearchIgnoresLateBusinessError() throws Exception {
        finishBeforeSearchCompletes(false);
    }

    public void testFinishingSearchIgnoresLateSuccess() throws Exception {
        finishBeforeSearchCompletes(true);
    }

    private void finishBeforeSearchCompletes(boolean success) throws Exception {
        beginSlowSearch(success);
        String before = status();
        AtomicInteger publishedWhileFinishing = new AtomicInteger();
        // Keep the UI thread inside finish/onDestroy while the real worker queues its reply.
        boolean[] lifecycle = main(() -> {
            search.finish();
            boolean[] state = {search.isFinishing(), search.isDestroyed()};
            int generation = (Integer) field(SearchActivity.class, search, "generation");
            invoke(SearchActivity.class, search, "publishSearch", new Class<?>[]{int.class, Runnable.class},
                    generation, (Runnable) publishedWhileFinishing::incrementAndGet);
            search.releaseSlow.countDown();
            try { search.slowThread.get().join(2500); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            return state;
        });
        assertTrue("没有进入 finishing 窗口", lifecycle[0]);
        assertFalse("夹具未能锁定 finish 与 onDestroy 之间的窗口", lifecycle[1]);
        assertEquals("Activity 尚未销毁但已结束时仍提交搜索回调", 0, publishedWhileFinishing.get());
        releaseSlowSearch();
        assertTrue(search.isFinishing());
        assertEquals("退出后的搜索结果仍修改状态", before, status());
        assertEquals("退出后的搜索仍重建结果列表", 0, (int) main(() -> results().getChildCount()));
    }

    @SuppressWarnings("unchecked")
    public void testRefreshKeepsFocusInOriginalSectionForDuplicateSeries() throws Exception {
        MainActivity home = (MainActivity) open(MainActivity.class);
        main(() -> {
            set(MainActivity.class, home, "generation", ((Integer) field(MainActivity.class, home, "generation")) + 1);
            set(MainActivity.class, home, "page", 0);
            set(MainActivity.class, home, "initiallyFocused", true);
            set(MainActivity.class, home, "userNavigated", true);
            LibraryRepository library = (LibraryRepository) field(MainActivity.class, home, "library");
            library.unobserve((Runnable) field(MainActivity.class, home, "libraryChanged"));
            Api.Series same = new Api.Series();
            same.seriesId = "9913377000000000002"; same.title = "两行重复作品"; same.cover = ""; same.episodeCount = 12;
            Map<String, List<Api.Series>> sections = (Map<String, List<Api.Series>>) field(MainActivity.class, home, "sections");
            sections.clear();
            sections.put("hot_score", new ArrayList<>(Collections.singletonList(same)));
            sections.put("online_time", new ArrayList<>(Collections.singletonList(same)));
            invoke(MainActivity.class, home, "render", new Class<?>[]{});
            return null;
        });
        getInstrumentation().waitForIdleSync();
        List<View> before = main(() -> matchingCards(home.getWindow().getDecorView()));
        assertEquals("fixture 应在两个分区显示同一作品", 2, before.size());
        assertTrue(main(() -> before.get(1).requestFocus()));
        getInstrumentation().waitForIdleSync();
        assertSame(before.get(1), main(home::getCurrentFocus));
        main(() -> { invoke(MainActivity.class, home, "render", new Class<?>[]{}); return null; });
        getInstrumentation().waitForIdleSync();
        List<View> after = main(() -> matchingCards(home.getWindow().getDecorView()));
        assertEquals(2, after.size());
        assertSame("新鲜上架分区的焦点跳到了热播分区", after.get(1), main(home::getCurrentFocus));
    }

    private List<View> matchingCards(View view) {
        List<View> out = new ArrayList<>();
        collectCards(view, out);
        return out;
    }

    private void collectCards(View view, List<View> out) {
        if (view.getTag() instanceof String && ((String) view.getTag()).startsWith("series:")
                && view.getContentDescription() != null && view.getContentDescription().toString().startsWith("两行重复作品")) out.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectCards(group.getChildAt(i), out);
        }
    }
}
