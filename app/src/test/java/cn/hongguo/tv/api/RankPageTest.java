package cn.hongguo.tv.api;

import java.io.IOException;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RankPageTest {
    @Test public void seriesUsesTheConfirmedOfficialRecommendationAndNewSeasonBoards() {
        Map<String, String> recommend = Api.rankQuery("series", "recommend", 0, "test-session");
        assertEquals("7470092475068071998", recommend.get("cell_id"));
        assertEquals("26", recommend.get("tab_type"));
        assertEquals("series_album", recommend.get("selected_items"));
        assertEquals("series_album_hot_sc", recommend.get("sub_selected_items"));
        assertFalse(recommend.containsKey("offset"));
        Map<String, String> fresh = Api.rankQuery("series", "new", 10, "test-session");
        assertEquals("series_album_new_rank", fresh.get("sub_selected_items"));
        assertEquals("10", fresh.get("offset"));
    }

    @Test public void existingComicBoardsKeepTheirOriginalParameters() {
        assertEquals("comic_series_hot_rank", Api.rankQuery("comic", "recommend", 0, "s").get("sub_selected_items"));
        assertEquals("comic_series_hot_play", Api.rankQuery("comic", "hot", 0, "s").get("sub_selected_items"));
        assertEquals("comic_series_new_rank", Api.rankQuery("comic", "new", 0, "s").get("sub_selected_items"));
        assertEquals("comic_series_rank", Api.rankQuery("comic", "new", 0, "s").get("selected_items"));
    }

    @Test(expected = IllegalArgumentException.class) public void seriesHasNoInventedHotPlayBoard() {
        Api.rankQuery("series", "hot", 0, "s");
    }

    @Test public void albumOpensTheOfficialFirstSeasonAndKeepsSeasonAndRecommendationLabels() throws Exception {
        JSONObject video = video().put("jump_schema", "dragon8662://videoDetail?video_series_id=7691938290202577944")
                .put("sub_title_list", new JSONArray().put(new JSONObject().put("content", "漫剧"))
                        .put(new JSONObject().put("content", "更新至第3季")))
                .put("rec_text_item", new JSONObject().put("RecommendText", "994万推荐"));
        Api.RankPage page = Api.rankPage(reply(new JSONArray().put(cell(video)), true, 10), "series", 0);
        assertEquals(1, page.items.size());
        Api.Series row = page.items.get(0);
        assertEquals("7691938290202577944", row.seriesId);
        assertEquals(0, row.episodeCount);
        assertEquals("漫剧 · 更新至第3季", row.category);
        assertEquals("994万推荐", row.hot);
        assertTrue(page.hasMore);
        assertEquals(10, page.nextOffset);
    }

    @Test public void invalidRoutesCannotReplaceThePlayableSeriesId() throws Exception {
        String[] routes = {"https://videoDetail?video_series_id=123", "dragon8662://other?video_series_id=123",
                "dragon8662://videoDetail?video_series_id=wrong", "dragon8662://videoDetail?other_id=123"};
        for (String route : routes) {
            Api.Series row = Api.rankPage(reply(new JSONArray().put(cell(video().put("jump_schema", route))), false, 1), "series", 0).items.get(0);
            assertEquals("7708355462780947518", row.seriesId);
            assertEquals(207, row.episodeCount);
        }
    }

    @Test public void comicDoesNotApplyTheSeriesAlbumEntryRoute() throws Exception {
        JSONObject video = video().put("jump_schema", "dragon8662://videoDetail?video_series_id=7691938290202577944");
        Api.Series row = Api.rankPage(reply(new JSONArray().put(cell(video)), false, 1), "comic", 0).items.get(0);
        assertEquals("7708355462780947518", row.seriesId);
        assertEquals(207, row.episodeCount);
    }

    @Test public void rawRowsAdvanceTheCursorEvenWhenDeduplicatedOrInvalid() throws Exception {
        JSONArray cells = new JSONArray().put(cell(video())).put(new JSONObject()).put(cell(video()));
        JSONObject response = reply(cells, true, 0);
        response.getJSONObject("data").getJSONObject("cell_view").remove("next_offset");
        Api.RankPage page = Api.rankPage(response, "series", 20);
        assertEquals(1, page.items.size());
        assertEquals(23, page.nextOffset);
        assertTrue(page.hasMore);
        response.getJSONObject("data").getJSONObject("cell_view").put("next_offset", 20);
        assertFalse(Api.rankPage(response, "series", 20).hasMore);
    }

    @Test public void validEmptyBoardIsDifferentFromAnError() throws Exception {
        Api.RankPage page = Api.rankPage(reply(new JSONArray(), false, 0), "series", 0);
        assertTrue(page.items.isEmpty());
        assertFalse(page.hasMore);
    }

    @Test(expected = IOException.class) public void businessFailureIsReportedForRetry() throws Exception {
        Api.rankPage(new JSONObject().put("code", 1020), "series", 0);
    }

    @Test(expected = IOException.class) public void malformedSuccessIsReportedForRetry() throws Exception {
        Api.rankPage(new JSONObject().put("code", 0).put("data", new JSONObject()), "series", 0);
    }

    private JSONObject video() throws Exception {
        return new JSONObject().put("series_id", "7708355462780947518")
                .put("title", "受控系列剧").put("episode_cnt", 207);
    }
    private JSONObject cell(JSONObject video) throws Exception {
        return new JSONObject().put("video_data", new JSONArray().put(video));
    }
    private JSONObject reply(JSONArray cells, boolean more, int next) throws Exception {
        return new JSONObject().put("code", 0).put("data", new JSONObject().put("cell_view",
                new JSONObject().put("cell_data", cells).put("has_more", more).put("next_offset", next)));
    }
}
