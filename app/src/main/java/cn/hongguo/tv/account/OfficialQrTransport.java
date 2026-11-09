package cn.hongguo.tv.account;

import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** Official web QR protocol. No shared Http/CookieHandler or playback settings. */
final class OfficialQrTransport implements QrLoginManager.Transport {
    static final String ORIGIN="https://hongguoduanju.com";
    static final String AID="513755";
    private static final Set<String> HOSTS=new HashSet<>(Arrays.asList("hongguoduanju.com","www.hongguoduanju.com","security.snssdk.com"));
    private static final Set<String> AUTH=new HashSet<>(Arrays.asList("sessionid","sessionid_ss","sid_tt","sid_guard","sid_ucp_v1","ssid_ucp_v1","tt_sessionid"));
    private static final Set<String> COOKIES=new HashSet<>(Arrays.asList("sessionid","sessionid_ss","sid_tt","sid_guard","sid_ucp_v1","ssid_ucp_v1","tt_sessionid",
            "uid_tt","uid_tt_ss","passport_csrf_token","passport_csrf_token_default","passport_auth_status","passport_auth_status_ss"));
    private static final int MAX_BYTES=1024*1024;
    private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private volatile HttpURLConnection active;
    private volatile boolean closed;
    private String headerToken="";

    private static final class Reply {
        int code;
        String body="",location="";
    }
    private static Map<String,String> params() {
        Map<String,String> result=new LinkedHashMap<>();
        result.put("aid",AID);result.put("next",ORIGIN+"/");result.put("language","zh");result.put("is_from_ttaccountsdk","1");
        result.put("passport_jssdk_version","2.4.13");result.put("passport_jssdk_type","normal");result.put("account_sdk_source","web");
        return result;
    }
    private static String endpoint(String path,Map<String,String> query)throws IOException {
        StringBuilder result=new StringBuilder(ORIGIN).append(path).append('?');
        boolean first=true;
        for(Map.Entry<String,String> entry:query.entrySet()) {
            if(!first)result.append('&');first=false;
            result.append(URLEncoder.encode(entry.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(entry.getValue(),"UTF-8"));
        }
        return result.toString();
    }
    private Reply get(String url)throws IOException {
        if(closed)throw new QrLoginManager.QrException("QR_CANCELLED","已取消扫码登录",false);
        HttpURLConnection conn=null;
        try {
            URI uri=new URI(url);
            conn=(HttpURLConnection)uri.toURL().openConnection();active=conn;
            conn.setConnectTimeout(8000);conn.setReadTimeout(20000);conn.setUseCaches(false);conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
            conn.setRequestProperty("Origin",ORIGIN);conn.setRequestProperty("Referer",ORIGIN+"/");
            conn.setRequestProperty("Accept","application/json");conn.setRequestProperty("Accept-Encoding","identity");
            for(Map.Entry<String,List<String>> entry:cookies.get(uri,Collections.emptyMap()).entrySet()) {
                conn.setRequestProperty(entry.getKey(),join(entry.getValue()));
            }
            Reply reply=new Reply();reply.code=conn.getResponseCode();
            if(closed)throw new QrLoginManager.QrException("QR_CANCELLED","已取消扫码登录",false);
            cookies.put(uri,conn.getHeaderFields());
            reply.location=conn.getHeaderField("Location");
            String token=conn.getHeaderField("x-tt-token");if(text(token,8192))headerToken=token;
            try(InputStream stream=reply.code>=400?conn.getErrorStream():conn.getInputStream()) {
                if(stream!=null) {
                    ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] bytes=new byte[8192];int count;
                    while((count=stream.read(bytes))!=-1){if(closed)throw new QrLoginManager.QrException("QR_CANCELLED","已取消扫码登录",false);buffer.write(bytes,0,count);if(buffer.size()>MAX_BYTES)throw new QrLoginManager.QrException("QR_INVALID_RESPONSE","扫码登录服务返回异常，请刷新二维码",false);}
                    reply.body=new String(buffer.toByteArray(),"UTF-8");
                }
            }
            return reply;
        }catch(QrLoginManager.QrException error){throw error;
        }catch(Exception error){throw new QrLoginManager.QrException("QR_NETWORK","网络暂时中断，正在重试",true);
        }finally{if(conn!=null)conn.disconnect();active=null;if(closed){cookies.getCookieStore().removeAll();headerToken="";}}
    }
    private static String join(List<String> values){StringBuilder text=new StringBuilder();for(String value:values){if(text.length()>0)text.append("; ");text.append(value);}return text.toString();}
    static JSONObject data(int status,String body)throws IOException {
        if(status!=200)throw new QrLoginManager.QrException("QR_NETWORK","暂时无法连接红果扫码登录服务，正在重试",true);
        try {
            JSONObject payload=new JSONObject(body),data=payload.optJSONObject("data");
            if(data==null)throw new QrLoginManager.QrException("QR_INVALID_RESPONSE","扫码登录服务返回异常，请刷新二维码",false);
            if(!data.optString("captcha","").isEmpty())throw new QrLoginManager.QrException("QR_VERIFICATION_REQUIRED","官网要求额外安全验证，请稍后刷新或使用验证码登录",false);
            int code=data.optInt("error_code",payload.optInt("code",0));
            if(code==2035)throw new QrLoginManager.QrException("QR_EXPIRED","二维码已失效，请刷新二维码",false);
            if(code!=0)throw new QrLoginManager.QrException("QR_UPSTREAM_REJECTED","官网暂时无法完成扫码登录，请刷新或使用验证码登录",false);
            return data;
        }catch(QrLoginManager.QrException error){throw error;
        }catch(Exception error){throw new QrLoginManager.QrException("QR_INVALID_RESPONSE","扫码登录服务返回异常，请刷新二维码",false);}
    }
    public QrLoginManager.Ticket create()throws IOException {
        Reply reply=get(endpoint("/passport/web/get_qrcode/",params()));JSONObject data=data(reply.code,reply.body);
        String token=data.optString("token","");
        if(!text(token,8192))throw new QrLoginManager.QrException("QR_INVALID_RESPONSE","官网没有返回有效二维码，请刷新",false);
        long expiry=data.optLong("expire_time",0)*1000L;
        return new QrLoginManager.Ticket(token,image(data.optString("qrcode","")),expiry);
    }
    public JSONObject poll(QrLoginManager.Ticket ticket)throws IOException {
        Map<String,String> query=params();query.put("token",ticket.token);
        Reply reply=get(endpoint("/passport/web/check_qrconnect/",query));return data(reply.code,reply.body);
    }
    public Account candidate(JSONObject confirmation)throws IOException {
        String redirect=confirmation.optString("redirect_url","");
        if(!redirect.isEmpty()) {
            String target=trusted(ORIGIN+"/",redirect);
            boolean completed=false;
            for(int i=0;i<6;i++) {
                Reply reply=get(target);
                if(reply.code==301||reply.code==302||reply.code==303||reply.code==307||reply.code==308){target=trusted(target,reply.location);continue;}
                if(reply.code!=200)throw new QrLoginManager.QrException("QR_AUTHORIZATION_FAILED","手机已确认，但官网登录授权暂时无法完成，请重试",false);
                completed=true;break;
            }
            if(!completed)throw new QrLoginManager.QrException("QR_AUTHORIZATION_FAILED","手机已确认，但官网登录授权暂时无法完成，请重试",false);
        }
        Map<String,String> values=new LinkedHashMap<>();boolean authenticated=!headerToken.isEmpty();
        for(HttpCookie cookie:cookies.getCookieStore().getCookies()) {
            String domain=cookie.getDomain();if(domain!=null&&domain.startsWith("."))domain=domain.substring(1);if(domain!=null)domain=domain.toLowerCase(Locale.ROOT);
            if(COOKIES.contains(cookie.getName())&&HOSTS.contains(domain)&&!cookie.hasExpired()&&text(cookie.getValue(),8192)) {
                values.put(cookie.getName(),cookie.getValue());if(AUTH.contains(cookie.getName()))authenticated=true;
            }
        }
        if(!authenticated)throw new QrLoginManager.QrException("QR_INCOMPLETE_SESSION","手机已确认，但官网没有返回可用登录态，请重新扫码",false);
        StringBuilder result=new StringBuilder();
        for(Map.Entry<String,String> entry:values.entrySet()){if(result.length()>0)result.append("; ");result.append(entry.getKey()).append('=').append(entry.getValue());}
        return Account.candidate(result.toString(),headerToken,"","");
    }
    static boolean text(String value,int limit){if(value==null||value.isEmpty()||value.length()>limit)return false;for(int i=0;i<value.length();i++)if(value.charAt(i)<32)return false;return true;}

