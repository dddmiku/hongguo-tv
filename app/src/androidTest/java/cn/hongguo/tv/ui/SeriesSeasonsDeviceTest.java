package cn.hongguo.tv.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import cn.hongguo.tv.api.Api;
import cn.hongguo.tv.cache.LibraryRepository;
import cn.hongguo.tv.cache.SeasonRepository;
import cn.hongguo.tv.cache.WatchRecord;

/** Official public seasons and actual TV detail-sheet focus, in the independent QA package. */
public class SeriesSeasonsDeviceTest extends InstrumentationTestCase {
    private MainActivity activity;
    private SeriesDialog sheet;
    private static final String FIRST="7481108346636864536",SECOND="7577339455728520217";
    private <T>T main(Supplier<T> action){AtomicReference<T> result=new AtomicReference<>();getInstrumentation().runOnMainSync(()->result.set(action.get()));return result.get();}
    private Object field(String name){try{Field field=SeriesDialog.class.getDeclaredField(name);field.setAccessible(true);return field.get(sheet);}catch(Exception error){throw new AssertionError(error);}}
    private void waitFor(String label,Supplier<Boolean> test,long timeout)throws Exception{long end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end){if(main(test))return;Thread.sleep(100);}fail(label);}
    private View tag(String value){return ((AlertDialog)field("dialog")).getWindow().getDecorView().findViewWithTag(value);}
    private boolean contains(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().contains(text))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(contains(group.getChildAt(i),text))return true;}return false;}
    @Override protected void setUp()throws Exception{super.setUp();assertTrue(getInstrumentation().getTargetContext().getPackageName().endsWith(".qa"));cn.hongguo.tv.account.Account.clear(getInstrumentation().getTargetContext());}
    @Override protected void tearDown()throws Exception{main(()->{if(sheet!=null)((AlertDialog)field("dialog")).dismiss();if(activity!=null)activity.finish();return null;});super.tearDown();}

    public void testOfficialSeasonCountsAndRemoteDetailSelection()throws Exception{
        Api api=new Api(getInstrumentation().getTargetContext());SeasonRepository.Overview result=new SeasonRepository(api).overview(FIRST);
        assertEquals("found",result.status);assertEquals(2,result.items.size());assertEquals(79,result.items.get(0).series.episodeCount);assertEquals(111,result.items.get(1).series.episodeCount);assertNotNull(result.next);assertEquals(SECOND,result.next.series.seriesId);
        Intent intent=new Intent(getInstrumentation().getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity=(MainActivity)getInstrumentation().startActivitySync(intent);
        WatchRecord before=LibraryRepository.get(activity).store().find("local",SECOND);
        sheet=main(()->SeriesDialog.show(activity,result.items.get(0).series,LibraryRepository.get(activity)));
        waitFor("没有显示官方两季卡片",()->tag("detail-season:"+FIRST)!=null&&tag("detail-season:"+SECOND)!=null&&tag("detail-first").isEnabled(),120000);
        assertTrue(main(()->contains(tag("detail-season:"+FIRST),"79 集")&&contains(tag("detail-season:"+SECOND),"111 集")));
        assertTrue(main(()->tag("detail-season:"+FIRST).isSelected()));
        main(()->{View resume=tag("detail-resume");(resume==null?tag("detail-first"):resume).requestFocus();return null;});
        getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);getInstrumentation().waitForIdleSync();
        assertEquals("detail-season:"+FIRST,main(()->((AlertDialog)field("dialog")).getCurrentFocus().getTag()));
        SeasonScreenshots.capture(getInstrumentation(),"seasons-details");
        getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);getInstrumentation().waitForIdleSync();
        assertEquals("detail-season:"+SECOND,main(()->((AlertDialog)field("dialog")).getCurrentFocus().getTag()));
        getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("遥控选第二季没有更新详情和集数",()->((Api.Series)field("series")).seriesId.equals(SECOND)&&tag("detail-first").isEnabled()&&contains((View)field("body"),"111 集"),120000);
        assertTrue(main(()->tag("detail-season:"+SECOND).isSelected()));
        assertEquals(111,main(()->((org.json.JSONArray)field("episodes")).length()).intValue());
        WatchRecord after=LibraryRepository.get(activity).store().find("local",SECOND);
        if(before==null)assertNull("详情不得记录观看历史",after);
        else{assertNotNull(after);assertTrue(before.samePosition(after));assertEquals(before.watchedAt,after.watchedAt);assertEquals(before.pending,after.pending);}
        main(()->{tag("detail-season:"+FIRST).performClick();return null;});
        waitFor("反向选第一季未恢复详情",()->((Api.Series)field("series")).seriesId.equals(FIRST)&&tag("detail-first").isEnabled()&&((org.json.JSONArray)field("episodes")).length()==79,120000);
        assertTrue(main(()->tag("detail-season:"+FIRST).isSelected()));
    }
}
