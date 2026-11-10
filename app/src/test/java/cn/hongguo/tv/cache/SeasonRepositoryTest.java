package cn.hongguo.tv.cache;

import cn.hongguo.tv.api.Api;
import cn.hongguo.tv.api.SeasonMatcher;
import cn.hongguo.tv.api.SeasonMatcher.Metadata;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class SeasonRepositoryTest {
    private static final String CURRENT = "12345678", NEXT = "23456789", THIRD = "34567890", OTHER = "45678901", ALBUM = "987654321";
    private static Metadata metadata(String id, String title, int number, String album, int total) throws Exception {
        JSONObject raw = new JSONObject().put("series_title", title).put("content_type", 2)
                .put("season_index", number).put("related_album_id", album).put("is_multi_season", !album.isEmpty())
                .put("total_episode", total).put("episode_cnt", 3).put("video_list", new JSONArray()
                        .put(new JSONObject().put("vid_index", 3).put("vid", "video-3"))
                        .put(new JSONObject().put("vid_index", 1).put("vid", "video-1")));
        return Metadata.from(id, raw);
    }
    private static final class Fixture implements SeasonRepository.Source {
        final Map<String, Metadata> values = new LinkedHashMap<>();
        final AtomicInteger detailCalls = new AtomicInteger(), searchCalls = new AtomicInteger();
        final Map<String, Metadata> partialValues = new LinkedHashMap<>();
        final List<List<String>> requestedIds = new ArrayList<>();
        volatile boolean failSearch, missingDetail;
        volatile String missingSingleId;
        volatile CountDownLatch started, release;
        String lastQuery = "";
        Fixture(Metadata... items) { for (Metadata item : items) values.put(item.series.seriesId, item); }
        @Override public Map<String, Metadata> details(List<String> ids) throws IOException {
            int call = detailCalls.incrementAndGet();
            if (call == 1 && started != null) {
                started.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("fixture timeout"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("fixture cancelled"); }
            }
            Map<String, Metadata> out = new LinkedHashMap<>();
            synchronized (requestedIds) { requestedIds.add(new ArrayList<>(ids)); }
            if (!missingDetail) for (String id : ids) {
                if (ids.size() == 1 && id.equals(missingSingleId)) continue;
                if (ids.size() > 1 && partialValues.containsKey(id)) out.put(id, partialValues.get(id));
                else if (values.containsKey(id)) out.put(id, values.get(id));
            }
            return out;
        }
        @Override public List<Api.Series> search(String query) throws IOException {
            searchCalls.incrementAndGet(); lastQuery = query;
            if (failSearch) throw new IOException("fixed upstream fixture");
            List<Api.Series> out = new ArrayList<>();
            for (Metadata item : values.values()) out.add(SeasonMatcher.copySeries(item.series));
            return out;
        }
    }
    private static SeasonRepository repository(Fixture source, long[] clock, int capacity) {
        return new SeasonRepository(source, capacity, 100L, 20L, () -> clock[0]);
    }
    private static List<Integer> numbers(SeasonRepository.Overview overview) {
        List<Integer> out = new ArrayList<>(); for (SeasonRepository.Season item : overview.items) out.add(item.number); return out;
    }

    @Test public void overviewSortsAllEightOfficialSeasonsAndUsesRealTotals() throws Exception {
        Metadata[] rows = new Metadata[8];
        for (int number = 1; number <= 8; number++) rows[8 - number] = metadata(String.valueOf(12345677 + number),
                "觉醒伪神：神明弃我我自为神" + (number == 1 ? "" : number), number, ALBUM, number * 11);
        Fixture source = new Fixture(rows); SeasonRepository repo = repository(source, new long[]{1}, 128);
        SeasonRepository.Overview overview = repo.overview(CURRENT);
        assertEquals("觉醒伪神", source.lastQuery); assertEquals("found", overview.status);
        assertEquals(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8), numbers(overview));
        for (SeasonRepository.Season item : overview.items) assertEquals(item.number * 11, item.series.episodeCount);
        assertEquals(1, overview.current.number); assertEquals(2, overview.next.number); assertEquals(1, overview.next.firstEpisode);
    }
    @Test public void overviewFromMiddleSeasonIncludesPreviousAndFollowingSeasons() throws Exception {
        Fixture source = new Fixture(metadata(THIRD, "秋露", 3, ALBUM, 31), metadata(CURRENT, "春风", 1, ALBUM, 12), metadata(NEXT, "夏雨", 2, ALBUM, 22));
        SeasonRepository.Overview overview = repository(source, new long[]{1}, 128).overview(NEXT);
        assertEquals(Arrays.asList(1, 2, 3), numbers(overview)); assertEquals(2, overview.current.number);
        assertEquals(THIRD, overview.next.series.seriesId);
    }
    @Test public void missingImmediateSeasonDoesNotSkipToThird() throws Exception {
        Fixture source = new Fixture(metadata(CURRENT, "春风", 1, ALBUM, 12), metadata(THIRD, "秋露", 3, ALBUM, 31));
        SeasonRepository.Overview overview = repository(source, new long[]{1}, 128).overview(CURRENT);
        assertEquals(Arrays.asList(1, 3), numbers(overview)); assertNull(overview.next);
    }
    @Test public void duplicateCurrentOrLaterOfficialIndexIsAmbiguous() throws Exception {
        for (int duplicate : new int[]{1, 2, 3}) {
            Fixture source = new Fixture(metadata(CURRENT, "春风", 1, ALBUM, 12), metadata(NEXT, "夏雨", 2, ALBUM, 22),
                    metadata(THIRD, "秋露", 3, ALBUM, 31), metadata(OTHER, "冬雪", duplicate, ALBUM, 41));
            SeasonRepository.Overview overview = repository(source, new long[]{1}, 128).overview(CURRENT);
            assertEquals("ambiguous", overview.status); assertTrue(overview.ambiguous);
            assertEquals(Arrays.asList(1), numbers(overview)); assertNull(overview.next);
        }
    }
    @Test public void strictTitleManualOverviewCannotAutoAdvanceWithoutOfficialAlbum() throws Exception {
        Fixture source = new Fixture(metadata(CURRENT, "春风", 0, "", 12), metadata(NEXT, "春风第二季", 0, "", 22),
                metadata(OTHER, "春风十里第二季", 0, "", 1));
        SeasonRepository.Overview overview = repository(source, new long[]{1}, 128).overview(CURRENT);
        assertEquals(Arrays.asList(1, 2), numbers(overview)); assertEquals("title", overview.source); assertNull(overview.next);
    }
    @Test public void successfulCacheCannotBeMutatedByCallersAndRefreshBypassesIt() throws Exception {
        Fixture source = new Fixture(metadata(CURRENT, "春风", 1, ALBUM, 12), metadata(NEXT, "夏雨", 2, ALBUM, 22));
        long[] clock = {1}; SeasonRepository repo = repository(source, clock, 128);
        SeasonRepository.Overview first = repo.overview(CURRENT); int reads = source.detailCalls.get();
        first.current.series.title = "edited"; first.next.series.seriesId = OTHER;
        SeasonRepository.Overview second = repo.overview(CURRENT);
        assertEquals("春风", second.current.series.title); assertEquals(NEXT, second.next.series.seriesId);
        assertEquals(reads, source.detailCalls.get());
        try { second.items.clear(); fail(); } catch (UnsupportedOperationException expected) { }
        repo.overview(CURRENT, true); assertTrue(source.detailCalls.get() > reads);
        reads = source.detailCalls.get(); clock[0] += 101; repo.overview(CURRENT); assertTrue(source.detailCalls.get() > reads);
    }
    @Test public void singleAndAmbiguousCacheHasShortTtlAndCapacityIsBounded() throws Exception {
        Fixture source = new Fixture(metadata(CURRENT, "春风", 1, ALBUM, 12), metadata(OTHER, "冬雪", 1, "123456789", 41));
        long[] clock = {1}; SeasonRepository repo = repository(source, clock, 1);
        repo.overview(CURRENT); int reads = source.detailCalls.get(); repo.overview(CURRENT); assertEquals(reads, source.detailCalls.get());
        clock[0] += 21; repo.overview(CURRENT); assertTrue(source.detailCalls.get() > reads);
        repo.overview(OTHER); reads = source.detailCalls.get(); repo.overview(CURRENT); assertTrue(source.detailCalls.get() > reads);
    }
    @Test public void searchAndDetailFailuresAreNotCached() throws Exception {
        Fixture source = new Fixture(metadata(CURRENT, "春风", 1, ALBUM, 12), metadata(NEXT, "夏雨", 2, ALBUM, 22));
        SeasonRepository repo = repository(source, new long[]{1}, 128);
        source.failSearch = true;
        try { repo.overview(CURRENT); fail(); } catch (IOException error) { assertEquals("剧集季信息暂时不可用，请稍后重试", error.getMessage()); }
        source.failSearch = false; source.missingDetail = true;
        try { repo.overview(CURRENT); fail(); } catch (IOException expected) { }
        source.missingDetail = false; assertEquals(NEXT, repo.overview(CURRENT).next.series.seriesId);
        assertEquals(3, source.detailCalls.get() - 1);
    }
    @Test public void concurrentSameSeriesLookupsShareOneFetch() throws Exception {
        Fixture source = new Fixture(metadata(CURRENT, "春风", 1, ALBUM, 12), metadata(NEXT, "夏雨", 2, ALBUM, 22));
        source.started = new CountDownLatch(1); source.release = new CountDownLatch(1);
        SeasonRepository repo = repository(source, new long[]{1}, 128);
        CountDownLatch finished = new CountDownLatch(2); AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable lookup = () -> { try { assertEquals(NEXT, repo.overview(CURRENT).next.series.seriesId); }
            catch (Throwable error) { failure.compareAndSet(null, error); } finally { finished.countDown(); } };
        Thread first = new Thread(lookup), second = new Thread(lookup); first.start();
        assertTrue(source.started.await(1, TimeUnit.SECONDS)); second.start(); source.release.countDown();
        assertTrue(finished.await(3, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(2, source.detailCalls.get()); assertEquals(1, source.searchCalls.get());
    }
    @Test public void invalidSeriesIdDoesNotReachContentApi() throws Exception {
        Fixture source = new Fixture(); SeasonRepository repo = repository(source, new long[]{1}, 128);
        for (String id : new String[]{null, "", "12", "../12345678"}) {
            try { repo.overview(id); fail(); } catch (IOException expected) { }
        }
        assertEquals(0, source.detailCalls.get()); assertEquals(0, source.searchCalls.get());
    }
    @Test public void concurrentFailureIsSharedAndNeverCachedAsEmptyOverview() throws Exception {
        Fixture source = new Fixture(metadata(CURRENT, "春风", 1, ALBUM, 12), metadata(NEXT, "夏雨", 2, ALBUM, 22));
        source.started = new CountDownLatch(1); source.release = new CountDownLatch(1); source.failSearch = true;
        SeasonRepository repo = repository(source, new long[]{1}, 128);
        CountDownLatch finished = new CountDownLatch(2); AtomicInteger failures = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Runnable lookup = () -> { try { repo.overview(CURRENT, true); unexpected.compareAndSet(null, new AssertionError("expected failure")); }
            catch (IOException expected) { failures.incrementAndGet(); }
            catch (Throwable error) { unexpected.compareAndSet(null, error); } finally { finished.countDown(); } };
        Thread first = new Thread(lookup), second = new Thread(lookup); first.start();
        assertTrue(source.started.await(1, TimeUnit.SECONDS)); second.start();
        for (int i = 0; i < 1000 && second.getState() != Thread.State.WAITING; i++) Thread.sleep(1);
        assertEquals(Thread.State.WAITING, second.getState()); source.release.countDown();
        assertTrue(finished.await(3, TimeUnit.SECONDS)); if (unexpected.get() != null) throw new AssertionError(unexpected.get());
        assertEquals(2, failures.get()); assertEquals(1, source.detailCalls.get()); assertEquals(1, source.searchCalls.get());
        source.failSearch = false; assertEquals(NEXT, repo.overview(CURRENT).next.series.seriesId);
        assertEquals(3, source.detailCalls.get()); assertEquals(2, source.searchCalls.get());
    }
    private static Fixture partialBatchFixture() throws Exception {
        Fixture source = new Fixture(metadata(CURRENT, "家里家外", 1, ALBUM, 79), metadata(NEXT, "家里家外第二季", 2, ALBUM, 111),
                metadata(OTHER, "无关作品", 1, "123456789", 50));
        for (String id : Arrays.asList(NEXT, OTHER)) {
            Metadata full = source.values.get(id);
            JSONObject raw = new JSONObject().put("series_id", Long.parseLong(id)).put("series_title", full.series.title)
                    .put("related_album_id", Long.parseLong(full.relatedAlbumId)).put("season_index", full.seasonIndex)
                    .put("content_type", 2).put("episode_cnt", 999);
            source.partialValues.put(id, Metadata.fromIdentity(id, raw));
        }
        return source;
    }
    @Test public void metadataOnlyBatchLoadsRealEpisodesForMatchingSeasonOnly() throws Exception {
        Fixture source = partialBatchFixture(); SeasonRepository repo = repository(source, new long[]{1}, 128);
        SeasonRepository.Overview result = repo.overview(CURRENT);
        assertEquals("found", result.status); assertEquals(2, result.items.size());
        assertEquals(79, result.current.series.episodeCount); assertEquals(111, result.items.get(1).series.episodeCount);
        assertNotNull(result.next); assertEquals(NEXT, result.next.series.seriesId);
        assertEquals(3, source.detailCalls.get()); assertEquals(Arrays.asList(NEXT), source.requestedIds.get(2));
    }
    @Test public void matchingSeasonSupplementFailureIsNotCached() throws Exception {
        Fixture source = partialBatchFixture(); source.missingSingleId = NEXT;
        SeasonRepository repo = repository(source, new long[]{1}, 128);
        try { repo.overview(CURRENT); fail(); } catch (IOException expected) { }
        source.missingSingleId = null;
        assertEquals(111, repo.overview(CURRENT).next.series.episodeCount); assertEquals(6, source.detailCalls.get());
    }
    @Test public void metadataOnlyBatchMayAlsoOmitOfficialAlbumAndSeasonIndex() throws Exception {
        Fixture source = partialBatchFixture();
        source.partialValues.put(NEXT, Metadata.fromIdentity(NEXT, new JSONObject().put("series_title", "家里家外第二季")
                .put("content_type", 2).put("episode_cnt", 999)));
        source.partialValues.put(OTHER, Metadata.fromIdentity(OTHER, new JSONObject().put("series_title", "无关作品")
                .put("content_type", 2).put("episode_cnt", 999)));
        SeasonRepository.Overview result = repository(source, new long[]{1}, 128).overview(CURRENT);
        assertEquals("found", result.status); assertEquals(111, result.next.series.episodeCount);
        assertEquals(3, source.detailCalls.get()); assertEquals(Arrays.asList(NEXT), source.requestedIds.get(2));
    }
    @Test public void titleOnlySupplementMustStillProveOfficialAlbumBeforeAutomaticJump() throws Exception {
        Metadata unlinked = Metadata.fromIdentity(NEXT, new JSONObject().put("series_title", "家里家外第二季")
                .put("content_type", 2).put("total_episode", 111).put("video_list", new JSONArray()
                        .put(new JSONObject().put("vid_index", 1).put("vid", "fixture-next"))));
        Fixture source = new Fixture(metadata(CURRENT, "家里家外", 1, ALBUM, 79), unlinked);
        SeasonRepository.Overview result = repository(source, new long[]{1}, 128).overview(CURRENT);
        assertEquals("single", result.status); assertNull(result.next); assertEquals(1, result.items.size());
        assertEquals(3, source.detailCalls.get()); assertEquals(Arrays.asList(NEXT), source.requestedIds.get(2));
    }
}
