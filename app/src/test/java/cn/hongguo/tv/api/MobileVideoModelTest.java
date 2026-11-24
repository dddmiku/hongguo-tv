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

    @Test public void playerQueryGainsManifestVersionCodeOnlyForPlayerRoutes()throws Exception{
        java.util.LinkedHashMap<String,String> base=new java.util.LinkedHashMap<>();
        base.put("aid","8662");base.put("version_code","74332");
        String player=Api.playerQuery(base,"/novel/player/multi_video_model/player/v1",null);
        assertTrue(player.contains("manifest_version_code=74332"));
        String other=Api.playerQuery(base,"/reading/bookapi/bookmall/tab/v",null);
        assertFalse(other.contains("manifest_version_code"));
    }
    @Test public void playerQueryPreservesExistingManifestVersionCode()throws Exception{
        java.util.LinkedHashMap<String,String> base=new java.util.LinkedHashMap<>();
        base.put("version_code","74332");
        java.util.LinkedHashMap<String,String> extra=new java.util.LinkedHashMap<>();
        extra.put("manifest_version_code","111");
        String query=Api.playerQuery(base,"/novel/player/video_model/v1",extra);
        assertTrue(query.contains("manifest_version_code=111"));
        assertFalse(query.contains("manifest_version_code=74332"));
    }
}