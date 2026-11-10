package cn.hongguo.tv.api;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class SeasonMatcherTest {
    private static final String CURRENT = "12345678", NEXT = "23456789", ALBUM = "987654321";
    private static JSONObject raw(String title, int index, String album) throws Exception {
        return new JSONObject().put("series_title", title).put("season_index", index)
                .put("related_album_id", album).put("is_multi_season", !album.isEmpty())
                .put("content_type", 1).put("total_episode", 12)
                .put("celebrities", new JSONArray().put("甲").put("乙"))
                .put("video_list", new JSONArray().put(new JSONObject().put("vid_index", 1).put("vid", "video-a")));
    }
    private static SeasonMatcher.Metadata meta(String id, String title, int index, String album) throws Exception {
        return SeasonMatcher.Metadata.from(id, raw(title, index, album));
    }

    @Test public void parsesChineseArabicAndBracketedSeasonMarkers() {
        for (String value : new String[]{"春风第二季", "春风第2季", "春风（第二季）", "春风(2)", "春风2", "春风 二季"}) {
            SeasonMatcher.Title title = SeasonMatcher.parseTitle(value);
            assertTrue(value, title.safe); assertTrue(value, title.explicit);
            assertEquals(value, "春风", title.base); assertEquals(value, 2, title.index);
        }
        assertEquals(22, SeasonMatcher.parseTitle("春风第二十二季").index);
    }
    @Test public void retainsYearsUnsupportedNumbersAndVersionQualifiers() {
        for (String value : new String[]{"春风2024", "春风2024季", "春风第一百季", "春风（张三版）", "春风之二"}) {
            SeasonMatcher.Title title = SeasonMatcher.parseTitle(value);
            assertFalse(value, title.explicit); assertEquals(1, title.index);
        }
        assertEquals("春风(张三版)", SeasonMatcher.parseTitle("春风（张三版）").base);
    }
    @Test public void excludesSpecialRemakesAndCompilations() {
        for (String value : new String[]{"番外", "合集", "新版", "重制", "翻拍", "花絮", "特别篇", "真人版", "AI版"}) {
            assertFalse(SeasonMatcher.parseTitle("春风" + value + "第二季").safe);
        }
    }
    @Test public void mainTitleDiscoveryUsesOfficialBaseInsteadOfLongFullTitle() {
        assertEquals("觉醒伪神", SeasonMatcher.discoveryQuery(SeasonMatcher.parseTitle("觉醒伪神：神明弃我我自为神8")));
        assertEquals("春风", SeasonMatcher.discoveryQuery(SeasonMatcher.parseTitle("春风第二季")));
        assertEquals("神:另一个标题", SeasonMatcher.discoveryQuery(SeasonMatcher.parseTitle("神：另一个标题")));
    }
    @Test public void officialAlbumAllowsRenamedSeasonAndChangedCast() throws Exception {
        SeasonMatcher.Metadata current = meta(CURRENT, "春风", 1, ALBUM);
        JSONObject candidate = raw("夏雨", 2, ALBUM).put("celebrities", new JSONArray().put("丙"));
        SeasonMatcher.Metadata next = SeasonMatcher.Metadata.from(NEXT, candidate);
        assertEquals(2, SeasonMatcher.matchNumber(current, next)); assertTrue(SeasonMatcher.canAutoAdvance(current, next));
        assertEquals(0, SeasonMatcher.matchNumber(current, meta(NEXT, "春风第二季", 2, "123456789")));
    }
    @Test public void automaticAdvanceRequiresOfficialFlagsAndExactlyFollowingIndex() throws Exception {
        SeasonMatcher.Metadata current = meta(CURRENT, "春风", 1, ALBUM);
        assertFalse(SeasonMatcher.canAutoAdvance(current, meta(NEXT, "春风第三季", 3, ALBUM)));
        for (Object flag : new Object[]{false, 0, "0", "TRUE", JSONObject.NULL}) {
            SeasonMatcher.Metadata next = SeasonMatcher.Metadata.from(NEXT, raw("春风第二季", 2, ALBUM).put("is_multi_season", flag));
            assertFalse(next.official()); assertFalse(SeasonMatcher.canAutoAdvance(current, next));
        }
        for (Object flag : new Object[]{true, 1, 1L, "1", "true"}) {
            assertTrue(SeasonMatcher.Metadata.from(NEXT, raw("春风第二季", 2, ALBUM).put("is_multi_season", flag)).official());
        }
    }
    @Test public void titleFallbackIsManualAndNeedsPositiveCastAndTypeEvidence() throws Exception {
        SeasonMatcher.Metadata current = meta(CURRENT, "春风", 0, "");
        SeasonMatcher.Metadata second = meta(NEXT, "春风第二季", 0, "");
        assertEquals(2, SeasonMatcher.matchNumber(current, second)); assertFalse(SeasonMatcher.canAutoAdvance(current, second));
        assertEquals(0, SeasonMatcher.matchNumber(current, meta(NEXT, "春风十里第二季", 0, "")));
        assertEquals(0, SeasonMatcher.matchNumber(current, SeasonMatcher.Metadata.from(NEXT,
                raw("春风第二季", 0, "").put("celebrities", new JSONArray().put("丙")))));
        JSONObject missingKind = raw("春风第二季", 0, ""); missingKind.remove("content_type");
        assertEquals(0, SeasonMatcher.matchNumber(current, SeasonMatcher.Metadata.from(NEXT, missingKind)));
    }
    @Test public void officialRelationStillRejectsOtherKindsAiAdaptationsAndSpecials() throws Exception {
        SeasonMatcher.Metadata current = SeasonMatcher.Metadata.from(CURRENT, raw("春风", 1, ALBUM).put("ai_usage_type", 0));
        for (JSONObject candidate : new JSONObject[]{raw("春风番外第二季", 2, ALBUM),
                raw("春风第二季", 2, ALBUM).put("content_type", 2), raw("春风第二季", 2, ALBUM).put("ai_usage_type", 1)}) {
            assertEquals(0, SeasonMatcher.matchNumber(current, SeasonMatcher.Metadata.from(NEXT, candidate)));
        }
    }
    @Test public void zeroContentTypeFallsBackToKnownGenre() throws Exception {
        JSONObject current = raw("春风", 1, ALBUM).put("content_type", 0).put("genre", "comic_series");
        JSONObject candidate = raw("夏雨", 2, ALBUM).put("genre", "comic_series"); candidate.remove("content_type");
        assertTrue(SeasonMatcher.canAutoAdvance(SeasonMatcher.Metadata.from(CURRENT, current), SeasonMatcher.Metadata.from(NEXT, candidate)));
    }
    @Test public void actualDetailSchemaWithoutMultiSeasonFlagKeepsOfficialAlbumRelation() throws Exception {
        JSONObject current = raw("家里家外", 1, ALBUM).put("series_id", Long.parseLong(CURRENT));
        JSONObject candidate = raw("家里家外第二季", 2, ALBUM).put("series_id", Long.parseLong(NEXT));
        current.remove("is_multi_season"); candidate.remove("is_multi_season");
        current.remove("total_episode"); candidate.remove("total_episode");
        JSONArray first = new JSONArray(), second = new JSONArray();
        for (int i = 1; i <= 79; i++) first.put(new JSONObject().put("vid_index", i).put("vid", "fixture-" + i));
        for (int i = 1; i <= 111; i++) second.put(new JSONObject().put("vid_index", i).put("vid", "fixture-" + i));
        current.put("video_list", first); candidate.put("video_list", second);
        SeasonMatcher.Metadata left = SeasonMatcher.Metadata.from(CURRENT, current), right = SeasonMatcher.Metadata.from(NEXT, candidate);
        assertEquals(79, left.series.episodeCount); assertEquals(111, right.series.episodeCount);
        assertTrue(left.official()); assertTrue(right.official()); assertTrue(SeasonMatcher.canAutoAdvance(left, right));
        assertFalse(SeasonMatcher.Metadata.from(NEXT, candidate.put("is_multi_season", false)).official());
    }
    @Test public void totalEpisodeWinsOverSearchCountAndPartialPlayableRows() throws Exception {
        SeasonMatcher.Metadata current = SeasonMatcher.Metadata.from(CURRENT, raw("春风", 1, ALBUM)
                .put("total_episode", "127").put("episode_cnt", 3));
        assertEquals(127, current.series.episodeCount); assertEquals(1, current.firstEpisode);
    }
    @Test public void uniquePositiveOfficialIndexesSupplyMissingTotal() throws Exception {
        JSONArray episodes = new JSONArray().put(new JSONObject().put("vid_index", 3).put("vid", "video-c"))
                .put(new JSONObject().put("vid_index", 3).put("vid", "video-c"))
                .put(new JSONObject().put("vid_index", 1)).put(new JSONObject().put("vid_index", 0))
                .put(new JSONObject().put("vid_index", -1));
        JSONObject data = raw("春风", 1, ALBUM); data.remove("total_episode"); data.remove("video_list");
        data.put("video_list_unique", episodes).put("episode_cnt", 999);
        SeasonMatcher.Metadata current = SeasonMatcher.Metadata.from(CURRENT, data);
        assertEquals(2, current.series.episodeCount); assertEquals(3, current.firstEpisode);
    }
    @Test public void nonplayableMetadataIsNotAutomaticNext() throws Exception {
        JSONObject data = raw("春风第二季", 2, ALBUM).put("video_list",
                new JSONArray().put(new JSONObject().put("vid_index", 1)));
        assertFalse(SeasonMatcher.canAutoAdvance(meta(CURRENT, "春风", 1, ALBUM), SeasonMatcher.Metadata.from(NEXT, data)));
    }
    @Test public void missingOrMismatchedDetailsFailWithFixedDiagnostic() throws Exception {
        for (JSONObject data : new JSONObject[]{null, new JSONObject(), raw("春风", 1, ALBUM).put("series_id", NEXT)}) {
            try { SeasonMatcher.Metadata.from(CURRENT, data); fail(); }
            catch (IOException error) { assertEquals("剧集季信息暂时不可用，请稍后重试", error.getMessage()); }
        }
    }
    @Test public void missingOfficialTotalsCannotFallBackToSearchCardCounts() throws Exception {
        JSONObject data = raw("春风", 1, ALBUM).put("episode_cnt", 999);
        data.remove("total_episode"); data.remove("video_list");
        try { SeasonMatcher.Metadata.from(CURRENT, data); fail(); }
        catch (IOException error) { assertEquals("剧集季信息暂时不可用，请稍后重试", error.getMessage()); }
    }
}
