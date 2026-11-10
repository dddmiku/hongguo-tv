package cn.hongguo.tv.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.hongguo.tv.api.Api;
import cn.hongguo.tv.api.SeasonMatcher;
import cn.hongguo.tv.api.SeasonMatcher.Metadata;

/** 只读公共季概览；后台线程调用，成功缓存有界，同剧并发共享一次读取。 */
public final class SeasonRepository {
    public interface Source {
        Map<String, Metadata> details(List<String> ids) throws IOException;
        List<Api.Series> search(String query) throws IOException;
    }
    public interface Clock { long nowMillis(); }
    private static final Clock MONOTONIC = () -> System.nanoTime() / 1000000L;
    private static final State SHARED = new State(128, 600000L, 120000L);
    private final Source source;
    private final Clock clock;
    private final State state;

    public SeasonRepository(Api api) {
        if (api == null) throw new IllegalArgumentException("内容接口不能为空");
        this.source = new Source() {
            @Override public Map<String, Metadata> details(List<String> ids) throws IOException {
                return api.seasonDetails(ids);
            }
            @Override public List<Api.Series> search(String query) throws IOException {
                return api.seasonSearch(query);
            }
        };
        this.clock = MONOTONIC; this.state = SHARED;
    }

    /** 注入只读边界供离线合约测试；各实例有自己的缓存。 */
    public SeasonRepository(Source source, int capacity, long ttlMillis, long emptyTtlMillis, Clock clock) {
        if (source == null || clock == null) throw new IllegalArgumentException("季信息读取边界不能为空");
        this.source = source; this.clock = clock;
        this.state = new State(Math.max(1, Math.min(256, capacity)),
                Math.max(1, Math.min(3600000L, ttlMillis)), Math.max(1, emptyTtlMillis));
    }

    public static final class Season {
        public final int number, firstEpisode;
        public final Api.Series series;
        private Season(int number, Api.Series series, int firstEpisode) {
            this.number = number; this.series = SeasonMatcher.copySeries(series);
            this.firstEpisode = firstEpisode;
        }
        private Season copy() { return new Season(number, series, firstEpisode); }
    }

    public static final class Overview {
        public final String seriesId, status, source;
        public final List<Season> items;
        public final Season current, next;
        public final boolean ambiguous;
        private Overview(String seriesId, String status, String source, List<Season> items,
                         Season current, Season next, boolean ambiguous) {
            this.seriesId = seriesId; this.status = status; this.source = source;
            List<Season> snapshot = new ArrayList<>();
            Season currentCopy = null, nextCopy = null;
            for (Season item : items) {
                Season copy = item.copy(); snapshot.add(copy);
                if (current != null && copy.series.seriesId.equals(current.series.seriesId)) currentCopy = copy;
                if (next != null && copy.series.seriesId.equals(next.series.seriesId)) nextCopy = copy;
            }
            this.items = Collections.unmodifiableList(snapshot);
            this.current = currentCopy; this.next = nextCopy; this.ambiguous = ambiguous;
        }
        private Overview copy() { return new Overview(seriesId, status, source, items, current, next, ambiguous); }
    }

    public Overview overview(String seriesId) throws IOException { return overview(seriesId, false); }

    public Overview overview(String seriesId, boolean refresh) throws IOException {
        if (!SeasonMatcher.validId(seriesId)) throw new IOException("剧集标识不正确");
        Flight flight;
        synchronized (state) {
            trimExpired();
            Entry cached = state.values.get(seriesId);
            if (!refresh && cached != null) return cached.overview.copy();
            flight = state.running.get(seriesId);
            if (flight != null) {
                while (!flight.done) {
                    try { state.wait(); }
                    catch (InterruptedException error) {
                        Thread.currentThread().interrupt(); throw new IOException("季信息读取已取消");
                    }
                }
                if (flight.failed) throw SeasonMatcher.unavailable();
                return flight.overview.copy();
            }
            flight = new Flight(); state.running.put(seriesId, flight);
        }
        try {
            Overview result = collect(seriesId);
            synchronized (state) {
                trimExpired();
                long ttl = "found".equals(result.status) ? state.ttl : state.emptyTtl;
                state.values.put(seriesId, new Entry(clock.nowMillis() + ttl, result.copy()));
                while (state.values.size() > state.capacity) state.values.remove(state.values.keySet().iterator().next());
                flight.overview = result.copy();
            }
            return result;
        } catch (Exception error) {
            synchronized (state) { flight.failed = true; }
            // 固定诊断不复制上游 body、签名地址或异常原因；失败绝不缓存。
            throw SeasonMatcher.unavailable();
        } finally {
            synchronized (state) {
                flight.done = true; state.running.remove(seriesId); state.notifyAll();
            }
        }
    }

