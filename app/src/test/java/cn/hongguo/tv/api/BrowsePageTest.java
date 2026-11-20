package cn.hongguo.tv.api;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BrowsePageTest {
    @Test public void cursorAdvancesByRawRowsEvenAfterFilteringAndDeduplication() throws Exception {
        JSONObject first = new JSONObject().put("series_id", "9913377000000000001").put("title", "一部短剧");
        JSONArray rows = new JSONArray().put(first).put(first).put(new JSONObject());
        JSONObject data = new JSONObject().put("video_data", rows);
        JSONObject reply = new JSONObject().put("code", 0).put("data", data);
        Api.BrowsePage page = Api.browsePage(reply, 18, 3);
        assertEquals(1, page.items.size());
        assertEquals(21, page.nextOffset);
        assertTrue(page.hasMore);

        data.put("next_offset", 51).put("has_more", true);
        assertEquals(51, Api.browsePage(reply, 18, 18).nextOffset);
        assertTrue(Api.browsePage(reply, 18, 18).hasMore);
        data.put("has_more", false);
        assertFalse(Api.browsePage(reply, 18, 3).hasMore);
    }

    @Test(expected = IOException.class) public void reportsBusinessFailureInsteadOfAnEmptyPage() throws Exception {
        Api.browsePage(new JSONObject().put("code", 1020), 0, 18);
    }
}
