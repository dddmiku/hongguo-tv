package cn.hongguo.tv.api;

import android.test.ActivityInstrumentationTestCase2;
import cn.hongguo.tv.account.Account;
import cn.hongguo.tv.ui.PlayerActivity;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Launch the real PlayerActivity for a real episode and watch actual position advance. */
public class PlayerActivityLiveTest extends ActivityInstrumentationTestCase2<PlayerActivity> {
    public PlayerActivityLiveTest() { super(PlayerActivity.class); }

    public void testRealEpisodePlays() throws Exception {
        Api api = new Api(getInstrumentation().getTargetContext());
        List<Api.Series> rows = api.rank("series", "recommend", 3);
        String seriesId = rows.get(0).seriesId;
        JSONObject detail = api.episodes(seriesId);
        JSONArray list = detail.optJSONObject("data").optJSONObject(seriesId)
                .optJSONObject("video_data").optJSONArray("video_list");
        JSONArray eps = new JSONArray();
        for (int i = 0; i < Math.min(3, list.length()); i++) {
            JSONObject e = list.optJSONObject(i);
            eps.put(new JSONObject().put("vid", e.optString("vid"))
                    .put("num", e.optInt("num", i + 1))
                    .put("title", e.optString("title", "第" + (i + 1) + "集")));
        }
        android.content.Intent intent = PlayerActivity.intent(
                getInstrumentation().getTargetContext(), seriesId, "验证剧", eps.toString());
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        setActivityIntent(intent);
        PlayerActivity activity = getActivity();
        assertNotNull(activity);
        long deadline = System.currentTimeMillis() + 60000;
        int duration = 0, position = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000);
            Integer d = (Integer) field(activity, "playbackDurationProbe");
            if (d == null) d = 0;
            Integer p = (Integer) field(activity, "playbackPositionProbe");
            if (p == null) p = 0;
            duration = d; position = p;
            String status = String.valueOf(field(activity, "playbackStatusProbe"));
            if (duration > 1000 && position > 1000) break;
            if (status.contains("失败") || status.contains("错误")) break;
        }
        android.util.Log.i("HgAct", "duration=" + duration + " position=" + position
                + " status=" + field(activity, "playbackStatusProbe"));
        assertTrue("真实播放页未起播 duration=" + duration + " position=" + position,
                duration > 1000 && position > 1000);
    }

    private static Object field(Object target, String name) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable ignored) { return null; }
    }
}
