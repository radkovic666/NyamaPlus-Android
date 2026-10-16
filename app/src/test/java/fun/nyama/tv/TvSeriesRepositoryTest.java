package fun.nyama.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.Assert.*;

/** Synthetic API contracts only: these tests establish no real provider availability. */
public class TvSeriesRepositoryTest {
    private static final long NOW = 1780000000000L;
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final FakeClock clock = new FakeClock();
    private final FakeTransport transport = new FakeTransport();
    private File cache;
    private TvSeriesRepository repository;

    @Before public void before() throws Exception {
        // Rate limits are process-wide in production; each mock scenario gets its own process state.
        for (String fieldName : new String[]{"nextRequestMs", "retryUntilMs"}) {
            Field field = TvSeriesRepository.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setLong(null, 0L);
        }
        clock.now = NOW;
        cache = temporary.newFolder();
        repository = new TvSeriesRepository(cache, transport, clock);
    }

    @Test public void multiSeasonRowsRetainRealEpisodeNumbersAndDescriptions() throws Exception {
        enqueueShow("tt0944947", 82);
        transport.json("[{\"number\":1},{\"number\":2}]");
        transport.json(new JSONArray().put(episode(1, 1, "Winter Is Coming", "2011-04-17"))
                .put(episode(2, 1, "The North Remembers", "2012-04-01"))
                .put(episode(2, 2, "The Night Lands", "2012-04-08")).toString());
        TvSeriesMetadata metadata = repository.load("tt0944947", new JSONObject().put("totalSeasons", "8"), NOW);
        assertEquals("tt0944947", metadata.imdbId);
        assertEquals("TVmaze (CC BY-SA)", metadata.source);
        assertEquals("https://www.tvmaze.com/shows/82", metadata.sourceUrl);
        assertEquals(2, metadata.seasons.size());
        assertEquals(1, metadata.seasons.get(0).episodes.size());
        assertEquals(2, metadata.seasons.get(1).episodes.size());
        TvSeriesMetadata.Episode selected = metadata.seasons.get(1).episodes.get(1);
        assertEquals(2, selected.season);
        assertEquals(2, selected.number);
        assertEquals("The Night Lands", selected.title);
        assertEquals("Actual description & detail", selected.summary);
        assertEquals(57, selected.durationMinutes);
        assertTrue(selected.eligibleForPlayback());
        assertEquals("https://api.tvmaze.com/lookup/shows?imdb=tt0944947", transport.addresses.get(0));
        assertEquals("https://api.tvmaze.com/shows/82/episodes", transport.addresses.get(2));
    }

    @Test public void miniseriesHasOnlyItsExplicitSeasonAndEpisodes() throws Exception {
        enqueueShow("tt7366338", 1);
        transport.json("[{\"number\":1}]");
        transport.json(new JSONArray().put(episode(1, 1, "1:23:45", "2019-05-06"))
                .put(episode(1, 5, "Vichnaya Pamyat", "2019-06-03")).toString());
        TvSeriesMetadata metadata = repository.load("tt7366338", null, NOW);
        assertEquals(1, metadata.seasons.size());
        assertEquals(2, metadata.seasons.get(0).episodes.size());
        assertEquals(5, metadata.seasons.get(0).episodes.get(1).number);
    }

    @Test public void nestedPrimaryEpisodeRowsWinWithoutAnySupplementaryNetwork() throws Exception {
        JSONObject primary = new JSONObject().put("imdbID", "tt0944947").put("data", new JSONObject()
                .put("seasons", new JSONArray().put(new JSONObject().put("number", 2)
                        .put("episodes", new JSONArray().put(new JSONObject().put("number", 7)
                                .put("Title", "A Man Without Honor").put("air_date", "2012-05-13"))))));
        TvSeriesMetadata metadata = repository.load("tt0944947", primary, NOW);
        assertEquals("Pukanki.Fun", metadata.source);
        assertEquals(2, metadata.seasons.get(0).number);
        assertEquals(7, metadata.seasons.get(0).episodes.get(0).number);
        assertEquals(-1, metadata.seasons.get(0).episodes.get(0).durationMinutes);
        assertEquals(0, transport.addresses.size());
    }

    @Test public void aggregateCountsCannotCreateFakeEpisodes() throws Exception {
        assertNull(TvSeriesRepository.parsePukanki("tt0944947", new JSONObject()
                .put("totalSeasons", "8").put("episodeCount", 73).put("seasons", "8"), NOW));
    }

