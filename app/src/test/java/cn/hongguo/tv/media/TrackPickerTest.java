package cn.hongguo.tv.media;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/** Track selection must never leave a playable model as "no quality". */
public class TrackPickerTest {
    private static JSONArray tracks(Object[]... rows) throws Exception {
        JSONArray list = new JSONArray();
        for (Object[] row : rows) {
            JSONObject meta = new JSONObject();
            meta.put("codec_type", row[0]);
            meta.put("definition", row[1]);
            meta.put("vwidth", row[2]);
            meta.put("vheight", row[3]);
            meta.put("size", row[4]);
            JSONObject t = new JSONObject();
            t.put("main_url", "https://cdn.example/" + row[1] + ".mp4");
            t.put("video_meta", meta);
            list.put(t);
        }
        return list;
    }

    private static final TrackPicker.Caps AVC_ONLY = (codec, w, h) -> "h264".equals(codec);
    private static final TrackPicker.Factory FACTORY = (raw, meta, def, codec, w, h, size) ->
            new TrackPicker.Track(raw, def, codec, w, h, size, "https://cdn.example/x.mp4", "", "", false);

    @Test public void fallsBackToAnotherDefinitionWhenNoneFitsTheQualityCap() throws Exception {
        JSONArray list = tracks(new Object[]{"h264", "1080p", 1920, 1080, 4096L});
        TrackPicker.Track picked = TrackPicker.select(list, 480, AVC_ONLY, FACTORY);
        assertNotNull("A 1080p-only AVC track is still playable when the cap was 480", picked);
        assertEquals("1080p", picked.definition);
    }

    @Test public void neverSelectsByteVc2EvenAsFallback() throws Exception {
        JSONArray list = tracks(new Object[]{"bytevc2", "1080p", 1920, 1080, 4096L});
        assertNull("bv2C cannot be decoded by TV MediaPlayer", TrackPicker.select(list, 0, AVC_ONLY, FACTORY));
    }

    @Test public void prefersTheHighestTierThatFitsTheCap() throws Exception {
        JSONArray list = tracks(
                new Object[]{"h264", "1080p", 1920, 1080, 4096L},
                new Object[]{"h264", "720p", 1280, 720, 2048L},
                new Object[]{"h264", "480p", 854, 480, 1024L});
        assertEquals("720p", TrackPicker.select(list, 720, AVC_ONLY, FACTORY).definition);
    }
}
