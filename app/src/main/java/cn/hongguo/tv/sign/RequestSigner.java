package cn.hongguo.tv.sign;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import org.json.JSONArray;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.InflaterInputStream;
import com.bytedance.mobsec.metasec.ml.MS;
import cn.hongguo.tv.account.DeviceIdentity;

/** Domestic SDK JNI adapter. Signs the actual request bytes; no native offsets or emulator. */
public final class RequestSigner {
    private static Context application;
    private static boolean loaded;
    private static long manager;
    private static boolean failed;
    private final Context context;
    private static final String SHA64="c6831ab265a0cb76dacfa3b7041e9ea0f706490106a74e12f39058671cc14fe3";
    // armeabi-v7a 的签名 so 与 64 位版本是不同文件；校验必须按运行时 ABI 选择。
    private static final String SHA32="90a7a2b5e7f1f3c01100f2c1995b4de2371e9c90b8a2acff4fc2a8aa48764339";
    private static boolean is32Bit(){
        for(String abi:Build.SUPPORTED_ABIS)if("armeabi-v7a".equals(abi))return true;
        return false;
    }
    private static final String HEADER_PATTERN="X-(Argus|Gorgon|Helios|Khronos|Ladon|Medusa)";

    public RequestSigner(Context context){this.context=context.getApplicationContext();}
    public static synchronized void bind(Context context){application=context.getApplicationContext();}
    public static synchronized boolean isReady(){return loaded && manager!=0;}
    public static synchronized String failureReason(){return failed?"本机播放组件暂不可用":"";}

    public Map<String,String> sign(String url,Map<String,String> headers)throws IOException{
        return sign(url,headers,null);
    }
    public Map<String,String> sign(String url,Map<String,String> headers,byte[] body)throws IOException{
        synchronized(RequestSigner.class){
            try{
                initialize(url);
                long httpManager=MS.sHttpManager;
                Object result;
                if(httpManager!=0L){
                    // Header-ABI security call: the SDK receives the request URL
                    // plus every header that participates in the signature as a
                    // flat name/value String[] (the SDK adds its own X-* values).
                    String[][] built=headerPairs(headers);
                    result=ms.bd.c.m.a(50331649,0,httpManager,url,built[1]);
                }else{
                    // Fallback for SDK builds without the manager callback.
                    result=ms.bd.c.m.a(33554438,0,manager,url,body);
                }
                if(!(result instanceof String[]))throw new IOException("内容验证暂不可用");
                String[] pairs=(String[])result;
                Map<String,String> output=new LinkedHashMap<>();
                for(int i=0;i+1<pairs.length;i+=2){
                    String name=pairs[i],value=pairs[i+1];
                    if(name!=null && name.matches(HEADER_PATTERN) && value!=null && !value.isEmpty()
                        && value.indexOf('\r')<0 && value.indexOf('\n')<0)output.put(name,value);
                }
                if(!output.containsKey("X-Argus")||!output.containsKey("X-Gorgon")||!output.containsKey("X-Khronos"))
                    throw new IOException("内容验证暂不可用");
                return output;
            }catch(IOException error){throw error;}
            catch(Throwable error){throw new IOException("本机播放组件暂不可用");}
        }
    }

    private static final java.util.Set<String> SDK_HEADERS=new java.util.HashSet<>(java.util.Arrays.asList(
            "x-argus","x-gorgon","x-helios","x-khronos","x-ladon","x-medusa"));

    /** Flat name/value header list for the header-ABI call; SDK headers are excluded. */
    private static String[][] headerPairs(Map<String,String> source)throws IOException{
        java.util.LinkedHashMap<String,String> clean=new java.util.LinkedHashMap<>();
        for(Map.Entry<String,String> entry:source.entrySet()){
            String name=entry.getKey(),value=entry.getValue();
            if(name==null||value==null)continue;
            if(name.isEmpty())throw new IOException("请求头格式不正确");
            for(int ci=0;ci<name.length();ci++){char cc=name.charAt(ci);if(cc<0x21||cc>0x7e)throw new IOException("请求头格式不正确");}
            if(value.indexOf('\r')>=0||value.indexOf('\n')>=0)throw new IOException("请求头格式不正确");
            String canonical=name.toLowerCase(java.util.Locale.ROOT);
            if(SDK_HEADERS.contains(canonical))continue;
            clean.put(name,value);
        }
        String[] pairs=new String[clean.size()*2];
        int at=0;
        for(Map.Entry<String,String> entry:clean.entrySet()){
            pairs[at++]=entry.getKey();pairs[at++]=entry.getValue();
        }
        return new String[][]{{"count",String.valueOf(clean.size())},pairs};
    }

    private void initialize(String url)throws Exception{
        Context ctx=application!=null?application:context;
        if(ctx==null)throw new IOException("播放组件尚未就绪");
        Uri uri=Uri.parse(url);
        String version=uri.getQueryParameter("version_name");
        String code=uri.getQueryParameter("version_code");
        MS.sVersion=version==null?"7.4.3.32":version;
        try{MS.sVersionCode=code==null?74332:Integer.parseInt(code);}catch(NumberFormatException error){MS.sVersionCode=74332;}
        if(!loaded){
            File library=new File(ctx.getApplicationInfo().nativeLibraryDir,"libhongguo_signer.so");
            String expected=is32Bit()?SHA32:SHA64;
            if(!expected.equals(sha256(library)))
                throw new IOException("播放组件版本不匹配，请更新应用");
            MS.sFilesDir=ctx.getFilesDir().getAbsolutePath();
            MS.sContext=ctx;
            // 32 位签名 so 依赖系统 libstdc++；libc++_shared 只在 64 位包内。
            if(!is32Bit())System.loadLibrary("c++_shared");
            System.load(library.getAbsolutePath());
            loaded=true;
        }
        if(manager!=0)return;
        byte[] metadata;
        try(InputStream stream=new InflaterInputStream(ctx.getAssets().open("sdk-metadata.bin"))){
            ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] block=new byte[2048];int count;
            while((count=stream.read(block))>0){if(output.size()+count>16384)throw new IOException("播放组件配置异常");output.write(block,0,count);}
            metadata=output.toByteArray();
        }
        JSONArray config=new JSONArray(new String(metadata,"UTF-8"));
        String[] device=DeviceIdentity.load(ctx);
        config.put(6,device[0]);config.put(8,device[1]);config.put(13,device[0]);
        ms.bd.c.m.a(16777219,0,0L,null,ctx);
        if(!Boolean.TRUE.equals(ms.bd.c.m.a(67108865,0,0L,config.toString(),null)))
            throw new IOException("播放组件初始化失败");
        Object id=ms.bd.c.m.a(67108866,0,0L,null,null);
        if(!(id instanceof Long))throw new IOException("播放组件初始化失败");
        manager=((Long)id).longValue();failed=false;
    }

    private static String sha256(File file)throws Exception{
        try(InputStream input=new FileInputStream(file)){
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            byte[] block=new byte[16384];int count;while((count=input.read(block))>0)digest.update(block,0,count);
            StringBuilder output=new StringBuilder();for(byte value:digest.digest())output.append(String.format(Locale.US,"%02x",value&255));
            return output.toString();
        }
    }
}
