package cn.hongguo.tv.media;

import android.test.InstrumentationTestCase;
import cn.hongguo.tv.api.Api;
import org.json.JSONObject;
import org.json.JSONArray;
import android.media.MediaPlayer;
import android.media.AudioManager;
import android.content.Intent;
import cn.hongguo.tv.account.Account;
import cn.hongguo.tv.ui.PlayerActivity;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Real official model for the reported later episode; no captured URL or preview substitute. */
public final class MobilePlaybackDeviceTest extends InstrumentationTestCase {
    private PlayerActivity player;
    private <T> T main(Supplier<T> action){AtomicReference<T> value=new AtomicReference<>();getInstrumentation().runOnMainSync(()->value.set(action.get()));return value.get();}
    private Object field(String name){try{Field field=PlayerActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(player);}catch(Exception error){throw new AssertionError(error);}}
    @Override protected void tearDown()throws Exception{if(player!=null)main(()->{player.finish();return null;});getInstrumentation().waitForIdleSync();super.tearDown();}
    public void testReportedEpisode54ReturnsPlayableTracks() throws Exception {
        assertTrue(getInstrumentation().getTargetContext().getPackageName().endsWith(".qa"));
        Api api = new Api(getInstrumentation().getTargetContext());
        JSONObject model = Api.findVideoModel(api.videoModel("7663495632371321880"), "7663495632371321880");
        assertNotNull("第54集没有返回手机播放模型", model);
        TrackPicker.Track track = TrackPicker.pick(model, 1080);
        assertNotNull("第54集没有可解码轨道", track);
        assertTrue("片源尺寸无效", track.size > 0);
    }
    public void testFailedPrimarySourceUsesOfficialBackupAndDecodes()throws Exception{
        android.content.Context context=getInstrumentation().getTargetContext();
        Api api=new Api(context);
        String vid="7663495632371321880";
        TrackPicker.Track original=TrackPicker.pick(Api.findVideoModel(api.videoModel(vid),vid),1080);
        JSONObject raw=new JSONObject(original.raw.toString());
        raw.put("main_url","https://127.0.0.1:1/unavailable.mp4");raw.put("backup_url",original.mainUrl);
        TrackPicker.Track track=new TrackPicker.Track(raw,original.definition,original.codec,original.width,original.height,
            original.size,"https://127.0.0.1:1/unavailable.mp4",original.mainUrl,original.spadeA,original.encrypted);
        cn.hongguo.tv.cache.MediaCache cache=new cn.hongguo.tv.cache.MediaCache(context);
        String bucket=original.definition+"-backup-qa";java.io.File cached=cache.plainFile(vid,bucket);
        if(cached.exists())assertTrue(cached.delete());
        track=new TrackPicker.Track(raw,bucket,original.codec,original.width,original.height,original.size,
            track.mainUrl,track.backupUrl,original.spadeA,original.encrypted);
        AtomicReference<java.io.File> result=new AtomicReference<>();AtomicReference<String> error=new AtomicReference<>();
        new EpisodePreparer(cache).prepare(vid,track,new PreparationCancellation(),new EpisodePreparer.Listener(){
            @Override public void onProgress(String stage,int pct){}
            @Override public void onDone(java.io.File file){result.set(file);}
            @Override public void onError(String message){error.set(message);}
        });
        assertNull("备用地址准备失败",error.get());assertNotNull("备用地址没有整集文件",result.get());
        android.media.MediaMetadataRetriever probe=new android.media.MediaMetadataRetriever();
        try{probe.setDataSource(result.get().getAbsolutePath());android.graphics.Bitmap frame=probe.getFrameAtTime(1000000);assertNotNull("备用片源未解码",frame);frame.recycle();}finally{probe.release();}
    }
    public void testActualTvDecoderPlays54Then55And70()throws Exception{
        android.content.Context context=getInstrumentation().getTargetContext();
        assertTrue(context.getPackageName().endsWith(".qa"));
        assertFalse("真实播放测试不向用户云账号写入进度",Account.load(context).loggedIn());
        ((AudioManager)context.getSystemService(android.content.Context.AUDIO_SERVICE)).setStreamVolume(AudioManager.STREAM_MUSIC,0,0);
        String series="7663491547907755033";
        Api api=new Api(context);JSONArray all=Api.parseEpisodes(api.episodes(series),series);
        JSONArray selected=new JSONArray();
        for(int number:new int[]{54,55,70}){
            JSONObject found=null;
            for(int index=0;index<all.length();index++){JSONObject row=all.getJSONObject(index);if(row.optInt("num")==number)found=row;}
            assertNotNull("官方剧集缺少第"+number+"集",found);selected.put(found);
        }
        for(int index=0;index<selected.length();index++){
            if(player!=null){main(()->{player.finish();return null;});getInstrumentation().waitForIdleSync();}
            Intent intent=PlayerActivity.intent(context,series,"无敌财神！我觉醒了唯一隐藏职业","",selected.toString())
                .putExtra(PlayerActivity.EXTRA_START_INDEX,index).putExtra("start_position_ms",0L).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            player=(PlayerActivity)getInstrumentation().startActivitySync(intent);
            long deadline=System.currentTimeMillis()+90000;boolean decoded=false;
            while(System.currentTimeMillis()<deadline){
                decoded=main(()->{MediaPlayer media=(MediaPlayer)field("player");try{return media!=null&&media.isPlaying()&&media.getVideoWidth()>0&&media.getCurrentPosition()>1000;}catch(IllegalStateException error){return false;}});
                if(decoded)break;Thread.sleep(100);
            }
            assertTrue("TV 未实际解码第"+selected.getJSONObject(index).optInt("num")+"集",decoded);
            int width=main(()->((MediaPlayer)field("player")).getVideoWidth());
            assertTrue("解码宽度无效",width>0);
            if(index==0)cn.hongguo.tv.ui.SeasonScreenshots.capture(getInstrumentation(),"mobile-episode54");
        }
    }
}
