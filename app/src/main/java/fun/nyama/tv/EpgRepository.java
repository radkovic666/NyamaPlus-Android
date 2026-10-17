package fun.nyama.tv;

import android.content.Context;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

public final class EpgRepository {
    private static final ConcurrentHashMap<String, List<EpgEvent>> EVENTS_BY_ID = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> ALIAS_TO_ID = new ConcurrentHashMap<>();

    // Same guide behind https://is.gd/fullepg. Used only if the short URL itself fails.
    private static final String FULL_EPG_FALLBACK =
            "https://github.com/harrygg/EPG/releases/latest/download/epg.xml.gz";

    private EpgRepository() {}

    public static File cacheFile(Context context) {
        return new File(context.getFilesDir(), "epg.xmltv");
    }

    public static File parsedCacheFile(Context context) {
        return new File(context.getFilesDir(), "epg.parsed.cache");
    }

    public static synchronized void refresh(Context context) throws Exception {
        Config config = new Config(context);
        String url = config.epgUrl();
        if (url.isEmpty()) return;

        File cache = cacheFile(context);
        File staging = new File(context.getFilesDir(), "epg.download");
        deleteQuietly(staging);

        Exception primaryError = null;
        try {
            HttpUtil.downloadToFile(context, url, staging);
            ParsedEpg parsed = parseFile(staging);
            commit(staging, cache);
            install(parsed);
            writeParsedCacheQuietly(parsedCacheFile(context), parsed);
            config.setEpgLastRefresh(System.currentTimeMillis());
            return;
        } catch (Exception e) {
            primaryError = e;
            NetworkDiagnostics.logFailure(context, "primary EPG download/parse", e);
            deleteQuietly(staging);
        }

        // Keep the user's preferred is.gd URL, but make it resilient if the shortener is unavailable.
        if (isFullEpgShortUrl(url)) {
            try {
                HttpUtil.downloadToFile(context, FULL_EPG_FALLBACK, staging);
                ParsedEpg parsed = parseFile(staging);
                commit(staging, cache);
                install(parsed);
                writeParsedCacheQuietly(parsedCacheFile(context), parsed);
                config.setEpgLastRefresh(System.currentTimeMillis());
                return;
            } catch (Exception fallbackError) {
                NetworkDiagnostics.logFailure(context, "fallback EPG download/parse", fallbackError);
                deleteQuietly(staging);
                if (primaryError != null) primaryError.addSuppressed(fallbackError);
            }
        }

        if (primaryError != null) throw primaryError;
    }

    public static synchronized void parseRelevant(Context context) throws Exception {
        File file = cacheFile(context);
        if (!file.exists()) return;

        File parsedCache = parsedCacheFile(context);
        if (parsedCache.exists() && parsedCache.lastModified() >= file.lastModified()) {
            try {
                install(readParsedCache(parsedCache));
                return;
            } catch (Exception ignored) {
                deleteQuietly(parsedCache);
            }
        }

        ParsedEpg parsed = parseFile(file);
        install(parsed);
        writeParsedCacheQuietly(parsedCache, parsed);
    }

