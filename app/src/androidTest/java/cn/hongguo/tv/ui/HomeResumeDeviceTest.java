package cn.hongguo.tv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ScrollView;
import cn.hongguo.tv.account.Account;
import cn.hongguo.tv.cache.HistoryStore;
import cn.hongguo.tv.cache.WatchRecord;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Exercises home actions with a deterministic episode list and captures the actual launch intent. */
public class HomeResumeDeviceTest extends InstrumentationTestCase {
    private static final String SERIES = "9913377000000000047";
    private static final long POSITION = 12561L;
    private MainActivity home;
    private Context context;
    private HistoryStore history;
    private Instrumentation.ActivityMonitor monitor;
    private final AtomicReference<Intent> playback = new AtomicReference<>();
    private final CountDownLatch loading = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger attempts = new AtomicInteger();

    @Override protected void setUp() throws Exception {
        super.setUp();
        context = getInstrumentation().getTargetContext();
        assertTrue(context.getPackageName().endsWith(".qa"));
        assertFalse(Account.load(context).loggedIn());
        history = HistoryStore.get(context);
        WatchRecord record = new WatchRecord();
        record.seriesId = SERIES;
        record.title = "首页续看验收";
        record.episode = 7;
        record.position = POSITION;
        record.duration = 30000;
        record.watchedAt = System.currentTimeMillis();
        history.merge("local", record, true);

        home = (MainActivity) getInstrumentation().startActivitySync(new Intent(Intent.ACTION_MAIN)
                .setClass(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitFor("首页没有进入前台", () -> home.hasWindowFocus() && recent() != null);
        monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (intent.getComponent() != null && PlayerActivity.class.getName().equals(intent.getComponent().getClassName())) {
                    playback.set(new Intent(intent));
                    return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
                }
                return null;
            }
        };
        getInstrumentation().addMonitor(monitor);
    }

    @Override protected void tearDown() throws Exception {
        try {
            release.countDown();
            if (home != null) {
                main(() -> { if (!home.isDestroyed()) home.finish(); return null; });
                waitFor("首页没有销毁", home::isDestroyed);
            }
            if (monitor != null) getInstrumentation().removeMonitor(monitor);
            if (history != null) history.remove("local", SERIES);
            getInstrumentation().waitForIdleSync();
        } finally { super.tearDown(); }
    }

    public void testOnePressOpensSavedEpisodeAtExactPosition() throws Exception {
        installFixture(false, false, true);
        pressRecent();
        assertSavedPlayback();
        assertEquals(1, attempts.get());
    }

    public void testEpisodeServiceErrorCanBeRetried() throws Exception {
        installFixture(true, false, true);
        pressRecent();
        waitFor("失败后没有提供重试", () -> dialog() != null
                && dialog().getButton(AlertDialog.BUTTON_POSITIVE).getVisibility() == View.VISIBLE);
        assertNull(playback.get());
        main(() -> dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        assertSavedPlayback();
        assertEquals(2, attempts.get());
    }

    public void testBackCancelsLatePlaybackAndRootBackLeavesHome() throws Exception {
        installFixture(false, true, true);
        pressRecent();
        assertTrue("没有等待取集", loading.await(4, TimeUnit.SECONDS));
        // The worker can enter immediately, before WindowManager focuses the new dialog.
        waitFor("等待弹窗没有收到窗口焦点", () -> dialog() != null && dialog().getWindow() != null
                && dialog().getWindow().getDecorView().hasWindowFocus());
        getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitFor("Back 没有关闭等待弹窗", () -> dialog() == null && home.hasWindowFocus());
        release.countDown();
        ((ExecutorService) field(home, "resumeLoader")).submit(() -> {}).get(5, TimeUnit.SECONDS);
        getInstrumentation().waitForIdleSync();
        assertNull("取消后旧回复仍启动了播放器", playback.get());

        main(() -> {
            View nav = home.getWindow().getDecorView().findViewWithTag("nav-home");
            nav.requestFocus();
            ((ScrollView) field(home, "scroll")).scrollTo(0, 0);
            return null;
        });
        getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitFor("首页返回没有交给系统", () -> !((Boolean) field(home, "resumed")));
    }

    public void testUnavailableEpisodeOpensSelectionWithoutStartingPlayback() throws Exception {
        installFixture(false, false, false);
        pressRecent();
        String notice = "上次观看的集数暂不可播放，请重新选集。";
        waitFor("未进入已加载的选集界面", () -> dialog() == null && !home.hasWindowFocus());
        // Accessibility follows the top dialog window, which is separate from the home decor.
        long until = System.currentTimeMillis() + 5000L;
        boolean found = false;
        while (System.currentTimeMillis() < until && !found) {
            android.view.accessibility.AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                found = !root.findAccessibilityNodeInfosByText(notice).isEmpty();
                root.recycle();
            }
            if (!found) Thread.sleep(25);
        }
        assertTrue("未提示原集不可播放", found);
        assertNull("不可播放时启动了别的集数", playback.get());
        assertEquals("退到详情后应复用已加载的集数", 1, attempts.get());
        getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitFor("详情返回后首页未恢复焦点", home::hasWindowFocus);
    }

