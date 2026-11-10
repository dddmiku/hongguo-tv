package cn.hongguo.tv.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 公共内容身份的保守季匹配；搜索文字只用于发现，自动续季必须有官方关联。 */
public final class SeasonMatcher {
    private static final String NUMBER = "(?:[0-9]{1,2}|[零〇一二两三四五六七八九十]{1,3})";
    private static final Pattern SUFFIX = Pattern.compile("(?:第\\s*(" + NUMBER
            + ")|(?<![0-9零〇一二两三四五六七八九十百千])(" + NUMBER + "))\\s*(?:季|部)$");
    private static final Pattern BRACKET = Pattern.compile("\\(([^()]*)\\)$");
    private static final Pattern DIGIT_SUFFIX = Pattern.compile("(?<![0-9])([1-9][0-9]?)$");
    private static final Pattern SPECIAL = Pattern.compile(
            "番外|合集|合辑|花絮|特辑|特别篇|总集篇|剪辑|精编|重制|新版|翻拍|剧场版|真人版|动漫版|漫剧版|AI版",
            Pattern.CASE_INSENSITIVE);
    private SeasonMatcher() { }

    public static boolean validId(String id) {
        return id != null && id.matches("[0-9]{8,24}");
    }

    public static final class Title {
        public final String base;
        public final int index;
        public final boolean explicit, safe;
        private Title(String base, int index, boolean explicit, boolean safe) {
            this.base = base; this.index = index; this.explicit = explicit; this.safe = safe;
        }
    }

    /** 只移除末尾的一处明确季标记，不丢掉副标题、版本或年份。 */
    public static Title parseTitle(String value) {
        String text = normalize(value).trim();
        if (text.isEmpty() || text.length() > 200 || SPECIAL.matcher(text).find()) {
            return new Title(text, 1, false, false);
        }
        Matcher bracket = BRACKET.matcher(text);
        boolean hasBracket = bracket.find();
        String marker = hasBracket ? bracket.group(1).trim() : text;
        Matcher suffix = SUFFIX.matcher(marker);
        int index = 0, end = text.length();
        if (suffix.find() && (!hasBracket || suffix.start() == 0)) {
            index = seasonNumber(suffix.group(1) == null ? suffix.group(2) : suffix.group(1));
            end = hasBracket ? bracket.start() : suffix.start();
        } else if (hasBracket && marker.matches(NUMBER)) {
            index = seasonNumber(marker); end = bracket.start();
        } else if (!hasBracket) {
            Matcher digit = DIGIT_SUFFIX.matcher(text);
            if (digit.find()) { index = seasonNumber(digit.group(1)); end = digit.start(); }
        }
        if (end == text.length()) return new Title(text, 1, false, true);
        String base = text.substring(0, end).replaceFirst("[\\s\\-—_:：·]+$", "");
        if (index == 0 || base.isEmpty()) return new Title(text, 1, false, false);
        return new Title(base, index, true, true);
    }

    /** 官方长标题的冒号前主名可补到被全称搜索挤掉的其它季。 */
    public static String discoveryQuery(Title title) {
        if (!title.safe || title.base.isEmpty() || title.base.length() > 80) return "";
        int colon = title.base.indexOf(':');
        if (colon >= 0 && !title.base.substring(colon + 1).trim().isEmpty()) {
            String main = title.base.substring(0, colon).trim();
            if (main.length() >= 2 && main.length() <= 80) return main;
        }
        return title.base;
    }

    private static int seasonNumber(String text) {
        if (text == null) return 0;
        text = text.trim(); int value;
        if (text.matches("[0-9]{1,2}")) value = Integer.parseInt(text);
        else if (text.contains("十")) {
            if (text.indexOf('十') != text.lastIndexOf('十')) return 0;
            int split = text.indexOf('十');
            String left = text.substring(0, split), right = text.substring(split + 1);
            if (left.length() > 1 || right.length() > 1) return 0;
            int tens = left.isEmpty() ? 1 : chinese(left.charAt(0));
            int units = right.isEmpty() ? 0 : chinese(right.charAt(0));
            if (tens < 0 || units < 0) return 0;
            value = tens * 10 + units;
        } else if (text.length() == 1) value = chinese(text.charAt(0));
        else return 0;
        return value >= 1 && value <= 99 ? value : 0;
    }

