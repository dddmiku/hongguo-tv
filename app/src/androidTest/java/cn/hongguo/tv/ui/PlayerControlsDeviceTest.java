package cn.hongguo.tv.ui;

import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Exercises real remote-key handling, timers and view animation with a silent offline decoder. */
public class PlayerControlsDeviceTest extends InstrumentationTestCase {
    private static final String SOURCE = "9933333333333333333";
    private static final String EPISODES = "[{\"vid\":\"controls-one\",\"num\":1},"
            + "{\"vid\":\"controls-two\",\"num\":2},{\"vid\":\"controls-three\",\"num\":3},"
            + "{\"vid\":\"controls-four\",\"num\":4},{\"vid\":\"controls-five\",\"num\":5}]";
    private SeasonPlayerFixtureActivity player;
    private SilentPlayer decoder;
    private long hideDelay;

    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue("控制栏测试只允许独立 QA 包",
                getInstrumentation().getTargetContext().getPackageName().endsWith(".qa"));
        cn.hongguo.tv.account.Account.clear(getInstrumentation().getTargetContext());
        Intent intent = new Intent(getInstrumentation().getTargetContext(), SeasonPlayerFixtureActivity.class)
                .putExtra(PlayerActivity.EXTRA_SERIES_ID, SOURCE)
                .putExtra(PlayerActivity.EXTRA_TITLE, "离线遥控器控制栏验收")
                .putExtra(PlayerActivity.EXTRA_COVER, "")
                .putExtra(PlayerActivity.EXTRA_EPISODES, EPISODES)
                .putExtra(PlayerActivity.EXTRA_START_INDEX, 0)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        player = (SeasonPlayerFixtureActivity) getInstrumentation().startActivitySync(intent);
        long until = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < until
                && !main(() -> ((TextView) field("status")).getText().toString().contains("受控离线"))) {
            Thread.sleep(20);
        }
        assertTrue("测试播放器没有进入受控离线路径",
                main(() -> ((TextView) field("status")).getText().toString().contains("受控离线")));
        main(() -> {
            decoder = new SilentPlayer();
            set("player", decoder);
            set("prepared", true);
            set("surfaceReady", false);
            set("foreground", true);
            set("userPaused", false);
            set("playbackWanted", true);
            hideDelay = (Long) field("OVERLAY_HIDE_MS");
            hideOverlayImmediately();
            return null;
        });
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (player != null) main(() -> { player.finish(); return null; });
            getInstrumentation().waitForIdleSync();
        } finally { super.tearDown(); }
    }

    public void testRemotePauseKeepsControlsVisibleAfterHideDeadline() throws Exception {
        long pressedAt = press(KeyEvent.KEYCODE_DPAD_CENTER);
        assertFalse("确定键没有实际暂停播放", main(() -> decoder.isPlaying()));
        waitForVisibleControls();
        waitPastHideDeadline(pressedAt);
        assertTrue("暂停后仍被旧的自动收起任务隐藏了控制栏", main(() -> overlay().getAlpha() > .95f));
        assertFalse("等待控制栏时意外恢复了播放", main(() -> decoder.isPlaying()));
    }

    public void testRemoteResumeHidesControlsAfterHideDeadline() throws Exception {
        main(() -> {
            decoder.pause();
            set("userPaused", true);
            set("playbackWanted", false);
            return null;
        });
        long pressedAt = press(KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue("确定键没有实际恢复播放", main(() -> decoder.isPlaying()));
        waitForVisibleControls();
        waitPastHideDeadline(pressedAt);
        assertTrue("恢复播放后没有重新安排自动收起，控制栏持续遮挡画面",
                main(() -> overlay().getAlpha() < .05f));
        assertTrue("收起控制栏时意外暂停了播放", main(() -> decoder.isPlaying()));
    }

    public void testUnknownKeyDoesNotRevealControls() throws Exception {
        press(KeyEvent.KEYCODE_UNKNOWN);
        Thread.sleep(350);
        getInstrumentation().waitForIdleSync();
        assertTrue("未处理的按键也唤起了播放器控制栏", main(() -> overlay().getAlpha() < .05f));
        assertTrue("未处理的按键改变了播放状态", main(() -> decoder.isPlaying()));
    }

    public void testHeldSelectDoesNotTogglePlaybackAgain() {
        press(KeyEvent.KEYCODE_DPAD_CENTER);
        assertFalse(main(() -> decoder.isPlaying()));
        assertTrue(dispatch(KeyEvent.KEYCODE_DPAD_CENTER, 1));
        assertTrue(dispatch(KeyEvent.KEYCODE_DPAD_CENTER, 2));
        assertFalse("长按确定键再次切换了播放状态", main(() -> decoder.isPlaying()));
        assertTrue(dispatch(KeyEvent.KEYCODE_BUTTON_A, 0));
        assertTrue("手柄选择键没有恢复播放", main(() -> decoder.isPlaying()));
    }

    public void testHeldDirectionContinuesSeeking() {
        assertTrue(dispatch(KeyEvent.KEYCODE_DPAD_RIGHT, 0));
        assertTrue(dispatch(KeyEvent.KEYCODE_DPAD_RIGHT, 1));
        assertEquals(2, (int) main(() -> decoder.seeks));
    }

    private boolean dispatch(int keyCode, int repeat) {
        return main(() -> {
            long now = SystemClock.uptimeMillis();
            return player.onKeyDown(keyCode, new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, repeat));
        });
    }

    private long press(int keyCode) {
        return main(() -> {
            long now = SystemClock.uptimeMillis();
            player.onKeyDown(keyCode, new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            return now;
        });
    }

    private void waitForVisibleControls() throws Exception {
        long until = SystemClock.uptimeMillis() + 1000;
        while (SystemClock.uptimeMillis() < until && !main(() -> overlay().getAlpha() > .95f)) {
            Thread.sleep(20);
        }
        assertTrue("操作后没有显示控制栏", main(() -> overlay().getAlpha() > .95f));
    }

    private void waitPastHideDeadline(long pressedAt) throws Exception {
        // Include the real 250ms hide animation and a small main-thread scheduling margin.
        long remaining = pressedAt + hideDelay + 750 - SystemClock.uptimeMillis();
        if (remaining > 0) Thread.sleep(remaining);
        getInstrumentation().waitForIdleSync();
    }

    private View overlay() { return (View) field("overlay"); }

    private void hideOverlayImmediately() {
        ((Handler) field("ui")).removeCallbacks((Runnable) field("hideOverlay"));
        overlay().animate().cancel();
        overlay().setAlpha(0f);
    }

    private <T> T main(Supplier<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> value.set(action.get()));
        return value.get();
    }

    private Object field(String name) {
        try {
            Field field = PlayerActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(player);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void set(String name, Object value) {
        try {
            Field field = PlayerActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(player, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    /** No source is attached and start() never invokes native playback, so this fixture is silent. */
    private static final class SilentPlayer extends MediaPlayer {
        private boolean playing = true;
        private int seeks;
        @Override public boolean isPlaying() { return playing; }
        @Override public int getCurrentPosition() { return 4321; }
        @Override public int getDuration() { return 60000; }
        @Override public void pause() { playing = false; }
        @Override public void start() { playing = true; }
        @Override public void seekTo(int position) { seeks++; }
    }
}
