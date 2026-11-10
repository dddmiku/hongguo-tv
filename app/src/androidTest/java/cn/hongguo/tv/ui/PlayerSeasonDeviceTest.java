package cn.hongguo.tv.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.media.MediaPlayer;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import cn.hongguo.tv.api.Api;
import cn.hongguo.tv.cache.HistoryStore;
import cn.hongguo.tv.cache.SeasonRepository;
import cn.hongguo.tv.cache.WatchRecord;
import cn.hongguo.tv.media.SeasonPlaybackCoordinator;
import cn.hongguo.tv.media.PreparationCancellation;

/** Offline integration QA: the real player transition, menu, progress and lifetime guards. */
public class PlayerSeasonDeviceTest extends InstrumentationTestCase {
    private static final String SOURCE = "9911111111111111111", TARGET = "9922222222222222222";
    private static final String EPISODES = "[{\"vid\":\"fixture-one\",\"num\":1},{\"vid\":\"fixture-two\",\"num\":2},"
            + "{\"vid\":\"fixture-three\",\"num\":3},{\"vid\":\"fixture-four\",\"num\":4},{\"vid\":\"fixture-five\",\"num\":5}]";
    private SeasonPlayerFixtureActivity player;

    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue(getInstrumentation().getTargetContext().getPackageName().endsWith(".qa"));
        cn.hongguo.tv.account.Account.clear(getInstrumentation().getTargetContext());
        Intent intent = new Intent(getInstrumentation().getTargetContext(), SeasonPlayerFixtureActivity.class)
                .putExtra(PlayerActivity.EXTRA_SERIES_ID, SOURCE).putExtra(PlayerActivity.EXTRA_TITLE, "离线第一季")
                .putExtra(PlayerActivity.EXTRA_COVER, "fixture-cover-one")
                .putExtra(PlayerActivity.EXTRA_EPISODES, EPISODES).putExtra(PlayerActivity.EXTRA_START_INDEX, 0)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        player = (SeasonPlayerFixtureActivity) getInstrumentation().startActivitySync(intent);
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until && !main(() -> status().contains("受控离线"))) Thread.sleep(20);
        assertTrue(main(() -> status().contains("受控离线")));
        main(() -> {
            FakePlayer decoder = new FakePlayer();
            set("player", decoder); set("prepared", true); set("surfaceReady", false);
            coordinator().armCompletion();
            return null;
        });
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (player != null) main(() -> { player.finish(); return null; });
            getInstrumentation().waitForIdleSync();
        } finally { super.tearDown(); }
    }

    private <T> T main(Supplier<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> value.set(action.get()));
        return value.get();
    }
    private Object field(String name) {
        try { Field f = PlayerActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(player); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void set(String name, Object value) {
        try { Field f = PlayerActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(player, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private Object invoke(String name, Class<?>[] types, Object... values) {
        try { Method m = PlayerActivity.class.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(player, values); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private String status() { return ((TextView) field("status")).getText().toString(); }
    private SeasonPlaybackCoordinator coordinator() { return (SeasonPlaybackCoordinator) field("seasonPlayback"); }
    private SeasonRepository.Season season(String id, int number, String title, String cover) {
        try {
            Api.Series series = new Api.Series(); series.seriesId = id; series.title = title;
            series.cover = cover; series.episodeCount = 5;
            Constructor<SeasonRepository.Season> c = SeasonRepository.Season.class
                    .getDeclaredConstructor(int.class, Api.Series.class, int.class);
            c.setAccessible(true); return c.newInstance(number, series, 1);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private SeasonRepository.Overview overview() {
        try {
            SeasonRepository.Season current = season(SOURCE, 1, "离线第一季", "fixture-cover-one");
            SeasonRepository.Season next = season(TARGET, 2, "离线第二季", "fixture-cover-two");
            Constructor<SeasonRepository.Overview> c = SeasonRepository.Overview.class.getDeclaredConstructor(
                    String.class, String.class, String.class, List.class, SeasonRepository.Season.class,
                    SeasonRepository.Season.class, boolean.class);
            c.setAccessible(true); return c.newInstance(SOURCE, "found", "official", Arrays.asList(current, next), current, next, false);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private Object target() {
        try {
            Class<?> type = Class.forName("cn.hongguo.tv.ui.PlayerActivity$SeasonTarget");
            Constructor<?> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
            Object value = constructor.newInstance();
            File file = new File(player.getCacheDir(), "fixture-ready.mp4");
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[]{1,2,3,4}); }
            put(value,"season",season(TARGET,2,"离线第二季","fixture-cover-two"));
            put(value,"episodes",invoke("readEpisodes",new Class<?>[]{String.class},EPISODES));
            put(value,"file",file); put(value,"definition","720p"); put(value,"cap",720); put(value,"firstIndex",0);
            return value;
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private static void put(Object object, String name, Object value) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object,value);
    }
    private void apply(Object target, SeasonPlaybackCoordinator.Ticket ticket) {
        invoke("switchToSeason",new Class<?>[]{target.getClass(),SeasonPlaybackCoordinator.Ticket.class},target,ticket);
    }

    public void testSameActivitySwitchPreservesSettingsAndSavesOldSeason() {
        main(() -> {
            set("capIndex",1); set("speedIndex",2); set("aspectMode",1); set("autoplay",false);
            invoke("showEpisodePicker",new Class<?>[0]);
            Object ready = target(); apply(ready,coordinator().ticket());
            assertEquals(TARGET,field("seriesId")); assertEquals("离线第二季",field("seriesTitle"));
            assertEquals("fixture-cover-two",field("seriesCover")); assertEquals(0,field("index"));
            assertEquals(1,field("capIndex")); assertEquals(2,field("speedIndex"));
            assertEquals(1,field("aspectMode")); assertEquals(false,field("autoplay"));
            assertNotNull(field("pendingFile")); assertNull(field("player"));
            assertNull("切季后仍保留旧季选集窗口",field("episodePicker"));
            WatchRecord saved = HistoryStore.get(player).find((String)field("playbackOwner"),SOURCE);
            assertNotNull(saved); assertEquals(SOURCE,saved.seriesId); assertEquals(4321L,saved.position);
            assertEquals("离线第一季",saved.title); assertEquals("fixture-cover-one",saved.cover);
            assertEquals(1,saved.episode); assertFalse(player.isFinishing());
            return null;
        });
    }

    public void testPreparedNextStartsOnlyAfterEndAndWhenAutoplayEnabled() {
        main(() -> {
            set("capIndex",1); set("seasonOverview",overview()); set("nextTarget",target());
            set("index",4); set("autoplay",false); coordinator().completed();
            invoke("continueAtSeasonEnd",new Class<?>[0]); assertEquals(SOURCE,field("seriesId"));
            set("autoplay",true); invoke("continueAtSeasonEnd",new Class<?>[0]);
            assertEquals(TARGET,field("seriesId")); assertEquals(0,field("index"));
            return null;
        });
    }

    public void testPauseSeekAndHiddenDropLateReadyTarget() {
        main(() -> {
            Object ready = target(); SeasonPlaybackCoordinator.Ticket ticket = coordinator().ticket();
            coordinator().completed(); invoke("togglePause",new Class<?>[0]); apply(ready,ticket);
            assertEquals(SOURCE,field("seriesId")); assertFalse(coordinator().ended());
            ticket = coordinator().ticket(); coordinator().completed();
            invoke("seekBy",new Class<?>[]{int.class},1000); apply(ready,ticket);
            assertEquals(SOURCE,field("seriesId")); assertFalse(coordinator().ended());
            ticket = coordinator().ticket(); set("foreground",false); apply(ready,ticket);
            assertEquals(SOURCE,field("seriesId")); set("foreground",true);
            return null;
        });
    }

    public void testBothOverlayAndRemoteMenuHaveAllSeasonsAndCurrentHighlight() {
        main(() -> {
            assertTrue(contains(player.getWindow().getDecorView(),"全部季"));
            assertTrue(contains(player.getWindow().getDecorView(),"选集"));
            invoke("showPlaybackMenu",new Class<?>[0]);
            AlertDialog menu = (AlertDialog) field("playbackMenu");
            assertEquals("全部季",menu.getListView().getAdapter().getItem(1)); menu.dismiss();
            set("seasonOverview",overview()); invoke("displaySeasonPicker",new Class<?>[0]);
            AlertDialog picker = (AlertDialog) field("seasonPicker");
            assertEquals(0,picker.getListView().getCheckedItemPosition());
            assertTrue(picker.getListView().getAdapter().getItem(0).toString().contains("正在播放"));
            assertTrue(picker.getListView().getAdapter().getItem(1).toString().contains("5 集"));
            picker.dismiss(); return null;
        });
    }

    public void testSelectingSeasonCancelsUnpreparedEpisodeAndDropsItsLateStart() throws Exception {
        Object lock = main(() -> field("preparationLock"));
        int previousRequest = main(() -> (Integer)field("loadGeneration"));
        PreparationCancellation cancellation = new PreparationCancellation();
        CountDownLatch entered = new CountDownLatch(1), disconnected = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1), nextLoadEntered = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger obsoleteStarts = new AtomicInteger();
        HttpURLConnection connection = new HttpURLConnection(new URL("http://localhost/qa-read")) {
            @Override public void disconnect() { disconnected.countDown(); }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() { }
        };
        main(() -> { set("prepared",false); set("episodePreparation",cancellation); return null; });
        Thread oldLoad = new Thread(() -> {
            try {
                synchronized (lock) {
                    cancellation.bindWorker(); cancellation.bindConnection(connection); entered.countDown();
                    while (disconnected.getCount() != 0) try { disconnected.await(); }
                    catch (InterruptedException ignored) { }
                    cancellation.check();
                    failure.set(new AssertionError("旧下载取消后仍继续准备"));
                }
            } catch (InterruptedIOException expected) { }
            catch (Throwable error) { failure.set(error); }
            finally {
                cancellation.finished();
                invoke("postLoad",new Class<?>[]{int.class,Runnable.class},previousRequest,(Runnable)() -> {
                    obsoleteStarts.incrementAndGet();
                    invoke("startPlayback",new Class<?>[]{File.class,int.class},
                            new File(player.getCacheDir(),"fixture-obsolete.mp4"),1);
                });
                finished.countDown();
            }
        },"qa-obsolete-episode");
        Thread nextLoad = new Thread(() -> { synchronized (lock) { nextLoadEntered.countDown(); } },"qa-priority-load");
        oldLoad.start();
        try {
            assertTrue("受控旧下载没有进入准备锁",entered.await(3,TimeUnit.SECONDS));
            nextLoad.start(); assertEquals(1,nextLoadEntered.getCount());
            main(() -> {
                set("capIndex",1); set("nextTarget",target());
                invoke("selectSeason",new Class<?>[]{SeasonRepository.Season.class},
                        season(TARGET,2,"离线第二季","fixture-cover-two"));
                assertEquals(TARGET,field("seriesId")); assertTrue(cancellation.cancelled());
                assertTrue((Integer)field("loadGeneration") > previousRequest);
                return null;
            });
            assertTrue("取消没有关闭旧下载",disconnected.await(3,TimeUnit.SECONDS));
            assertTrue("新播放仍被旧下载占锁",nextLoadEntered.await(3,TimeUnit.SECONDS));
            assertTrue(finished.await(3,TimeUnit.SECONDS));
            getInstrumentation().waitForIdleSync();
            assertNull(failure.get()); assertEquals("旧集晚回调仍起播",0,obsoleteStarts.get());
            main(() -> {
                assertEquals(TARGET,field("seriesId")); assertNull(field("player"));
                assertEquals(new File(player.getCacheDir(),"fixture-ready.mp4"),field("pendingFile"));
                return null;
            });
        } finally {
            cancellation.cancel(); disconnected.countDown();
            oldLoad.join(3000); nextLoad.join(3000);
        }
    }

    private boolean contains(View view, String text) {
        if (view instanceof TextView && ((TextView)view).getText().toString().equals(text)) return true;
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++)
            if (contains(((ViewGroup)view).getChildAt(i),text)) return true;
        return false;
    }
    private static final class FakePlayer extends MediaPlayer {
        boolean playing = true;
        @Override public boolean isPlaying() { return playing; }
        @Override public int getCurrentPosition() { return 4321; }
        @Override public int getDuration() { return 10000; }
        @Override public void pause() { playing = false; }
        @Override public void start() { playing = true; }
        @Override public void seekTo(int position) { }
    }
}
