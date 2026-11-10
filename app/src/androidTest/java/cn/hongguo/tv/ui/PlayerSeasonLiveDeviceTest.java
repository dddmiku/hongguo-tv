package cn.hongguo.tv.ui;

import android.content.Intent;
import android.app.Activity;
import android.app.Application;
import android.app.AlertDialog;
import android.os.Bundle;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.test.InstrumentationTestCase;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import cn.hongguo.tv.account.Account;
import cn.hongguo.tv.api.Api;
import cn.hongguo.tv.cache.HistoryStore;
import cn.hongguo.tv.cache.SeasonRepository;
import cn.hongguo.tv.cache.WatchRecord;
import org.json.JSONArray;

/** Real .qa SurfaceView decoding: official season 1 episode 79 ends, season 2 episode 1 starts. */
public class PlayerSeasonLiveDeviceTest extends InstrumentationTestCase {
    private static final String SOURCE = "7481108346636864536", NEXT = "7577339455728520217";
    private PlayerActivity player;

    private <T> T main(Supplier<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> value.set(action.get()));
        return value.get();
    }
    private Object field(String name) {
        try { Field field = PlayerActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(player); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void set(String name, Object value) {
        try { Field field = PlayerActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(player,value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void invoke(String name) {
        try { Method method = PlayerActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(player); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (player != null) main(() -> { player.finish(); return null; });
            getInstrumentation().waitForIdleSync();
        } finally { super.tearDown(); }
    }

    public void testRealDecoderAutomaticallyContinuesFrom79ToNextSeason1() throws Exception {
        assertTrue(getInstrumentation().getTargetContext().getPackageName().endsWith(".qa"));
        assertFalse("真实季续播验收必须使用未登录QA包，避免云端写入", Account.load(getInstrumentation().getTargetContext()).loggedIn());
        AudioManager audio = (AudioManager)getInstrumentation().getTargetContext().getSystemService(android.content.Context.AUDIO_SERVICE);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,0,0);
        Api api = new Api(getInstrumentation().getTargetContext());
        SeasonRepository.Overview relation = new SeasonRepository(api).overview(SOURCE,true);
        assertNotNull("官方下一季没有匹配",relation.next); assertEquals(NEXT,relation.next.series.seriesId);
        JSONArray episodes = Api.parseEpisodes(api.episodes(SOURCE),SOURCE);
        assertEquals(79,episodes.length()); assertEquals(79,episodes.getJSONObject(78).getInt("num"));
        Intent intent = PlayerActivity.intent(getInstrumentation().getTargetContext(),SOURCE,
                relation.current.series.title,relation.current.series.cover,episodes.toString())
                .putExtra(PlayerActivity.EXTRA_START_INDEX,78).putExtra("start_position_ms",0L)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        player = (PlayerActivity)getInstrumentation().startActivitySync(intent);
        main(() -> { set("autoplay",true); set("speedIndex",0); return null; });
        waitFor("第79集未真实解码",() -> playing(SOURCE),180000);
        waitFor("下一季首集未提前准备",() -> field("nextTarget") != null || playing(NEXT),180000);
        int from = main(() -> SOURCE.equals(field("seriesId"))
                ? ((MediaPlayer)field("player")).getCurrentPosition() : 200);
        main(() -> {
            if (SOURCE.equals(field("seriesId"))) {
                MediaPlayer decoder = (MediaPlayer)field("player");
                decoder.seekTo(Math.max(0,decoder.getDuration()-1200));
            }
            return null;
        });
        waitFor("季末未自动切到下一季真实解码",() -> playing(NEXT),120000);
        main(() -> {
            assertEquals(NEXT,field("seriesId")); assertEquals(0,field("index"));
            assertEquals(true,field("autoplay")); assertEquals(0,field("speedIndex"));
            assertNotNull(field("surface")); assertFalse(player.isFinishing());
            WatchRecord old = HistoryStore.get(getInstrumentation().getTargetContext()).find("local",SOURCE);
            assertNotNull(old); assertEquals(79,old.episode); assertTrue(old.position >= from);
            return null;
        });
        WatchRecord oldSeason = HistoryStore.get(getInstrumentation().getTargetContext()).find("local",SOURCE);
        long oldPosition = oldSeason.position;
        recreatePlayer();
        waitFor("播放器重建后没有恢复第二季真实解码",() -> playing(NEXT),90000);
        main(() -> {
            assertEquals(NEXT,field("seriesId")); assertEquals(0,field("index"));
            assertEquals(NEXT,player.getIntent().getStringExtra(PlayerActivity.EXTRA_SERIES_ID));
            assertEquals(relation.next.series.title,field("seriesTitle"));
            assertEquals(relation.next.series.cover,field("seriesCover"));
            assertEquals(true,field("autoplay")); assertEquals(0,field("speedIndex"));
            WatchRecord source = HistoryStore.get(getInstrumentation().getTargetContext()).find("local",SOURCE);
            WatchRecord next = HistoryStore.get(getInstrumentation().getTargetContext()).find("local",NEXT);
            assertNotNull(source); assertNotNull(next); assertEquals(79,source.episode);
            assertEquals(oldPosition,source.position); assertEquals(1,next.episode);
            assertTrue(next.position > 0);
            invoke("showPlaybackMenu");
            AlertDialog menu = (AlertDialog)field("playbackMenu");
            assertEquals("全部季",menu.getListView().getAdapter().getItem(1));
            menu.dismiss(); invoke("showSeasonPicker");
            return null;
        });
        waitFor("第二季全部季菜单没有加载",() -> field("seasonPicker") != null
                && ((AlertDialog)field("seasonPicker")).isShowing(),60000);
        SeasonScreenshots.capture(getInstrumentation(),"seasons-player");
    }

    private void recreatePlayer() throws Exception {
        PlayerActivity old = player;
        AtomicReference<PlayerActivity> replacement = new AtomicReference<>();
        CountDownLatch resumed = new CountDownLatch(1);
        Application application = (Application)getInstrumentation().getTargetContext().getApplicationContext();
        Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                if (activity instanceof PlayerActivity && activity != old) replacement.set((PlayerActivity)activity);
            }
            @Override public void onActivityResumed(Activity activity) {
                if (activity == replacement.get()) resumed.countDown();
            }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        };
        main(() -> { application.registerActivityLifecycleCallbacks(callbacks); old.recreate(); return null; });
        try {
            assertTrue("重建没有产生新播放器Activity",resumed.await(15,TimeUnit.SECONDS));
            player = replacement.get(); assertNotNull(player);
        } finally { main(() -> { application.unregisterActivityLifecycleCallbacks(callbacks); return null; }); }
    }

    private boolean playing(String series) {
        try {
            MediaPlayer decoder = (MediaPlayer)field("player");
            return series.equals(field("seriesId")) && (Boolean)field("prepared") && decoder != null
                    && decoder.isPlaying() && decoder.getVideoWidth()>0 && decoder.getCurrentPosition()>200;
        } catch (IllegalStateException error) { return false; }
    }
    private void waitFor(String message, Supplier<Boolean> condition, long timeout) throws Exception {
        long deadline = System.currentTimeMillis()+timeout;
        while(System.currentTimeMillis()<deadline) {
            if(main(condition)) return;
            Thread.sleep(100);
        }
        fail(message);
    }
}
