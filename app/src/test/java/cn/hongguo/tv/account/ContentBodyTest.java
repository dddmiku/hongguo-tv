package cn.hongguo.tv.account;

import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;
import java.util.zip.GZIPInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public class ContentBodyTest {
    @Test public void compressedBytesAndSigningDigestReferToTheSameRequest()throws Exception{
        JSONObject body=new JSONObject().put("video_id","12345678").put("dr_scene","player");
        ContentBody encoded=new ContentBody(body);
        assertEquals(ContentBody.digest(encoded.bytes),encoded.digest);
        ByteArrayOutputStream decoded=new ByteArrayOutputStream();
        try(GZIPInputStream input=new GZIPInputStream(new ByteArrayInputStream(encoded.bytes))){byte[] block=new byte[1024];int count;while((count=input.read(block))>0)decoded.write(block,0,count);}
        assertEquals("12345678",new JSONObject(decoded.toString("UTF-8")).getString("video_id"));
        assertNotEquals(ContentBody.digest(body.toString().getBytes("UTF-8")),encoded.digest);
    }
    @Test public void formDigestIsStableForKnownPayload()throws Exception{
        assertEquals("99914B932BD37A50B983C5E7C90AE93B",ContentBody.digest("{}".getBytes("UTF-8")));
    }
}
