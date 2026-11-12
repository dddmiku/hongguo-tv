package cn.hongguo.tv.api;

import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class MobileVideoModelTest {
    @Test public void requestedVideoIsReadFromTheOfficialContentTypeBucket()throws Exception{
        JSONObject target=new JSONObject().put("marker","requested");
        JSONObject other=new JSONObject().put("marker","other");
        JSONObject bucket=new JSONObject().put("12345678",new JSONObject().put("video_model",target.toString()))
            .put("87654321",new JSONObject().put("video_model",other.toString()));
        JSONObject response=new JSONObject().put("data",new JSONObject().put("1004",bucket));
        assertEquals("requested",Api.findVideoModel(response,"12345678").getString("marker"));
        assertNull(Api.findVideoModel(response,"99999999"));
    }
}