    private void assertSavedPlayback() throws Exception {
        waitFor("一次按确定没有启动播放", () -> playback.get() != null);
        Intent intent = playback.get();
        assertEquals(SERIES, intent.getStringExtra(PlayerActivity.EXTRA_SERIES_ID));
        assertEquals(POSITION, intent.getLongExtra("start_position_ms", -1));
        int index = intent.getIntExtra(PlayerActivity.EXTRA_START_INDEX, -1);
        assertEquals(1, index);
        assertEquals(7, new JSONArray(intent.getStringExtra(PlayerActivity.EXTRA_EPISODES))
                .getJSONObject(index).getInt("num"));
    }

    private void installFixture(boolean failFirst, boolean wait, boolean withSavedEpisode) {
        SeriesEpisodes.Source source = seriesId -> {
                if (!SERIES.equals(seriesId)) throw new IOException("意外的测试剧集");
                int attempt = attempts.incrementAndGet();
                if (wait) {
                    loading.countDown();
                    long until = System.currentTimeMillis() + 5000L;
                    while (release.getCount() != 0 && System.currentTimeMillis() < until) {
                        try { release.await(100, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { /* Simulate a transport that returns after cancellation. */ }
                    }
                }
                try {
                    if (failFirst && attempt == 1) return new JSONObject().put("code", 1099);
                    JSONArray episodes = new JSONArray().put(episode(2));
                    if (withSavedEpisode) episodes.put(episode(7));
                    return new JSONObject().put("code", 0).put("data", new JSONObject().put(seriesId,
                            new JSONObject().put("video_data", new JSONObject().put("video_list", episodes))));
                } catch (JSONException error) { throw new IOException(error); }
        };
        main(() -> { reflect(home, "resumeSource", source); return null; });
    }

    private static JSONObject episode(int number) throws JSONException {
        return new JSONObject().put("vid", "fixture-vid-" + number).put("vid_index", number);
    }

    private View recent() { return home.getWindow().getDecorView().findViewWithTag("recent:" + SERIES); }

    private void pressRecent() throws Exception {
        waitFor("续看卡片无法聚焦", () -> recent() != null && recent().requestFocus());
        getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
    }

    private AlertDialog dialog() {
        Object request = field(home, "resumeRequest");
        return request == null ? null : (AlertDialog) field(request, "dialog");
    }

    private static Object field(Object object, String name) { return reflect(object, name); }

    private static Object reflect(Object object, String name, Object... value) {
        try {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            if (value.length != 0) field.set(object, value[0]);
            return field.get(object);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private <T> T main(Supplier<T> operation) {
        AtomicReference<T> result = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> result.set(operation.get()));
        return result.get();
    }

    private void waitFor(String reason, Supplier<Boolean> predicate) throws Exception {
        long until = System.currentTimeMillis() + 6500L;
        while (System.currentTimeMillis() < until) {
            if (main(predicate)) return;
            Thread.sleep(25);
        }
        fail(reason);
    }
}
