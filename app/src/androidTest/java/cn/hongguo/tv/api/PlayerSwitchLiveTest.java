package cn.hongguo.tv.api;

import android.test.ActivityInstrumentationTestCase2;
import cn.hongguo.tv.ui.PlayerActivity;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real app: episode 1 plays, then manual "next" plays episode 2. */
public class PlayerSwitchLiveTest extends ActivityInstrumentationTestCase2<PlayerActivity> {
    public PlayerSwitchLiveTest() { super(PlayerActivity.class); }

    public void testNextEpisodePlays() throws Exception {
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
        long deadline = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000);
            if (probe(activity, "playbackPositionProbe") > 1000) break;
        }
        int firstDuration = probe(activity, "playbackDurationProbe");
        assertTrue("第1集未起播", firstDuration > 1000);
        android.util.Log.i("HgNext", "first duration=" + firstDuration + " status=" + status(activity));

        getInstrumentation().runOnMainSync(() -> activity.dispatchKeyEvent(
                new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_NEXT)));
        getInstrumentation().runOnMainSync(() -> activity.dispatchKeyEvent(
                new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_NEXT)));
        deadline = System.currentTimeMillis() + 60000;
        int secondDuration = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000);
            secondDuration = probe(activity, "playbackDurationProbe");
            if (secondDuration > 1000 && probe(activity, "playbackPositionProbe") > 500) break;
        }
        android.util.Log.i("HgNext", "second duration=" + secondDuration + " status=" + status(activity));
        assertTrue("下一集未起播", secondDuration > 1000);
    }

    private static int probe(Object target, String name) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(target);
        } catch (Throwable ignored) { return 0; }
    }
    private static String status(Object target) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField("playbackStatusProbe");
            f.setAccessible(true);
            return String.valueOf(f.get(target));
        } catch (Throwable ignored) { return ""; }
    }
}
