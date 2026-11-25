package cn.hongguo.tv.api;

import android.test.InstrumentationTestCase;
import cn.hongguo.tv.account.Account;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Verify the website playback route on a real device: resolve + CDN range probe. */
public class WebPlaybackDeviceTest extends InstrumentationTestCase {
    public void testResolveAndProbe() throws Exception {
        Api api = new Api(getInstrumentation().getTargetContext());
        List<Api.Series> rows = api.rank("series", "recommend", 3);
        String seriesId = rows.get(0).seriesId;
        JSONObject detail = api.episodes(seriesId);
        JSONArray list = detail.optJSONObject("data").optJSONObject(seriesId)
                .optJSONObject("video_data").optJSONArray("video_list");
        String vid = list.optJSONObject(0).optString("vid");
        String cookie = Account.load(getInstrumentation().getTargetContext()).cookie;
        WebPlayback.Result result = WebPlayback.resolve(vid, seriesId, "1080p", cookie);
        android.util.Log.i("HgWeb", "resolved definition=" + result.definition + " https=" + result.url.startsWith("https://"));
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(result.url).openConnection();
        c.setRequestProperty("Range", "bytes=0-1023");
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        int code = c.getResponseCode();
        int total = c.getContentLength();
        c.disconnect();
        android.util.Log.i("HgWeb", "cdn HTTP=" + code + " bytes=" + total);
        assertTrue("CDN 未返回内容", code == 200 || code == 206);
        assertTrue(code == 206 || total > 100000);
    }
}
