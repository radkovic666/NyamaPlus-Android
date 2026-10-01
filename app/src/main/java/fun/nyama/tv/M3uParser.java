package fun.nyama.tv;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class M3uParser {
    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Za-z0-9_-]+)=\\\"([^\\\"]*)\\\"");
    private static final String VLC_UA = "#EXTVLCOPT:http-user-agent=";
    private static final String VLC_REF = "#EXTVLCOPT:http-referrer=";
    private static final String VLC_REF2 = "#EXTVLCOPT:http-reconnect=";

    private M3uParser() {}

    public static List<Channel> parse(File file) throws Exception {
        List<Channel> result = new ArrayList<>();
        if (file == null || !file.exists()) return result;

        String displayName = null;
        String extGroup = "";
        Map<String, String> attrs = new LinkedHashMap<>();
        Map<String, String> pendingHeaders = new LinkedHashMap<>();
        boolean awaitingUrl = false;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.replace("\uFEFF", "").trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("#EXTINF")) {
                    awaitingUrl = true;
                    attrs = attributes(line);
                    pendingHeaders = new LinkedHashMap<>();
                    extGroup = "";
                    int comma = findMetadataComma(line);
                    displayName = comma >= 0 && comma + 1 < line.length()
                            ? line.substring(comma + 1).trim()
                            : firstNonEmpty(attrs.get("tvg-name"), attrs.get("tvg-id"));
                } else if (awaitingUrl && line.regionMatches(true, 0, "#EXTGRP:", 0, 8)) {
                    extGroup = line.substring(8).trim();
                } else if (awaitingUrl && line.regionMatches(true, 0, VLC_UA, 0, VLC_UA.length())) {
                    pendingHeaders.put("User-Agent", line.substring(VLC_UA.length()).trim());
                } else if (awaitingUrl && line.regionMatches(true, 0, VLC_REF, 0, VLC_REF.length())) {
                    pendingHeaders.put("Referer", line.substring(VLC_REF.length()).trim());
                } else if (awaitingUrl && line.regionMatches(true, 0, "#EXTHTTP:", 0, 9)) {
                    parseExtHttp(line.substring(9).trim(), pendingHeaders);
                } else if (awaitingUrl && line.regionMatches(true, 0, VLC_REF2, 0, VLC_REF2.length())) {
                    // Recognized but not needed by ExoPlayer. Retain it in metadata for future compatibility.
                    attrs.put("vlc-http-reconnect", line.substring(VLC_REF2.length()).trim());
                } else if (!line.startsWith("#") && awaitingUrl) {
                    ParsedUrl parsed = parseUrlAndPipeHeaders(line);
                    if (!parsed.url.isEmpty()) {
                        pendingHeaders.putAll(parsed.headers);
                        String group = firstNonEmpty(attrs.get("group-title"), extGroup);
                        result.add(new Channel(
                                result.size() + 1,
                                displayName,
                                attrs.get("tvg-id"),
                                attrs.get("tvg-name"),
                                attrs.get("tvg-logo"),
                                group,
                                parsed.url,
                                attrs,
                                pendingHeaders));
                    }
                    awaitingUrl = false;
                    displayName = null;
                    extGroup = "";
                    attrs = new LinkedHashMap<>();
                    pendingHeaders = new LinkedHashMap<>();
                }
            }
        }
        return result;
    }

    private static Map<String, String> attributes(String line) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher m = ATTRIBUTE.matcher(line);
        while (m.find()) result.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
        return result;
    }

    private static int findMetadataComma(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ',' && !quoted) return i;
        }
        return -1;
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        return b == null ? "" : b.trim();
    }

    private static ParsedUrl parseUrlAndPipeHeaders(String raw) {
        String url = raw.trim();
        Map<String, String> headers = new LinkedHashMap<>();
        int pipe = url.indexOf('|');
        if (pipe <= 0) return new ParsedUrl(url, headers);

        String suffix = url.substring(pipe + 1);
        url = url.substring(0, pipe).trim();
        for (String pair : suffix.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                String key = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                String value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                if (!key.trim().isEmpty()) headers.put(normalizeHeader(key.trim()), value.trim());
            } catch (Exception ignored) {}
        }
        return new ParsedUrl(url, headers);
    }

    private static void parseExtHttp(String jsonish, Map<String, String> headers) {
        // Common #EXTHTTP form is a tiny JSON object such as {"Referer":"...","User-Agent":"..."}.
        Matcher m = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(jsonish);
        while (m.find()) headers.put(normalizeHeader(m.group(1)), m.group(2));
    }

    private static String normalizeHeader(String key) {
        if ("user-agent".equalsIgnoreCase(key)) return "User-Agent";
        if ("referrer".equalsIgnoreCase(key) || "referer".equalsIgnoreCase(key)) return "Referer";
        if ("origin".equalsIgnoreCase(key)) return "Origin";
        if ("cookie".equalsIgnoreCase(key)) return "Cookie";
        return key;
    }

    private static final class ParsedUrl {
        final String url;
        final Map<String, String> headers;
        ParsedUrl(String url, Map<String, String> headers) { this.url = url; this.headers = headers; }
    }
}
