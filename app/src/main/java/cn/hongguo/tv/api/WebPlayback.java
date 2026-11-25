package cn.hongguo.tv.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Official website playback route.
 *
 * The signed app routes (mobile video_model) currently answer HTTP 200 with a
 * zero-byte body on some devices/accounts, which made playback impossible.
 * The public website exposes the same episode through
 * POST /api/video/protected/play-info and /fallback-play-info using ordinary
 * browser headers — no SDK signature required. The returned URL is a plain MP4
 * on the official CDN, so it can be fed straight to the platform player.
 */
public final class WebPlayback {
    private static final String ORIGIN = "https://hongguoduanju.com";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; PGT-AN10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 12000;

    public static final class Result {
        public final String url;
        public final String definition;
        Result(String url, String definition) { this.url = url; this.definition = definition; }
    }

    private WebPlayback() {}

    /**
     * Returns a direct official-CDN MP4 URL.
     *
     * fallback-play-info is tried first on purpose: it serves a plain
     * progressive MP4 (moov at the head, no fragments, no CENC). The
     * play-info route serves a fragmented, DRM-encrypted DASH asset that the
     * platform MediaPlayer cannot open — that was the black screen.
     */
    public static Result resolve(String vid, String seriesId, String definition, String cookie) throws IOException {
        String firstError = null;
        for (String path : new String[]{"/api/video/protected/fallback-play-info",
                "/api/video/protected/play-info"}) {
            try {
                Result result = call(path, vid, seriesId, definition, cookie);
                if (result != null) return result;
            } catch (IOException error) {
                if (firstError == null) firstError = error.getMessage();
            }
        }
        throw new IOException(firstError == null ? "官网播放线路暂时不可用" : firstError);
    }

    private static Result call(String path, String vid, String seriesId, String definition, String cookie) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("item_id", vid);
            if (path.endsWith("/play-info")) {
                body.put("series_id", seriesId);
                body.put("definition", definition == null || definition.isEmpty() || "auto".equals(definition) ? "720p" : definition);
            }
        } catch (org.json.JSONException error) {
            throw new IOException("播放参数不正确");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(ORIGIN + path + "?aid=8662").openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Referer", ORIGIN + "/player/" + seriesId);
            connection.setRequestProperty("Origin", ORIGIN);
            if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(payload); }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = stream == null ? "" : readAll(stream);
            if (code != 200 || text.isEmpty()) throw new IOException("官网播放线路暂时不可用");
            JSONObject reply;
            try { reply = new JSONObject(text); }
            catch (org.json.JSONException error) { throw new IOException("官网播放线路暂时不可用"); }
            JSONObject base = reply.optJSONObject("base_resp");
            if (base != null && base.optInt("StatusCode", 0) != 0) throw new IOException("官网播放线路暂时不可用");
            JSONObject legacy = reply.optJSONObject("legacy_play_info");
            JSONObject play = legacy != null ? legacy : reply.optJSONObject("play_info");
            if (play == null) play = reply;
            String url = play.optString("main_url", "");
            if (url.isEmpty()) throw new IOException("官网播放线路暂时不可用");
            if (!url.startsWith("https://")) throw new IOException("官网播放线路暂时不可用");
            int actual = definitionOf(play);
            return new Result(url, actual + "p");
        } finally {
            connection.disconnect();
        }
    }

    private static int definitionOf(JSONObject play) {
        int shortSide = 0;
        int width = play.optInt("width", 0), height = play.optInt("height", 0);
        if (width > 0 && height > 0) shortSide = Math.min(width, height);
        if (shortSide <= 0) {
            String text = play.optString("definition", "");
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("([0-9]{3,4})").matcher(text);
            if (matcher.find()) shortSide = Integer.parseInt(matcher.group(1));
        }
        if (shortSide <= 360) return 360;
        if (shortSide <= 480) return 480;
        if (shortSide <= 540) return 540;
        if (shortSide <= 720) return 720;
        return 1080;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int count;
        while ((count = in.read(block)) != -1) {
            out.write(block, 0, count);
            if (out.size() > 1024 * 1024) throw new IOException("响应过大");
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