    public void clear() { synchronized (state) { state.values.clear(); } }

    private void trimExpired() {
        long now = clock.nowMillis();
        Iterator<Entry> entries = state.values.values().iterator();
        while (entries.hasNext()) if (entries.next().expires <= now) entries.remove();
    }

    private Overview collect(String sid) throws IOException {
        Map<String, Metadata> first = source.details(Collections.singletonList(sid));
        Metadata current = first == null ? null : first.get(sid);
        if (current == null || !sid.equals(current.series.seriesId)) throw SeasonMatcher.unavailable();
        current = complete(sid, current);
        int index = SeasonMatcher.number(current);
        Season currentSeason = new Season(index, current.series, current.firstEpisode);
        List<Season> items = new ArrayList<>(); items.add(currentSeason);
        List<Metadata> members = new ArrayList<>(); members.add(current);
        String query = SeasonMatcher.discoveryQuery(SeasonMatcher.parseTitle(current.series.title));
        if (!query.isEmpty()) {
            List<Api.Series> discovered = source.search(query);
            if (discovered == null) throw SeasonMatcher.unavailable();
            List<String> ids = new ArrayList<>(); Set<String> seen = new HashSet<>(); seen.add(sid);
            for (Api.Series candidate : discovered) {
                if (candidate != null && SeasonMatcher.validId(candidate.seriesId) && seen.add(candidate.seriesId)) {
                    ids.add(candidate.seriesId);
                    if (ids.size() == 40) break;
                }
            }
            if (!ids.isEmpty()) {
                Map<String, Metadata> details = source.details(ids);
                if (details == null) throw SeasonMatcher.unavailable();
                for (String candidateId : ids) {
                    Metadata candidate = details.get(candidateId);
                    if (candidate == null || !candidateId.equals(candidate.series.seriesId)) throw SeasonMatcher.unavailable();
                    if (SeasonMatcher.needsIdentityDetail(current, candidate)) candidate = readSingle(candidateId);
                    int number = SeasonMatcher.matchNumber(current, candidate);
                    if (number > 0) {
                        candidate = complete(candidateId, candidate);
                        number = SeasonMatcher.matchNumber(current, candidate);
                        if (number <= 0) continue;
                        items.add(new Season(number, candidate.series, candidate.firstEpisode)); members.add(candidate);
                    }
                }
            }
        }
        Collections.sort(items, (left, right) -> Integer.compare(left.number, right.number));
        Set<Integer> numbers = new HashSet<>(); boolean ambiguous = false;
        for (Season item : items) if (!numbers.add(item.number)) ambiguous = true;
        Season next = null;
        if (ambiguous) { items.clear(); items.add(currentSeason); }
        else for (Metadata member : members) {
            if (SeasonMatcher.canAutoAdvance(current, member)) {
                for (Season item : items) if (item.series.seriesId.equals(member.series.seriesId)) next = item;
                break;
            }
        }
        return new Overview(sid, ambiguous ? "ambiguous" : items.size() > 1 ? "found" : "single",
                current.official() ? "official" : "title", items, currentSeason, next, ambiguous);
    }

    private Metadata complete(String sid, Metadata metadata) throws IOException {
        if (metadata.series.episodeCount > 0 && metadata.firstEpisode > 0) return metadata;
        // 大批详情有时只有身份字段；只补已匹配成员，不为无关搜索结果拉集列表。
        return readSingle(sid);
    }

    private Metadata readSingle(String sid) throws IOException {
        Map<String, Metadata> result = source.details(Collections.singletonList(sid));
        Metadata full = result == null ? null : result.get(sid);
        if (full == null || !sid.equals(full.series.seriesId) || full.series.episodeCount <= 0) {
            throw SeasonMatcher.unavailable();
        }
        return full;
    }

    private static final class State {
        final int capacity; final long ttl, emptyTtl;
        final LinkedHashMap<String, Entry> values = new LinkedHashMap<>(16, 0.75f, true);
        final Map<String, Flight> running = new HashMap<>();
        State(int capacity, long ttl, long emptyTtl) {
            this.capacity = capacity; this.ttl = ttl; this.emptyTtl = Math.min(ttl, emptyTtl);
        }
    }
    private static final class Entry {
        final long expires; final Overview overview;
        Entry(long expires, Overview overview) { this.expires = expires; this.overview = overview; }
    }
    private static final class Flight { boolean done, failed; Overview overview; }
}
