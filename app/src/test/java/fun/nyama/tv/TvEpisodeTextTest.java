package fun.nyama.tv;

import org.junit.Test;

import static org.junit.Assert.*;

public class TvEpisodeTextTest {
    @Test public void preservesParagraphsAndLineBreaksWithoutBreakingInlineWords() {
        assertEquals("Winter is coming.\n\nFirst line\nSecond line\n\nNext item",
                TvEpisodeText.plainText("<p><b>Win</b>ter is <em>coming</em>.</p>"
                        + "<div>First line<br/>Second line</div><ul><li>Next item</li></ul>"));
    }

    @Test public void removesUnsafeContentsCommentsImagesAndAttributes() {
        assertEquals("BeforeAfter\n\nSafe text", TvEpisodeText.plainText("Before"
                + "<script>if (a < b) alert('secret');</script>"
                + "<style>.secret { color: red; }</style><object><object>nested</object>hidden</object>"
                + "<iframe src='https://untrusted.invalid'>hidden frame</iframe>"
                + "<svg>hidden vector</svg><img src='https://untrusted.invalid/image' alt='hidden'>"
                + "<!-- hidden comment -->After<p title='a > b' onclick='bad()'>Safe text</p>"));
    }

    @Test public void decodesEntitiesExactlyOnceIncludingUnicodeCodePoints() {
        assertEquals("& < > \" ' – — … ' 😀 &lt;tag&gt; &unknown;",
                TvEpisodeText.plainText("&amp; &lt; &gt; &quot; &apos; &ndash; &mdash; &hellip; "
                        + "&#39; &#x1F600; &amp;lt;tag&amp;gt; &unknown;"));
        assertEquals("ab", TvEpisodeText.plainText("a&#0;&#xD800;&#x01;b"));
    }

    @Test public void handlesMalformedTagsAndUnclosedUnsafeBlocksConservatively() {
        assertEquals("A < B and C", TvEpisodeText.plainText("A < B and C"));
        assertEquals("Visible", TvEpisodeText.plainText("Visible<script>never shown"));
        assertEquals("VisibleAfter", TvEpisodeText.plainText("Visible<script/>never shown</script>After"));
        assertEquals("Visible", TvEpisodeText.plainText("Visible<style title='unterminated"));
        assertEquals("Visible", TvEpisodeText.plainText("Visible<!-- unfinished"));
        assertEquals("BeforeAfter", TvEpisodeText.plainText("Before<object/>After"));
    }

    @Test public void retainsCompleteLongDescriptionsAndNormalizesWhitespace() {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 2000; i++) source.append("<p>Paragraph ").append(i).append(" &amp; detail.</p>");
        String result = TvEpisodeText.plainText(source.toString());
        assertTrue(result.startsWith("Paragraph 0 & detail.\n\nParagraph 1"));
        assertTrue(result.endsWith("Paragraph 1999 & detail."));
        assertFalse(result.contains("<p>"));
        assertEquals("One two\nThree", TvEpisodeText.plainText("  One\t&nbsp; two \r\n Three  "));
    }

    @Test public void legacyEpisodeConstructorsRetainTheirExistingSummaryContract() {
        TvSeriesMetadata.Episode episode = new TvSeriesMetadata.Episode(
                1, 2, "Title", 45, "Full existing summary", "", -1, 1000);
        assertEquals("Full existing summary", episode.summary);
        assertEquals(episode.summary, episode.fullSummary);
        assertEquals("", episode.airDate);
    }
}