    private static ParsedEpg parseFile(File file) throws Exception {
        long now = System.currentTimeMillis();
        long min = now - 3L * 60L * 60L * 1000L;
        long max = now + 36L * 60L * 60L * 1000L;

        Map<String, List<EpgEvent>> events = new HashMap<>();
        Map<String, String> aliases = new HashMap<>();
        int programmeCount = 0;

        try (InputStream raw = openMaybeGzip(file)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(raw, null);
            int event = parser.getEventType();

            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "channel".equals(parser.getName())) {
                    parseChannelTag(parser, aliases);
                } else if (event == XmlPullParser.START_TAG && "programme".equals(parser.getName())) {
                    String channel = value(parser.getAttributeValue(null, "channel"));
                    long start = parseXmlTvTime(parser.getAttributeValue(null, "start"));
                    long stop = parseXmlTvTime(parser.getAttributeValue(null, "stop"));
                    String title = "";
                    String titleBg = "";
                    String description = "";
                    String descriptionBg = "";
                    int depth = parser.getDepth();

                    while (true) {
                        int inner = parser.next();
                        if (inner == XmlPullParser.START_TAG && "title".equals(parser.getName())) {
                            String lang = value(parser.getAttributeValue(null, "lang"));
                            String text = parser.nextText();
                            if (title.isEmpty()) title = text;
                            if ("bg".equalsIgnoreCase(lang) && !text.trim().isEmpty()) titleBg = text;
                        } else if (inner == XmlPullParser.START_TAG && "desc".equals(parser.getName())) {
                            String lang = value(parser.getAttributeValue(null, "lang"));
                            String text = parser.nextText();
                            if (description.isEmpty()) description = text;
                            if ("bg".equalsIgnoreCase(lang) && !text.trim().isEmpty()) descriptionBg = text;
                        } else if (inner == XmlPullParser.END_TAG
                                && parser.getDepth() == depth
                                && "programme".equals(parser.getName())) {
                            break;
                        }
                    }

                    if (!titleBg.isEmpty()) title = titleBg;
                    if (!descriptionBg.isEmpty()) description = descriptionBg;
                    if (!channel.isEmpty() && start > 0L && stop > start && start < max && stop > min) {
                        events.computeIfAbsent(channel, k -> new ArrayList<>())
                                .add(new EpgEvent(start, stop, value(title), value(description)));
                        registerAliases(aliases, channel, channel);
                        programmeCount++;
                    }
                }
                event = parser.next();
            }
        }

        if (programmeCount == 0) {
            throw new IllegalStateException("EPG contains no usable programmes in the current time window");
        }

        for (List<EpgEvent> list : events.values()) {
            list.sort(Comparator.comparingLong(e -> e.startMs));
        }
        return new ParsedEpg(events, aliases);
    }

    private static void parseChannelTag(XmlPullParser parser, Map<String, String> aliases) throws Exception {
        String id = value(parser.getAttributeValue(null, "id"));
        if (id.isEmpty()) return;

        registerAliases(aliases, id, id);
        int depth = parser.getDepth();
        while (true) {
            int inner = parser.next();
            if (inner == XmlPullParser.START_TAG && "display-name".equals(parser.getName())) {
                String displayName = parser.nextText();
                registerAliases(aliases, displayName, id);
            } else if (inner == XmlPullParser.END_TAG
                    && parser.getDepth() == depth
                    && "channel".equals(parser.getName())) {
                break;
            }
        }
    }

    private static void install(ParsedEpg parsed) {
        EVENTS_BY_ID.clear();
        EVENTS_BY_ID.putAll(parsed.eventsById);
        ALIAS_TO_ID.clear();
        ALIAS_TO_ID.putAll(parsed.aliasToId);
    }

    /**
     * Preferred lookup for the app UI. XMLTV feeds often use a canonical id such as
     * "bnt4.bg" while an M3U may use "BNT4" and display the channel as "БНТ 4".
     * We therefore try tvg-id, tvg-name and the visible M3U channel name against
     * both XMLTV ids and XMLTV display-name aliases.
     */
    public static List<EpgEvent> events(Channel channel) {
        if (channel == null) return Collections.emptyList();
        List<EpgEvent> found = findEvents(channel.tvgId);
        if (!found.isEmpty()) return found;
        found = findEvents(channel.tvgName);
        if (!found.isEmpty()) return found;
        return findEvents(channel.name);
    }

    // Kept for compatibility with any older call sites.
    public static List<EpgEvent> events(String tvgId) {
        return findEvents(tvgId);
    }

    public static EpgEvent current(Channel channel) {
        return currentFrom(events(channel));
    }

    public static EpgEvent next(Channel channel) {
        return nextFrom(events(channel));
    }

    public static EpgEvent current(String tvgId) {
        return currentFrom(findEvents(tvgId));
    }

    public static EpgEvent next(String tvgId) {
        return nextFrom(findEvents(tvgId));
    }

    public static boolean hasData() {
        return !EVENTS_BY_ID.isEmpty();
    }

    private static EpgEvent currentFrom(List<EpgEvent> list) {
        long now = System.currentTimeMillis();
        EpgEvent best = null;
        // XMLTV feeds can occasionally contain overlapping or malformed programme intervals.
        // If more than one event contains "now", the event that started most recently is
        // the only sensible current programme. This also prevents an old overlong event from
        // masking the real programme that started later in the day.
        for (EpgEvent e : list) {
            if (e.startMs <= now && e.stopMs > now) {
                if (best == null
                        || e.startMs > best.startMs
                        || (e.startMs == best.startMs && e.stopMs < best.stopMs)) {
                    best = e;
                }
            }
        }
        return best;
    }

    private static EpgEvent nextFrom(List<EpgEvent> list) {
        long now = System.currentTimeMillis();
        EpgEvent best = null;
        // Pick the earliest future start explicitly instead of relying on feed ordering.
        for (EpgEvent e : list) {
            if (e.startMs > now && (best == null || e.startMs < best.startMs)) best = e;
        }
        return best;
    }

    private static List<EpgEvent> findEvents(String rawKey) {
        if (rawKey == null || rawKey.trim().isEmpty()) return Collections.emptyList();

        // First allow a literal XMLTV channel id.
        List<EpgEvent> direct = EVENTS_BY_ID.get(rawKey.trim());
        if (direct != null && !direct.isEmpty()) return direct;

        for (String key : aliasKeys(rawKey)) {
            String id = ALIAS_TO_ID.get(key);
            if (id == null) continue;
            List<EpgEvent> list = EVENTS_BY_ID.get(id);
            if (list != null && !list.isEmpty()) return list;
        }
        return Collections.emptyList();
    }

    private static void registerAliases(Map<String, String> aliases, String alias, String id) {
        if (alias == null || id == null || alias.trim().isEmpty() || id.trim().isEmpty()) return;
        for (String key : aliasKeys(alias)) {
            // First declaration wins. This is safer than silently moving a common display-name
            // alias to a later unrelated channel.
            aliases.putIfAbsent(key, id.trim());
        }
    }

    private static Set<String> aliasKeys(String raw) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (raw == null) return keys;
        String value = value(raw);
        if (value.isEmpty()) return keys;

        addNormalizedKeys(keys, value);

        // Common XMLTV convention in this guide: ids like bnt4.bg, btv.bg, nova.bg.
        String withoutCountrySuffix = value.replaceFirst("(?i)\\.[a-z]{2,4}$", "");
        if (!withoutCountrySuffix.equals(value)) addNormalizedKeys(keys, withoutCountrySuffix);

        // Common playlist display-name variants should still resolve to the same guide channel.
        String withoutQuality = value.replaceFirst(
                "(?i)(?:[\\s._-]+)(?:UHD|FHD|HD|SD|LQ|4K)$", "");
        if (!withoutQuality.equals(value)) addNormalizedKeys(keys, withoutQuality);

        String both = withoutCountrySuffix.replaceFirst(
                "(?i)(?:[\\s._-]+)(?:UHD|FHD|HD|SD|LQ|4K)$", "");
        addNormalizedKeys(keys, both);

        return keys;
    }

    private static void addNormalizedKeys(Set<String> keys, String raw) {
        String normalized = normalize(raw);
        if (normalized.isEmpty()) return;
        keys.add(normalized);
        String compact = normalized.replace(" ", "");
        if (!compact.isEmpty()) keys.add(compact);
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('&', ' ')
                .trim();
        StringBuilder out = new StringBuilder(s.length());
        boolean pendingSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (pendingSpace && out.length() > 0) out.append(' ');
                out.append(c);
                pendingSpace = false;
            } else {
                pendingSpace = true;
            }
        }
        return out.toString().trim();
    }

    private static void writeParsedCacheQuietly(File file, ParsedEpg parsed) {
        try { writeParsedCache(file, parsed); } catch (Exception ignored) {}
    }

    private static void writeParsedCache(File file, ParsedEpg parsed) throws Exception {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        deleteQuietly(tmp);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(0x4E594550); // NYEP
            out.writeInt(1);
            out.writeInt(parsed.aliasToId.size());
            for (Map.Entry<String, String> entry : parsed.aliasToId.entrySet()) {
                writeString(out, entry.getKey());
                writeString(out, entry.getValue());
            }
            out.writeInt(parsed.eventsById.size());
            for (Map.Entry<String, List<EpgEvent>> entry : parsed.eventsById.entrySet()) {
                writeString(out, entry.getKey());
                List<EpgEvent> list = entry.getValue();
                out.writeInt(list.size());
                for (EpgEvent event : list) {
                    out.writeLong(event.startMs);
                    out.writeLong(event.stopMs);
                    writeString(out, event.title);
                    writeString(out, event.description);
                }
            }
        }
        if (file.exists() && !file.delete()) throw new IOException("Cannot replace parsed EPG cache");
        if (!tmp.renameTo(file)) throw new IOException("Cannot commit parsed EPG cache");
    }

    private static ParsedEpg readParsedCache(File file) throws Exception {
        Map<String, String> aliases = new HashMap<>();
        Map<String, List<EpgEvent>> events = new HashMap<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != 0x4E594550 || in.readInt() != 1) throw new IOException("Unsupported EPG cache");
            int aliasCount = in.readInt();
            if (aliasCount < 0 || aliasCount > 200000) throw new IOException("Invalid EPG alias count");
            for (int i = 0; i < aliasCount; i++) aliases.put(readString(in), readString(in));
            int channelCount = in.readInt();
            if (channelCount < 0 || channelCount > 100000) throw new IOException("Invalid EPG channel count");
            for (int i = 0; i < channelCount; i++) {
                String id = readString(in);
                int count = in.readInt();
                if (count < 0 || count > 10000) throw new IOException("Invalid EPG event count");
                List<EpgEvent> list = new ArrayList<>(count);
                for (int j = 0; j < count; j++) {
                    list.add(new EpgEvent(in.readLong(), in.readLong(), readString(in), readString(in)));
                }
                events.put(id, list);
            }
        }
        if (events.isEmpty()) throw new IOException("Parsed EPG cache is empty");
        return new ParsedEpg(events, aliases);
    }

    private static void writeString(DataOutputStream out, String value) throws Exception {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws Exception {
        int length = in.readInt();
        if (length < 0 || length > 4 * 1024 * 1024) throw new IOException("Invalid EPG string length");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static InputStream openMaybeGzip(File file) throws Exception {
        BufferedInputStream in = new BufferedInputStream(new FileInputStream(file));
        in.mark(2);
        int b1 = in.read();
        int b2 = in.read();
        in.reset();
        return (b1 == 0x1f && b2 == 0x8b) ? new GZIPInputStream(in) : in;
    }

    private static long parseXmlTvTime(String value) {
        if (value == null) return 0L;
        String v = value.trim();
        String[] patterns = {"yyyyMMddHHmmss Z", "yyyyMMddHHmmssZ", "yyyyMMddHHmmss"};
        for (String pattern : patterns) {
            SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.US);
            sdf.setLenient(true);
            if (!pattern.contains("Z")) sdf.setTimeZone(TimeZone.getDefault());
            ParsePosition pos = new ParsePosition(0);
            Date d = sdf.parse(v, pos);
            if (d != null && pos.getIndex() >= Math.min(v.length(), 14)) return d.getTime();
        }
        return 0L;
    }

    private static boolean isFullEpgShortUrl(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(Locale.ROOT);
        return u.equals("https://is.gd/fullepg") || u.equals("http://is.gd/fullepg");
    }

    private static void commit(File staging, File target) throws Exception {
        if (!staging.exists() || staging.length() <= 0L) {
            throw new IllegalStateException("Downloaded EPG is empty");
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Cannot replace old EPG cache");
        }
        if (!staging.renameTo(target)) {
            throw new IllegalStateException("Cannot commit EPG cache");
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) //noinspection ResultOfMethodCallIgnored
            file.delete();
    }

    private static String value(String s) {
        return s == null ? "" : s.trim();
    }

    private static final class ParsedEpg {
        final Map<String, List<EpgEvent>> eventsById;
        final Map<String, String> aliasToId;

        ParsedEpg(Map<String, List<EpgEvent>> eventsById, Map<String, String> aliasToId) {
            this.eventsById = eventsById;
            this.aliasToId = aliasToId;
        }
    }
}