    @Test public void invalidOrTmdbIdentityFailsBeforeNetwork() throws Exception {
        for (String value : new String[]{"1399", "Game of Thrones", "tt123", "tt1234567/1", ""}) {
            try { repository.load(value, null, NOW); fail("Identity accepted: " + value); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("IMDb")); }
        }
        assertTrue(transport.addresses.isEmpty());
    }

    @Test public void mismatchedLookupIsNeverAcceptedOrCached() throws Exception {
        enqueueShow("tt0903747", 169);
        try { repository.load("tt0944947", null, NOW); fail("Mismatched show accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("different IMDb")); }
        assertEquals(1, transport.addresses.size());
        assertEquals(0, cache.list().length);
    }

    @Test public void unrelatedPrimaryIdentityCannotOverrideRequestedShow() throws Exception {
        JSONObject primary = new JSONObject().put("imdbID", "tt0903747").put("episodes",
                new JSONArray().put(episode(1, 1, "Pilot", "2008-01-20")));
        assertNull(TvSeriesRepository.parsePukanki("tt0944947", primary, NOW));
        JSONObject embeddedMismatch = new JSONObject().put("data", primary);
        assertNull(TvSeriesRepository.parsePukanki("tt0944947", embeddedMismatch, NOW));
    }

    @Test public void futureAndUnnumberedEpisodesAreExcludedUnknownDatesRemainDisabled() throws Exception {
        enqueueShow("tt0944947", 82);
        transport.json("[{\"number\":0},{\"number\":1}]");
        transport.json(new JSONArray().put(episode(1, 1, "Aired", "2011-04-17"))
                .put(episode(1, 2, "Future", "2099-01-01"))
                .put(episode(1, 3, "Unknown", ""))
                .put(episode(1, 4, "Malformed date", "2020-02-31"))
                .put(episode(0, 1, "Actual numbered special", "2011-01-01"))
                .put(episode(0, 2, "Unnumbered", "2011-01-01").put("number", JSONObject.NULL)).toString());
        TvSeriesMetadata metadata = repository.load("tt0944947", null, NOW);
        assertEquals(2, metadata.seasons.size());
        assertEquals(1, metadata.seasons.get(0).number);
        assertEquals(3, metadata.seasons.get(0).episodes.size());
        assertTrue(metadata.seasons.get(0).episodes.get(0).eligibleForPlayback());
        assertFalse(metadata.seasons.get(0).episodes.get(1).eligibleForPlayback());
        assertFalse(metadata.seasons.get(0).episodes.get(2).eligibleForPlayback());
        assertEquals(0, metadata.seasons.get(1).number);
        assertEquals(1, metadata.seasons.get(1).episodes.size());
    }

    @Test public void episodeOwnershipLinkCannotPointToAnotherShow() throws Exception {
        enqueueShow("tt0944947", 82);
        transport.json("[{\"number\":1}]");
        transport.json(new JSONArray().put(episode(1, 1, "Wrong", "2011-04-17")
                .put("_links", new JSONObject().put("show", new JSONObject()
                        .put("href", "https://api.tvmaze.com/shows/169")))).toString());
        try { repository.load("tt0944947", null, NOW); fail("Cross-show row accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("different show")); }
    }

    @Test public void safeHttpsLookupRedirectIsFollowedAndRevalidated() throws Exception {
        transport.redirect("/shows/82");
        enqueueShow("tt0944947", 82);
        transport.json("[{\"number\":1}]");
        transport.json(new JSONArray().put(episode(1, 1, "Pilot", "2011-04-17")).toString());
        assertEquals(1, repository.load("tt0944947", null, NOW).seasons.size());
        assertEquals("https://api.tvmaze.com/shows/82", transport.addresses.get(1));
    }

    @Test public void redirectsCannotDowngradeTlsEscapeHostOrAttachCredentials() throws Exception {
        for (String address : new String[]{"http://api.tvmaze.com/shows/82",
                "https://unrelated.invalid/shows/82", "https://api.tvmaze.com.evil.invalid/shows/82",
                "https://user:password@api.tvmaze.com/shows/82", "https://api.tvmaze.com:444/shows/82",
                "https://api.tvmaze.com/shows/82#fragment"}) {
            try { TvSeriesRepository.verifiedApiUrl(address); fail("Unsafe redirect accepted"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("Unsafe")); }
        }
        transport.redirect("http://api.tvmaze.com/shows/82");
        try { repository.load("tt0944947", null, NOW); fail("HTTP redirect followed"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Unsafe")); }
        assertEquals(1, transport.addresses.size());
    }

    @Test public void redirectLoopsAreBounded() throws Exception {
        for (int i = 0; i < 4; i++) transport.redirect("/shows/82");
        try { repository.load("tt0944947", null, NOW); fail("Loop accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("redirect")); }
        assertEquals(4, transport.addresses.size());
    }

    @Test public void seasonSwitchAndRepositoryRecreationReuseSuccessfulCache() throws Exception {
        enqueueSingleSeason();
        TvSeriesMetadata first = repository.load("tt0944947", null, NOW);
        assertSame(first, repository.load("tt0944947", null, NOW + 1000L));
        assertEquals(3, transport.addresses.size());
        TvSeriesRepository recreated = new TvSeriesRepository(cache, transport, clock);
        TvSeriesMetadata disk = recreated.load("tt0944947", null, NOW + 1000L);
        assertEquals(first.seasons.get(0).episodes.get(0).title, disk.seasons.get(0).episodes.get(0).title);
        assertEquals(3, transport.addresses.size());
        assertFalse(disk.stale);
    }

    @Test public void staleMetadataSurvivesTemporaryOfflineFailureButFailuresAreNotCached() throws Exception {
        enqueueSingleSeason();
        repository.load("tt0944947", null, NOW);
        clock.now += TvSeriesRepository.CACHE_TTL_MS + 1;
        transport.status(503, "");
        TvSeriesMetadata fallback = repository.load("tt0944947", null, clock.now);
        assertTrue(fallback.stale);
        assertEquals("Pilot", fallback.seasons.get(0).episodes.get(0).title);
        assertEquals(4, transport.addresses.size());
        transport.status(404, "");
        try { repository.load("tt0903747", null, clock.now); fail("Empty failure fabricated"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("404")); }
        assertFalse(new File(cache, "tt0903747.json").exists());
    }

    @Test public void rateLimitHonorsRetryAfterAcrossDifferentTitlesWithoutBusyRetries() throws Exception {
        transport.status(429, "120");
        try { repository.load("tt0944947", null, NOW); fail("Rate limit accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("rate limit")); }
        try { repository.load("tt0903747", null, NOW); fail("Backoff skipped"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("rate limit")); }
        assertEquals(1, transport.addresses.size());
        clock.now += 120001L;
        enqueueSingleSeason();
        assertEquals(1, repository.load("tt0944947", null, clock.now).seasons.size());
        assertEquals(4, transport.addresses.size());
    }

    @Test public void malformedEmptyAndUnavailableMetadataFailCleanly() throws Exception {
        transport.json("not json");
        try { repository.load("tt0944947", null, NOW); fail("Malformed data accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Invalid")); }
        enqueueShow("tt0944947", 82);
        transport.json("[{\"number\":1}]");
        transport.json("[]");
        try { repository.load("tt0944947", null, NOW); fail("Empty episodes accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("No numbered")); }
        assertEquals(0, cache.list().length);
    }

    @Test public void cancellationDoesNotContinueOrCachePartialResults() throws Exception {
        Thread.currentThread().interrupt();
        try {
            try { repository.load("tt0944947", null, NOW); fail("Cancellation ignored"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("cancelled")); }
            assertEquals(0, transport.addresses.size());
            assertEquals(0, cache.list().length);
        } finally { Thread.interrupted(); }
    }

    @Test public void returnedListsAreImmutableAndNoMissingDurationIsInvented() throws Exception {
        enqueueSingleSeason();
        TvSeriesMetadata metadata = repository.load("tt0944947", null, NOW);
        try { metadata.seasons.clear(); fail("Mutable seasons"); }
        catch (UnsupportedOperationException expected) { }
        try { metadata.seasons.get(0).episodes.clear(); fail("Mutable episodes"); }
        catch (UnsupportedOperationException expected) { }
        TvSeriesMetadata.Episode unknown = new TvSeriesMetadata.Episode(1, 1, "", 0, "", "", -1, NOW);
        assertEquals(-1, unknown.durationMinutes);
        assertFalse(unknown.eligibleForPlayback());
    }

    private void enqueueSingleSeason() throws Exception {
        enqueueShow("tt0944947", 82);
        transport.json("[{\"number\":1}]");
        transport.json(new JSONArray().put(episode(1, 1, "Pilot", "2011-04-17")).toString());
    }

    private void enqueueShow(String imdb, int showId) throws Exception {
        transport.json(new JSONObject().put("id", showId)
                .put("externals", new JSONObject().put("imdb", imdb)).toString());
    }

    private JSONObject episode(int season, int number, String title, String date) throws Exception {
        return new JSONObject().put("season", season).put("number", number).put("name", title)
                .put("runtime", 57).put("summary", "<p>Actual description &amp; detail</p>")
                .put("airdate", date).put("image", new JSONObject().put("medium", "https://static.tvmaze.com/still.jpg"));
    }

    private static final class FakeClock implements TvSeriesRepository.Clock {
        long now;
        @Override public long nowMillis() { return now; }
        @Override public void pause(long millis) { now += millis; }
    }

    private static final class FakeTransport implements TvSeriesRepository.Transport {
        final Deque<TvSeriesRepository.Response> responses = new ArrayDeque<>();
        final List<String> addresses = new ArrayList<>();
        void json(String body) { responses.add(new TvSeriesRepository.Response(200, body, "", "")); }
        void redirect(String location) { responses.add(new TvSeriesRepository.Response(301, "", location, "")); }
        void status(int status, String retryAfter) { responses.add(new TvSeriesRepository.Response(status, "", "", retryAfter)); }
        @Override public TvSeriesRepository.Response get(URL url) throws IOException {
            addresses.add(url.toExternalForm());
            if (responses.isEmpty()) throw new IOException("Unexpected network request in fixture");
            return responses.removeFirst();
        }
    }
}
