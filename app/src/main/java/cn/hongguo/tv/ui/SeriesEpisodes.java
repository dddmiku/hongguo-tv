package cn.hongguo.tv.ui;

import android.content.Context;
import android.content.Intent;
import cn.hongguo.tv.api.Api;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** The playable list and its display metadata, shared by detail and home resume. */
final class SeriesEpisodes {
    @FunctionalInterface
    interface Source { JSONObject episodes(String seriesId) throws IOException; }

    final JSONArray rows;
    final String title, cover, intro;

    private SeriesEpisodes(JSONArray rows, String title, String cover, String intro) {
        this.rows = rows;
        this.title = title;
        this.cover = cover;
        this.intro = intro;
    }

    static SeriesEpisodes load(Source source, Api.Series series) throws IOException {
        JSONObject response = source.episodes(series.seriesId);
        if (response == null || response.optInt("code", response.optInt("status_code", 0)) != 0) {
            throw new IOException("剧集暂时无法加载");
        }
        JSONObject data = response.optJSONObject("data");
        JSONObject node = data == null ? null : data.optJSONObject(series.seriesId);
        JSONObject metadata = node == null ? null : node.optJSONObject("video_data");
        return new SeriesEpisodes(Api.parseEpisodes(response, series.seriesId),
                metadata == null ? series.title : metadata.optString("series_title", series.title),
                metadata == null ? series.cover : metadata.optString("series_cover", series.cover),
                metadata == null ? series.intro : metadata.optString("series_intro", series.intro));
    }

    static Intent playback(Context context, Api.Series series, JSONArray rows, int episode, long position) {
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && row.optInt("num", i + 1) == episode) {
                return PlayerActivity.intent(context, series.seriesId, series.title, series.cover, rows.toString())
                        .putExtra(PlayerActivity.EXTRA_START_INDEX, i)
                        .putExtra("start_position_ms", position);
            }
        }
        return null;
    }
}
