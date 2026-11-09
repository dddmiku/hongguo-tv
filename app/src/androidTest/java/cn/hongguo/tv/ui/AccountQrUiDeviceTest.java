package cn.hongguo.tv.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import cn.hongguo.tv.account.Account;
import cn.hongguo.tv.account.QrLoginManager;

/** Synthetic QR UI states only; no SMS, real scan or network request is issued. */
public class AccountQrUiDeviceTest extends InstrumentationTestCase {
    private AccountActivity activity;

    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue(getInstrumentation().getTargetContext().getPackageName().endsWith(".qa"));
        Account.clear(getInstrumentation().getTargetContext());
        activity=(AccountActivity)getInstrumentation().startActivitySync(new Intent(
                getInstrumentation().getTargetContext(),AccountActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        // Mode transitions remain local during these tests; backend contracts are tested separately.
        getInstrumentation().runOnMainSync(()->{
            try {Field f=AccountActivity.class.getDeclaredField("foreground");f.setAccessible(true);f.set(activity,false);}
            catch(Exception e){throw new AssertionError(e);}
        });
    }

    @Override protected void tearDown() throws Exception {
        if(activity!=null)getInstrumentation().runOnMainSync(()->activity.finish());
        Account.clear(getInstrumentation().getTargetContext());
        super.tearDown();
    }

    private Object field(String name)throws Exception {
        Field f=AccountActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);
    }

    private void invoke(String name,Class<?>[] types,Object...args)throws Exception {
        Method method=AccountActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);
        getInstrumentation().runOnMainSync(()->{
            try{method.invoke(activity,args);}catch(Exception e){throw new AssertionError(e);}
        });
        getInstrumentation().waitForIdleSync();
    }

    private void scanMode()throws Exception {
        invoke("selectLoginMode",new Class<?>[]{boolean.class},true);
    }

    public void testQrModeHidesSmsKeypadAndHasRemoteActions()throws Exception {
        invoke("showKeypad",new Class<?>[]{int.class},0);
        assertEquals(View.VISIBLE,((View)field("keypadHost")).getVisibility());
        scanMode();
        View refresh=(View)field("qrRefreshBtn"),cancel=(View)field("qrCancelBtn");
        assertEquals(View.GONE,((View)field("loginForm")).getVisibility());
        assertEquals(View.GONE,((View)field("keypadHost")).getVisibility());
        assertEquals(View.VISIBLE,((View)field("qrPanel")).getVisibility());
        assertTrue(((View)field("qrModeBtn")).isSelected());
        assertEquals(refresh,activity.getCurrentFocus());
        getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        getInstrumentation().waitForIdleSync();
        assertEquals(cancel,activity.getCurrentFocus());
        getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        getInstrumentation().waitForIdleSync();
        assertEquals(field("qrModeBtn"),activity.getCurrentFocus());
        invoke("showKeypad",new Class<?>[]{int.class},1);
        assertEquals(View.GONE,((View)field("keypadHost")).getVisibility());
        assertNull(field("qrLogin"));
    }

    public void testSwitchingBackToSmsRestoresInputWithoutForcingKeyboard()throws Exception {
        scanMode();
        invoke("selectLoginMode",new Class<?>[]{boolean.class},false);
        assertEquals(View.VISIBLE,((View)field("loginForm")).getVisibility());
        assertEquals(View.GONE,((View)field("qrPanel")).getVisibility());
        assertEquals(View.GONE,((View)field("keypadHost")).getVisibility());
        assertTrue(((View)field("mobileInput")).isFocusable());
        assertEquals(field("smsModeBtn"),activity.getCurrentFocus());
        invoke("showKeypad",new Class<?>[]{int.class},1);
        assertEquals(View.VISIBLE,((View)field("keypadHost")).getVisibility());
    }

    private byte[] image() {
        Bitmap bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        for(int y=12;y<40;y++)for(int x=12;x<40;x++)bitmap.setPixel(x,y,Color.BLACK);
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG,100,output);bitmap.recycle();return output.toByteArray();
    }

    private void state(String status,String message,byte[] image)throws Exception {
        invoke("renderQrState",new Class<?>[]{QrLoginManager.State.class},new QrLoginManager.State(
                "synthetic-local-attempt",status,message,System.currentTimeMillis()+60000,image));
    }

    public void testRetryKeepsImageAndExpirationRemovesExpiredQr()throws Exception {
        scanMode();state("waiting","等待手机扫码",image());
        ImageView view=(ImageView)field("qrImage");
        assertEquals(View.VISIBLE,view.getVisibility());assertNotNull(view.getDrawable());
        state("waiting","网络暂时不可用，正在重试",null);
        assertEquals(View.VISIBLE,view.getVisibility());assertNotNull(view.getDrawable());
        assertTrue(((TextView)field("qrStatusView")).getText().toString().contains("重试"));
        state("expired","二维码已过期，请刷新",null);
        assertEquals(View.INVISIBLE,view.getVisibility());assertNull(view.getDrawable());
        assertEquals(View.VISIBLE,((View)field("qrPlaceholder")).getVisibility());
        assertTrue(((View)field("qrRefreshBtn")).isEnabled());
    }

    public void testCleanupAndSmsModeRejectStaleQrImage()throws Exception {
        scanMode();state("waiting","等待手机扫码",image());
        invoke("stopQrLogin",new Class<?>[0]);
        assertEquals("",field("qrAttemptId"));assertEquals(0L,field("qrExpiresAt"));
        assertNull(((ImageView)field("qrImage")).getDrawable());
        invoke("selectLoginMode",new Class<?>[]{boolean.class},false);
        state("waiting","旧扫码请求",image());
        assertEquals(View.GONE,((View)field("qrPanel")).getVisibility());
        assertNull(((ImageView)field("qrImage")).getDrawable());
    }
}
