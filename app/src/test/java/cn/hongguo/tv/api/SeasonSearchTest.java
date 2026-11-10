package cn.hongguo.tv.api;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class SeasonSearchTest {
    private static JSONObject series(String id, String title) throws Exception {
        return new JSONObject().put("series_id", id).put("title", title);
    }
    private static JSONObject page(JSONArray rows, boolean more, String offset, String passback, String searchId) throws Exception {
        JSONObject tab = new JSONObject().put("data", rows).put("has_more", more);
        if (offset != null) tab.put("next_offset", offset);
        if (passback != null) tab.put("passback", passback);
        if (searchId != null) tab.put("search_id", searchId);
        return new JSONObject().put("code", 0).put("search_tabs", new JSONArray().put(tab));
    }
    @Test public void excludesNovelIdsAndAcceptsBothVideoDataShapesAndNestedDetails() throws Exception {
        JSONArray rows = new JSONArray().put(new JSONObject().put("book_id", "99999999").put("title", "普通小说"))
                .put(new JSONObject().put("video_data", new JSONArray().put(series("12345678", "春风"))))
                .put(new JSONObject().put("video_data", series("23456789", "春风第二季")))
                .put(new JSONObject().put("book_id", "34567890").put("video_detail", new JSONObject().put("episode_cnt", 31).put("series_title", "春风第三季")));
        JSONObject response = page(rows, false, null, null, null);
        List<Api.Series> result = SeasonSearch.find("春风", query -> response);
        assertEquals(3, result.size()); assertEquals("12345678", result.get(0).seriesId);
        assertEquals("23456789", result.get(1).seriesId); assertEquals("34567890", result.get(2).seriesId);
        for (Api.Series item : result) { assertNotEquals("99999999", item.seriesId); assertEquals(0, item.episodeCount); }
    }
    @Test public void paginationCarriesEveryOfficialCursorAndDisablesCorrection() throws Exception {
        JSONObject first = page(new JSONArray().put(series("12345678", "春风")), true, "9", "cursor-a", "search-a");
        JSONObject second = page(new JSONArray().put(series("12345678", "重复条目")).put(series("23456789", "春风第二季")), false, null, null, null);
        AtomicInteger calls = new AtomicInteger(); List<Map<String, String>> requests = new ArrayList<>();
        List<Api.Series> result = SeasonSearch.find("春风", query -> { requests.add(query); return calls.getAndIncrement() == 0 ? first : second; });
        assertEquals(2, calls.get()); assertEquals(2, result.size()); assertEquals("春风", result.get(0).title);
        assertEquals("false", requests.get(0).get("use_correct")); assertEquals("0", requests.get(0).get("offset"));
        assertEquals("9", requests.get(1).get("offset")); assertEquals("cursor-a", requests.get(1).get("passback")); assertEquals("search-a", requests.get(1).get("search_id"));
    }
    @Test public void missingOrNonadvancingPaginationIsFailure() throws Exception {
        for (String offset : new String[]{null, "0"}) {
            JSONObject stuck = page(new JSONArray().put(series("12345678", "春风")), true, offset, null, null);
            try { SeasonSearch.find("春风", query -> stuck); fail(); }
            catch (IOException error) { assertEquals("剧集季信息暂时不可用，请稍后重试", error.getMessage()); }
        }
    }
    @Test public void malformedAndDeniedSearchResponsesAreFailures() throws Exception {
        for (JSONObject response : new JSONObject[]{null, new JSONObject(), new JSONObject().put("code", 1),
                new JSONObject().put("search_tabs", new JSONArray().put(new JSONObject().put("data", "invalid")))}) {
            try { SeasonSearch.find("春风", query -> response); fail(); } catch (IOException expected) { }
        }
    }
    @Test public void searchStopsAtFourPagesAndFortyUniqueCandidates() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<Api.Series> result = SeasonSearch.find("春风", query -> {
            int number = calls.incrementAndGet();
            try { return page(new JSONArray().put(series(String.valueOf(12345677 + number), "季" + number)), true, String.valueOf(number), null, null); }
            catch (Exception error) { throw new IOException("fixed fixture"); }
        });
        assertEquals(4, calls.get()); assertEquals(4, result.size());
        JSONArray many = new JSONArray(); for (int i = 0; i < 60; i++) many.put(series(String.valueOf(12345678 + i), "作品" + i));
        JSONObject response = page(many, true, "1", null, null); calls.set(0);
        result = SeasonSearch.find("春风", query -> { calls.incrementAndGet(); return response; });
        assertEquals(40, result.size()); assertEquals(1, calls.get());
    }
    @Test public void networkFailureGetsFixedDiagnosticAndNextCallCanRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger(); JSONObject response = page(new JSONArray().put(series("12345678", "春风")), false, null, null, null);
        SeasonSearch.Source source = query -> { if (calls.incrementAndGet() == 1) throw new IOException("upstream response fixture"); return response; };
        try { SeasonSearch.find("春风", source); fail(); } catch (IOException error) { assertFalse(error.getMessage().contains("upstream")); }
        assertEquals(1, SeasonSearch.find("春风", source).size()); assertEquals(2, calls.get());
    }
}
