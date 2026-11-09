package cn.hongguo.tv.account;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class QrMobileVerifierTest {
    private static Account candidate(){return Account.candidate("sessionid=synthetic-cookie","","","");}
    private static JSONObject json(String text){try{return new JSONObject(text);}catch(Exception error){throw new AssertionError(error);}}
    private static JSONObject valid(String path) {
        if(path.equals("/reading/user/info/v"))return json("{\"code\":0,\"data\":{\"user_id\":\"synthetic-mobile-user\",\"user_name\":\"测试用户\"}}");
        return json(path.contains("history")?"{\"code\":0,\"data\":{\"data_list\":[]}}":"{\"code\":0,\"data\":{\"video_shelf_info\":[]}}");
    }
    @Test public void verifiesProfileHistoryAndShelfInMobileScope()throws Exception {
        List<String> paths=new ArrayList<>();Account input=candidate();
        Account result=QrMobileVerifier.verify(input,(path,q,session)->{paths.add(path);assertEquals(input.cookie,session.cookie);if(q!=null){assertEquals("2",q.get("book_type"));assertEquals("1",q.get("limit"));}return valid(path);});
        assertEquals(3,paths.size());assertEquals("/reading/user/info/v",paths.get(0));assertEquals("synthetic-mobile-user",result.uid);assertEquals("测试用户",result.userName);assertTrue(input.uid.isEmpty());
    }
    @Test public void rejectsGuestIdentity()throws Exception {
        for(String profile:new String[]{"{\"code\":0,\"data\":{\"user_id\":\"0\"}}","{\"code\":0,\"data\":{\"user_id\":\"synthetic\",\"is_login\":false}}","{\"code\":401,\"data\":{}}"}) {
            try{QrMobileVerifier.verify(candidate(),(path,q,session)->json(profile));fail();}catch(QrLoginManager.QrException error){assertEquals("QR_MOBILE_SESSION_UNAVAILABLE",error.code);assertFalse(error.retryable);}
        }
    }
    @Test public void deniedHistoryOrShelfPreventsSuccess()throws Exception {
        for(String rejected:new String[]{"history","bookshelf"}) {
            try{QrMobileVerifier.verify(candidate(),(path,q,session)->path.contains(rejected)?json("{\"code\":401,\"data\":{}} "):valid(path));fail();}
            catch(QrLoginManager.QrException error){assertFalse(error.retryable);}
        }
    }
    @Test public void malformedAccountListsAreRejected()throws Exception {
        try{QrMobileVerifier.verify(candidate(),(path,q,session)->path.contains("history")?json("{\"code\":0,\"data\":{\"data_list\":\"invalid\"}}"):valid(path));fail();}
        catch(QrLoginManager.QrException error){assertEquals("QR_MOBILE_SESSION_UNAVAILABLE",error.code);}
    }
    @Test public void networkErrorsAreFixedAndRetryable()throws Exception {
        try{QrMobileVerifier.verify(candidate(),(path,q,session)->{throw new IOException("token=synthetic-sensitive");});fail();}
        catch(QrLoginManager.QrException error){assertTrue(error.retryable);assertFalse(error.getMessage().contains("synthetic-sensitive"));}
    }
    @Test public void redirectsMustUseOfficialHttpsAuthorizationPaths()throws Exception {
        assertEquals("https://hongguoduanju.com/passport/web/scan_login/?ticket=synthetic",OfficialQrTransport.trusted(OfficialQrTransport.ORIGIN+"/","/passport/web/scan_login/?ticket=synthetic"));
        for(String url:new String[]{"http://hongguoduanju.com/passport/","https://evil.example/passport/","https://hongguoduanju.com.evil.example/passport/","https://user:synthetic@hongguoduanju.com/passport/","https://hongguoduanju.com:8080/passport/","https://hongguoduanju.com/api/write"}) {
            try{OfficialQrTransport.trusted(OfficialQrTransport.ORIGIN+"/",url);fail();}catch(QrLoginManager.QrException error){assertEquals("QR_INVALID_REDIRECT",error.code);}
        }
    }
    @Test public void upstreamErrorsNeverExposeTheirBody()throws Exception {
        for(String payload:new String[]{"{\"data\":{\"error_code\":2035,\"description\":\"synthetic-sensitive\"}}","{\"data\":{\"error_code\":7,\"description\":\"synthetic-sensitive\"}}","{\"data\":{\"error_code\":0,\"captcha\":\"synthetic-sensitive\"}}"}){
            try{OfficialQrTransport.data(200,payload);fail();}catch(QrLoginManager.QrException error){assertFalse(error.getMessage().contains("synthetic-sensitive"));assertFalse(error.retryable);}
        }
    }
}