    /** Candidate-account probes never use the generic HTTP error/body logger. */
    static JSONObject readMobile(String url,Map<String,String> headers)throws IOException {
        HttpURLConnection connection=null;
        try {
            if(Thread.currentThread().isInterrupted())throw new IOException();
            URI uri=new URI(url);
            if(!"https".equals(uri.getScheme())||!"api5-normal-sinfonlinea.fqnovel.com".equals(uri.getHost())||!uri.getPath().startsWith("/reading/"))
                throw new QrLoginManager.QrException("QR_MOBILE_SESSION_UNAVAILABLE","手机账号连接地址无效，请重试",false);
            connection=(HttpURLConnection)uri.toURL().openConnection();
            connection.setConnectTimeout(8000);connection.setReadTimeout(20000);connection.setUseCaches(false);connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");for(Map.Entry<String,String> entry:headers.entrySet())connection.setRequestProperty(entry.getKey(),entry.getValue());
            int status=connection.getResponseCode();
            if(status!=200)throw new QrLoginManager.QrException("QR_MOBILE_SESSION_UNAVAILABLE","手机账号数据暂时不可用，请重试或使用验证码登录",status>=500||status==408||status==429);
            try(InputStream stream=connection.getInputStream()) {
                ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] bytes=new byte[8192];int count;
                while((count=stream.read(bytes))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException();buffer.write(bytes,0,count);if(buffer.size()>MAX_BYTES)throw new QrLoginManager.QrException("QR_MOBILE_SESSION_UNAVAILABLE","手机账号数据格式异常，请重试",false);}
                return new JSONObject(new String(buffer.toByteArray(),"UTF-8"));
            }
        }catch(QrLoginManager.QrException error){throw error;
        }catch(Exception error){throw new QrLoginManager.QrException("QR_MOBILE_SESSION_UNAVAILABLE","手机账号连接暂时中断，正在重试",true);
        }finally{if(connection!=null)connection.disconnect();}
    }
    static String trusted(String base,String value)throws IOException {
        try {
            if(!text(value,16384))throw new IllegalArgumentException();
            URI url=new URI(base).resolve(value);
            String path=url.getPath(),host=url.getHost();
            if(!"https".equalsIgnoreCase(url.getScheme())||host==null||!HOSTS.contains(host.toLowerCase(Locale.ROOT))
                    ||url.getUserInfo()!=null||(url.getPort()!=-1&&url.getPort()!=443)||!("/".equals(path)||path.startsWith("/passport/")))throw new IllegalArgumentException();
            return url.toString();
        }catch(Exception error){throw new QrLoginManager.QrException("QR_INVALID_REDIRECT","扫码登录的授权地址无效",false);}
    }
    static byte[] image(String value)throws IOException {
        try {
            if(value==null||value.length()>MAX_BYTES)throw new IllegalArgumentException();
            if(value.startsWith("data:"))value=value.substring(value.indexOf(',')+1);
            if(!value.matches("[A-Za-z0-9+/]*={0,2}"))throw new IllegalArgumentException();
            byte[] bytes=Base64.decode(value,Base64.NO_WRAP);
            boolean png=bytes.length>=8&&bytes[0]==(byte)0x89&&bytes[1]=='P'&&bytes[2]=='N'&&bytes[3]=='G'&&bytes[4]==13&&bytes[5]==10&&bytes[6]==26&&bytes[7]==10;
            boolean jpeg=bytes.length>=3&&bytes[0]==(byte)0xff&&bytes[1]==(byte)0xd8&&bytes[2]==(byte)0xff;
            if(bytes.length>512*1024||(!png&&!jpeg))throw new IllegalArgumentException();
            return bytes;
        }catch(Exception error){throw new QrLoginManager.QrException("QR_INVALID_RESPONSE","官网返回的二维码格式不正确",false);}
    }
    public void close(){closed=true;HttpURLConnection connection=active;if(connection!=null)connection.disconnect();cookies.getCookieStore().removeAll();headerToken="";}
}
