package fun.nyama.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, plain-text SRT/WebVTT import. File bytes never go to a subtitle service. */
final class TvSubtitleFile {
    static final int MAX_BYTES = 1024 * 1024;
    static final int MAX_CUES = 6000;
    private static final Pattern TIMING = Pattern.compile(
            "^((?:[0-9]{1,3}:)?[0-9]{2}:[0-9]{2}[.,][0-9]{3})\\s+-->\\s+((?:[0-9]{1,3}:)?[0-9]{2}:[0-9]{2}[.,][0-9]{3})(?:\\s+.*)?$");
    final String id, label, language;
    final JSONArray cues;

    private TvSubtitleFile(String id, String label, String language, JSONArray cues) {
        this.id = id; this.label = label; this.language = language; this.cues = cues;
    }

    static TvSubtitleFile read(InputStream input, String name, String language) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted() || output.size() + count > MAX_BYTES) throw new IOException("subtitle_size");
            output.write(buffer, 0, count);
        }
        return parse(output.toByteArray(), name, language);
    }

    static TvSubtitleFile parse(byte[] bytes, String name, String language) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("subtitle_size");
        String decoded;
        try {
            if (bytes.length >= 2 && ((bytes[0] & 255) == 255 && (bytes[1] & 255) == 254))
                decoded = decode(bytes, StandardCharsets.UTF_16LE).substring(1);
            else if (bytes.length >= 2 && ((bytes[0] & 255) == 254 && (bytes[1] & 255) == 255))
                decoded = decode(bytes, StandardCharsets.UTF_16BE).substring(1);
            else {
                try { decoded = decode(bytes, StandardCharsets.UTF_8); }
                catch (CharacterCodingException legacyBulgarian) { decoded = decode(bytes, Charset.forName("windows-1251")); }
                if (decoded.startsWith("\uFEFF")) decoded = decoded.substring(1);
            }
        } catch (CharacterCodingException invalid) { throw new IOException("subtitle_encoding"); }
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') throw new IOException("subtitle_binary");
        }
        String[] blocks = decoded.replace("\r\n", "\n").replace('\r', '\n').trim().split("\\n[ \\t]*\\n+");
        JSONArray cues = new JSONArray(); double previous = -1;
        try {
            for (String block : blocks) {
                String value = block.trim();
                if (value.startsWith("WEBVTT") || value.startsWith("NOTE") || value.startsWith("STYLE") || value.startsWith("REGION")) continue;
                String[] lines = value.split("\n"); int timing = 0;
                if (!lines[0].contains("-->")) timing = 1;
                if (timing >= lines.length) throw new IOException("subtitle_format");
                Matcher match = TIMING.matcher(lines[timing].trim());
                if (!match.matches()) throw new IOException("subtitle_format");
                double start = seconds(match.group(1)), end = seconds(match.group(2));
                if (start < previous || end <= start || end > 72 * 3600) throw new IOException("subtitle_timing");
                previous = start;
                StringBuilder text = new StringBuilder();
                for (int line = timing + 1; line < lines.length; line++) { if (text.length() > 0) text.append('\n'); text.append(lines[line]); }
                String plain = TvEpisodeText.plainText(text.toString().replaceAll("\\{\\\\[^}]*}", ""));
                if (plain.length() > 8192 || cues.length() >= MAX_CUES) throw new IOException("subtitle_size");
                if (!plain.isEmpty()) cues.put(new JSONArray().put(start).put(end).put(plain));
            }
            if (cues.length() == 0) throw new IOException("subtitle_empty");
            return new TvSubtitleFile(hash(bytes), clean(name, 100), "en".equals(language) ? "en" : "bg", cues);
        } catch (JSONException invalid) { throw new IOException("subtitle_format"); }
    }

    private static String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static double seconds(String value) throws IOException {
        String[] parts = value.replace(',', '.').split(":");
        double last = Double.parseDouble(parts[parts.length - 1]);
        int minutes = Integer.parseInt(parts[parts.length - 2]);
        if (minutes >= 60 || last >= 60) throw new IOException("subtitle_timing");
        return (parts.length == 3 ? Integer.parseInt(parts[0]) * 3600 : 0) + minutes * 60 + last;
    }

    JSONObject json() {
        try { return new JSONObject().put("id", id).put("label", label).put("language", language).put("cues", cues); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    static TvSubtitleFile cached(String raw) throws IOException {
        try {
            JSONObject object = new JSONObject(raw);
            String id = object.getString("id"), language = object.getString("language");
            JSONArray cues = object.getJSONArray("cues");
            if (!id.matches("[0-9a-f]{64}") || cues.length() == 0 || cues.length() > MAX_CUES) throw new IOException("subtitle_cache");
            return new TvSubtitleFile(id, clean(object.optString("label"), 100), "en".equals(language) ? "en" : "bg", cues);
        } catch (JSONException invalid) { throw new IOException("subtitle_cache"); }
    }

    static String clean(String value, int max) {
        if (value == null) return "";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < value.length() && text.length() < max; i++) {
            char c = value.charAt(i);
            if (!Character.isISOControl(c) && Character.getType(c) != Character.FORMAT) text.append(c);
        }
        return text.toString().trim();
    }

    static String hash(String text) { return hash(text.getBytes(StandardCharsets.UTF_8)); }
    static String hash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
