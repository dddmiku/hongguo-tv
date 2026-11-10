package cn.hongguo.tv.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 搜索分页与候选解析的只读边界，便于在无网络 JVM 中验证官方数据形状。 */
public final class SeasonSearch {
    public interface Source { JSONObject load(Map<String, String> query) throws IOException; }
    private SeasonSearch() { }

    public static List<Api.Series> find(String query, Source source) throws IOException {
        if (query == null || query.trim().isEmpty() || query.length() > 80 || source == null) throw SeasonMatcher.unavailable();
        List<Api.Series> out = new ArrayList<>(); Set<String> seen = new HashSet<>();
        String offset = "0", passback = "", searchId = "";
        for (int page = 0; page < 4; page++) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("query", query); q.put("tab_name", "feed"); q.put("search_source", "1");
            q.put("offset", offset); q.put("count", "0"); q.put("use_correct", "false");
            if (!passback.isEmpty()) q.put("passback", passback);
            if (!searchId.isEmpty()) q.put("search_id", searchId);
            JSONObject raw;
            try { raw = source.load(q); }
            catch (IOException error) { throw SeasonMatcher.unavailable(); }
            if (raw == null || raw.optInt("code", 0) != 0) throw SeasonMatcher.unavailable();
            JSONArray tabs = raw.optJSONArray("search_tabs");
            if (tabs == null) throw SeasonMatcher.unavailable();
            if (tabs.length() == 0) break;
            JSONObject tab = tabs.optJSONObject(0);
            JSONArray rows = tab == null ? null : tab.optJSONArray("data");
            if (rows == null) throw SeasonMatcher.unavailable();
            for (int i = 0; i < rows.length() && out.size() < 40; i++) {
                JSONObject cell = rows.optJSONObject(i);
                if (cell == null) continue;
                Object videos = cell.opt("video_data");
                if (videos instanceof JSONArray) {
                    JSONArray array = (JSONArray) videos;
                    for (int k = 0; k < array.length() && out.size() < 40; k++) add(out, seen, array.optJSONObject(k), cell);
                } else if (videos instanceof JSONObject) add(out, seen, (JSONObject) videos, cell);
                if (out.size() < 40) add(out, seen, cell, cell);
            }
            if (out.size() >= 40 || !tab.optBoolean("has_more", false) || rows.length() == 0) break;
            String nextOffset = tab.isNull("next_offset") ? "" : tab.optString("next_offset", "");
            String nextPassback = tab.optString("passback", passback);
            if (nextOffset.isEmpty() || (offset.equals(nextOffset) && passback.equals(nextPassback))) throw SeasonMatcher.unavailable();
            offset = nextOffset; passback = nextPassback; searchId = tab.optString("search_id", searchId);
        }
        return out;
    }

    private static void add(List<Api.Series> out, Set<String> seen, JSONObject video, JSONObject cell) {
        if (video == null) return;
        JSONObject detail = video.optJSONObject("video_detail");
        if (detail == null) detail = cell.optJSONObject("video_detail");
        // 综合 tab 也有小说的 numeric book_id，集数仅用来确认剧集形状。
        boolean seriesShape = !video.optString("series_id", "").isEmpty()
                || (detail != null && !detail.optString("series_id", "").isEmpty())
                || video.optInt("episode_cnt", 0) > 0 || (detail != null && detail.optInt("episode_cnt", 0) > 0);
        if (!seriesShape) return;
        String sid = video.optString("series_id", video.optString("book_id", ""));
        if (sid.isEmpty() && detail != null) sid = detail.optString("series_id", detail.optString("book_id", ""));
        if (sid.isEmpty()) sid = cell.optString("book_id", cell.optString("search_result_id", ""));
        String title = video.optString("title", video.optString("series_title", ""));
        if (title.isEmpty() && detail != null) title = detail.optString("series_title", detail.optString("title", ""));
        if (title.isEmpty()) {
            JSONObject highlight = cell.optJSONObject("search_high_light");
            JSONObject titleNode = highlight == null ? null : highlight.optJSONObject("title");
            if (titleNode != null) title = titleNode.optString("text", "");
        }
        title = title.replaceAll("<[^>]+>", "").trim();
        if (!SeasonMatcher.validId(sid) || title.isEmpty() || !seen.add(sid)) return;
        Api.Series candidate = new Api.Series(); candidate.seriesId = sid;
        candidate.title = title.length() > 200 ? title.substring(0, 200) : title;
        out.add(candidate);
    }
}
