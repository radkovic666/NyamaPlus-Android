package fun.nyama.tv;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TvSubtitleFileTest {
    private static final String SRT = "1\r\n00:00:01,000 --> 00:00:03,500\r\n<b>Здравей</b> &amp; welcome\r\nSecond line\r\n\r\n2\r\n00:00:04,000 --> 00:00:06,000\r\nAnother caption\r\n";
    @Test public void srtRetainsCompleteTextAndTimingWithSafeMarkupHandling() throws Exception {
        TvSubtitleFile file = TvSubtitleFile.parse(SRT.getBytes(StandardCharsets.UTF_8), "Release.srt", "bg");
        assertEquals(2, file.cues.length()); assertEquals(1, file.cues.getJSONArray(0).getDouble(0), .001);
        assertEquals(3.5, file.cues.getJSONArray(0).getDouble(1), .001);
        assertEquals("Здравей & welcome\nSecond line", file.cues.getJSONArray(0).getString(2));
        assertTrue(file.id.matches("[0-9a-f]{64}")); assertEquals("bg", file.language);
    }
    @Test public void webVttSupportsCueIdentifiersSettingsAndOverlappingCaptions() throws Exception {
        String vtt = "WEBVTT\nKind: captions\n\nNOTE source metadata\nignore\n\nfirst\n00:01.000 --> 00:04.000 align:start\nFirst\n\n00:02.000 --> 00:05.000\nSecond\n";
        TvSubtitleFile file = TvSubtitleFile.parse(vtt.getBytes(StandardCharsets.UTF_8), "File.vtt", "en");
        assertEquals(2, file.cues.length()); assertEquals("en", file.language);
        assertEquals("First", file.cues.getJSONArray(0).getString(2));
    }
    @Test public void utf8BomUtf16AndLegacyBulgarianAreSupported() throws Exception {
        for (Charset encoding : new Charset[]{StandardCharsets.UTF_8, StandardCharsets.UTF_16, Charset.forName("windows-1251")}) {
            TvSubtitleFile file = TvSubtitleFile.parse(SRT.getBytes(encoding), "Local", "bg");
            assertTrue(file.cues.getJSONArray(0).getString(2).contains("Здравей"));
        }
        TvSubtitleFile file = TvSubtitleFile.parse(("\uFEFF" + SRT).getBytes(StandardCharsets.UTF_8), "Local", "bg");
        assertEquals(2, file.cues.length());
    }
    @Test public void executableMarkupAndFormattingControlsNeverBecomeSubtitleResources() throws Exception {
        String text = "1\n00:00:01,000 --> 00:00:02,000\n<script>doNotRun()</script><img src='https://example.com/a'>Safe {\\an8}caption";
        TvSubtitleFile file = TvSubtitleFile.parse(text.getBytes(StandardCharsets.UTF_8), "a\u202E.srt\n", "bg");
        assertEquals("Safe caption", file.cues.getJSONArray(0).getString(2)); assertEquals("a.srt", file.label);
    }
    @Test public void malformedBinaryEmptyAndOversizedFilesAreRejected() throws Exception {
        for (byte[] invalid : new byte[][]{new byte[0], new byte[]{0, 1, 2}, "not subtitles".getBytes(),
                "1\n00:70:00,000 --> 00:71:00,000\nBad".getBytes(),
                "1\n00:00:03,000 --> 00:00:02,000\nBad".getBytes(), new byte[TvSubtitleFile.MAX_BYTES + 1]}) {
            try { TvSubtitleFile.read(new ByteArrayInputStream(invalid), "bad", "en"); fail("Must reject invalid data"); }
            catch (IOException expected) { assertTrue(expected.getMessage().startsWith("subtitle_")); }
        }
    }
    @Test public void cacheAndPreferencesAreScopedAndDoNotStoreSignedSourceUrls() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("nyama_tv_subtitles", 0).edit().clear().commit();
        TvSubtitleStore episode = new TvSubtitleStore(context, "https://vaplayer.ru/embed/tv/tt0944947/2/3");
        TvSubtitleStore otherEpisode = new TvSubtitleStore(context, "https://vaplayer.ru/embed/tv/tt0944947/2/4");
        TvSubtitleStore otherProvider = new TvSubtitleStore(context, "https://vidrock.to/embed/tv/tt0944947/2/3");
        TvSubtitleFile file = TvSubtitleFile.parse(SRT.getBytes(StandardCharsets.UTF_8), "Release.srt", "bg");
        episode.cache(file); assertEquals(file.json().toString(), episode.load(file.id).json().toString());
        episode.save("local", "", "", file.id, .5);
        assertEquals(.5, episode.read().getDouble("offset"), .001);
        assertEquals(0, otherEpisode.read().length()); assertEquals(0, otherProvider.read().length());
        assertFalse(context.getSharedPreferences("nyama_tv_subtitles", 0).getAll().toString().contains("https://"));
        try { episode.load("../private"); fail(); } catch (IOException expected) { }
    }
    @Test public void selectingAnotherActualFileChangesItsIdentity() throws Exception {
        TvSubtitleFile first = TvSubtitleFile.parse(SRT.getBytes(StandardCharsets.UTF_8), "Same name", "bg");
        TvSubtitleFile changed = TvSubtitleFile.parse(SRT.replace("Another caption", "Other release").getBytes(StandardCharsets.UTF_8), "Same name", "bg");
        assertNotEquals(first.id, changed.id);
        assertEquals(first.id, TvSubtitleFile.parse(SRT.getBytes(StandardCharsets.UTF_8), "Renamed", "bg").id);
    }
}