    private static int chinese(char value) {
        if (value == '〇') return 0;
        if (value == '两') return 2;
        return "零一二三四五六七八九".indexOf(value);
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC);
    }
    private static String key(String text) {
        return normalize(text).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
    private static String text(JSONObject raw, String... fields) {
        for (String field : fields) {
            if (!raw.isNull(field)) {
                String value = raw.optString(field, "");
                if (!value.isEmpty()) return value;
            }
        }
        return "";
    }
    private static String bounded(String text, int limit) {
        return text.length() > limit ? text.substring(0, limit) : text;
    }
    private static String kind(JSONObject raw) {
        for (String field : new String[]{"content_type", "genre", "contentType"}) {
            if (raw.isNull(field)) continue;
            String value = key(raw.optString(field, ""));
            if (!value.isEmpty() && !"0".equals(value)) return value;
        }
        return "";
    }
    private static int positive(Object value) {
        if (value == null || value instanceof Boolean || value == JSONObject.NULL) return 0;
        try {
            long number = Long.parseLong(String.valueOf(value));
            return number > 0 && number <= Integer.MAX_VALUE ? (int) number : 0;
        } catch (NumberFormatException ignored) { return 0; }
    }

    /** 只保留结构身份字段和海报元数据，不保留上游 body、取流地址或账号数据。 */
    public static final class Metadata {
        public final Api.Series series;
        public final String contentType, aiUsageType, relatedAlbumId;
        public final int seasonIndex, firstEpisode;
        public final boolean multiSeason;
        public final List<String> actors;

        private Metadata(Api.Series series, String contentType, String aiUsageType,
                         String relatedAlbumId, int seasonIndex, int firstEpisode,
                         boolean multiSeason, List<String> actors) {
            this.series = copySeries(series); this.contentType = contentType;
            this.aiUsageType = aiUsageType; this.relatedAlbumId = relatedAlbumId;
            this.seasonIndex = seasonIndex; this.firstEpisode = firstEpisode;
            this.multiSeason = multiSeason;
            this.actors = Collections.unmodifiableList(new ArrayList<>(actors));
        }

        public boolean official() {
            return multiSeason && validId(relatedAlbumId) && seasonIndex > 0;
        }

        public static Metadata from(String sid, JSONObject raw) throws IOException {
            return parse(sid, raw, true);
        }

        /** 批量详情可能只有身份字段；零集数只用于本次匹配，不能进入最终概览。 */
        public static Metadata fromIdentity(String sid, JSONObject raw) throws IOException {
            return parse(sid, raw, false);
        }

        private static Metadata parse(String sid, JSONObject raw, boolean requireTotal) throws IOException {
            if (!validId(sid) || raw == null || raw.length() == 0) throw unavailable();
            String title = bounded(text(raw, "series_title", "title"), 200);
            if (title.trim().isEmpty()) throw unavailable();
            String declaredId = text(raw, "series_id", "book_id");
            if (!declaredId.isEmpty() && !sid.equals(declaredId)) throw unavailable();
            Api.Series series = new Api.Series();
            series.seriesId = sid; series.title = title;
            String cover = text(raw, "series_cover", "cover", "thumb_url");
            series.intro = bounded(text(raw, "series_intro", "intro", "video_desc"), 2000);
            Set<Integer> indexes = new LinkedHashSet<>();
            int first = 0;
            for (String field : new String[]{"video_list", "video_list_unique"}) {
                JSONArray episodes = raw.optJSONArray(field);
                if (episodes == null) continue;
                for (int i = 0; i < episodes.length(); i++) {
                    JSONObject episode = episodes.optJSONObject(i);
                    if (episode == null) continue;
                    int index = positive(episode.opt("vid_index"));
                    if (index == 0) continue;
                    indexes.add(index);
                    if (!text(episode, "vid", "video_id").trim().isEmpty() && (first == 0 || index < first)) first = index;
                    if (cover.isEmpty()) cover = text(episode, "episode_cover", "cover");
                }
            }
            series.cover = cover.startsWith("https://") ? bounded(cover, 2048) : "";
            int total = positive(raw.opt("total_episode"));
            if (requireTotal && total == 0 && indexes.isEmpty()) throw unavailable();
            series.episodeCount = total > 0 ? total : indexes.size();
            List<String> actors = new ArrayList<>();
            JSONArray cast = raw.optJSONArray("celebrities");
            if (cast == null || cast.length() == 0) cast = raw.optJSONArray("actors");
            if (cast != null) for (int i = 0; i < cast.length() && i < 20; i++) {
                Object actor = cast.opt(i);
                String name = actor instanceof JSONObject ? text((JSONObject) actor, "nickname", "演员")
                        : actor instanceof String ? (String) actor : "";
                name = key(bounded(name, 100));
                if (!name.isEmpty() && !actors.contains(name)) actors.add(name);
            }
            Object enabled = raw.opt("is_multi_season");
            // 当前内容接口省略开关，仍返回官方 album 与季号；显式关闭保持拒绝。
            boolean multi = !raw.has("is_multi_season") || Boolean.TRUE.equals(enabled) || Integer.valueOf(1).equals(enabled)
                    || Long.valueOf(1).equals(enabled) || "1".equals(enabled) || "true".equals(enabled);
            return new Metadata(series, kind(raw),
                    text(raw, "ai_usage_type"), text(raw, "related_album_id"),
                    positive(raw.opt("season_index")), first, multi, actors);
        }
    }

    public static int number(Metadata current) {
        return current.official() ? current.seasonIndex : parseTitle(current.series.title).index;
    }

    /** 返回候选季号；零表示不能证明为同作品的一季。 */
    public static int matchNumber(Metadata current, Metadata candidate) {
        Title left = parseTitle(current.series.title), right = parseTitle(candidate.series.title);
        if (!left.safe || !right.safe || !sameKind(current, candidate, !current.official())) return 0;
        if (current.official()) {
            return candidate.official() && current.relatedAlbumId.equals(candidate.relatedAlbumId)
                    ? candidate.seasonIndex : 0;
        }
        if (!key(left.base).equals(key(right.base)) || !sameCast(current, candidate)) return 0;
        if (candidate.seasonIndex > 0 && candidate.seasonIndex != right.index) return 0;
        return right.index;
    }

    public static boolean canAutoAdvance(Metadata current, Metadata candidate) {
        return current.official() && candidate.official() && candidate.firstEpisode > 0
                && matchNumber(current, candidate) == current.seasonIndex + 1;
    }

    /** 批次丢掉官方身份时，只补查精确标题家族；补查结果仍必须通过官方匹配。 */
    public static boolean needsIdentityDetail(Metadata current, Metadata candidate) {
        if (!current.official() || candidate.official() || !candidate.multiSeason
                || !sameKind(current, candidate, false)) return false;
        if (validId(candidate.relatedAlbumId)) {
            return current.relatedAlbumId.equals(candidate.relatedAlbumId) && candidate.seasonIndex == 0;
        }
        Title left = parseTitle(current.series.title), right = parseTitle(candidate.series.title);
        return left.safe && right.safe && key(left.base).equals(key(right.base));
    }

    private static boolean sameKind(Metadata current, Metadata candidate, boolean known) {
        String left = current.contentType, right = candidate.contentType;
        if (known && (left.isEmpty() || "0".equals(left) || right.isEmpty() || "0".equals(right))) return false;
        if (!left.isEmpty() && !right.isEmpty() && !left.equals(right)) return false;
        return current.aiUsageType.isEmpty() || candidate.aiUsageType.isEmpty()
                || current.aiUsageType.equals(candidate.aiUsageType);
    }

    private static boolean sameCast(Metadata current, Metadata candidate) {
        if (current.actors.isEmpty() && candidate.actors.isEmpty()) {
            return "comic_series".equals(current.contentType) || "ai_series".equals(current.contentType)
                    || "2".equals(current.contentType) || "3".equals(current.contentType);
        }
        Set<String> left = new LinkedHashSet<>(current.actors.subList(0, Math.min(2, current.actors.size())));
        Set<String> right = new LinkedHashSet<>(candidate.actors.subList(0, Math.min(2, candidate.actors.size())));
        return !left.isEmpty() && left.equals(right);
    }

    public static Api.Series copySeries(Api.Series source) {
        Api.Series target = new Api.Series();
        target.seriesId = source.seriesId; target.title = source.title; target.cover = source.cover;
        target.intro = source.intro; target.episodeCount = source.episodeCount; target.rank = source.rank;
        target.hot = source.hot; target.category = source.category; target.premiere = source.premiere;
        return target;
    }
    public static IOException unavailable() { return new IOException("剧集季信息暂时不可用，请稍后重试"); }
}
