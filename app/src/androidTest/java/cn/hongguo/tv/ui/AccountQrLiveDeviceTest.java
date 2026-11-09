package cn.hongguo.tv.ui;

import android.content.Intent;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import android.widget.ImageView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import cn.hongguo.tv.account.Account;
import cn.hongguo.tv.account.AccountApi;

/** Explicit QA-only user scan harness; normal test runs never open a real QR. */
public class AccountQrLiveDeviceTest extends InstrumentationTestCase {
    private AccountActivity activity;
    private Object field(String name)throws Exception {Field field=AccountActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(activity);}
    private void invoke(String method,Class<?>[] types,Object...args)throws Exception {
        Method call=AccountActivity.class.getDeclaredMethod(method,types);call.setAccessible(true);
        getInstrumentation().runOnMainSync(()->{try{call.invoke(activity,args);}catch(Exception error){throw new AssertionError(error);}});
    }
    private void signal(String stage) {Bundle data=new Bundle();data.putString("stage",stage);getInstrumentation().sendStatus(2,data);}
    private void result(JSONObject data)throws Exception {
        try(FileOutputStream file=new FileOutputStream(new File(getInstrumentation().getTargetContext().getFilesDir(),"qr-live-result.json"))) {
            file.write(data.toString().getBytes("UTF-8"));
        }
    }

    public void testRealUserScanAndMobileData()throws Exception {
        android.content.Context context=getInstrumentation().getTargetContext();
        assertTrue(context.getPackageName().endsWith(".qa"));
        File enabled=new File(context.getFilesDir(),"qr-live-enabled");
        if(!enabled.isFile())return;
        enabled.delete();
        Account.clear(context);
        activity=(AccountActivity)getInstrumentation().startActivitySync(new Intent(context,AccountActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        invoke("selectLoginMode",new Class<?>[]{boolean.class},true);
        long deadline=System.currentTimeMillis()+300000;boolean ready=false;
        while(System.currentTimeMillis()<deadline) {
            Account account=Account.load(context);
            if(account.loggedIn()) {
                AccountApi api=new AccountApi(context);
                JSONObject info=api.userInfo();
                AccountApi.HistoryPage history=api.historyPage(account,0,1);
                java.util.List<String> favorites=api.favorites(account);
                boolean identity=info.optInt("code",-1)==0&&info.optJSONObject("data")!=null;
                result(new JSONObject().put("stage","confirmed").put("mobileSession",identity)
                        .put("historyReadable",true).put("favoritesReadable",true)
                        .put("historyTotal",history.total).put("favoritesTotal",favorites.size())
                        .put("confirmedAt",System.currentTimeMillis()));
                signal("confirmed");assertTrue(identity);return;
            }
            String status=(String)field("qrStatus");
            boolean image=((ImageView)field("qrImage")).getDrawable()!=null;
            if(image&&!ready){ready=true;signal("qr_ready");}
            if("expired".equals(status)){invoke("startQrLogin",new Class<?>[0]);signal("qr_refreshed");}
            if("failed".equals(status)) {
                result(new JSONObject().put("stage","failed").put("mobileSession",false).put("historyReadable",false).put("favoritesReadable",false));
                signal("failed");fail("TV扫码流程未完成，请查看界面固定错误提示");
            }
            Thread.sleep(500);
        }
        result(new JSONObject().put("stage","expired").put("mobileSession",false).put("historyReadable",false).put("favoritesReadable",false));
        signal("scan_timeout");fail("等待手机实际扫码确认超时");
    }
}
