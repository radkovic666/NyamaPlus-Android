package fun.nyama.tv;

import java.util.Locale;

/** Plain text only: source HTML is never rendered or allowed to load external resources. */
final class TvEpisodeText {
    private TvEpisodeText() { }

    static String plainText(String html) {
        if (html == null || html.isEmpty()) return "";
        StringBuilder text = new StringBuilder(html.length());
        for (int at = 0; at < html.length();) {
            char value = html.charAt(at);
            if (html.startsWith("<!--", at)) {
                int end = html.indexOf("-->", at + 4);
                at = end < 0 ? html.length() : end + 3;
                continue;
            }
            if (value == '<') {
                int nameAt = at + 1;
                boolean closing = nameAt < html.length() && html.charAt(nameAt) == '/';
                if (closing) nameAt++;
                int nameEnd = nameAt;
                while (nameEnd < html.length() && Character.isLetterOrDigit(html.charAt(nameEnd))) nameEnd++;
                if (nameEnd > nameAt && Character.isLetter(html.charAt(nameAt))
                        && (nameEnd == html.length() || Character.isWhitespace(html.charAt(nameEnd))
                        || html.charAt(nameEnd) == '/' || html.charAt(nameEnd) == '>')) {
                    String name = html.substring(nameAt, nameEnd).toLowerCase(Locale.ROOT);
                    int end = tagEnd(html, nameEnd);
                    if (!closing && unsafe(name)) {
                        // Missing closing tags must not expose executable/style/object contents as prose.
                        at = end < 0 ? html.length() : !name.equals("script") && !name.equals("style") && html.charAt(end - 1) == '/'
                                ? end + 1 : skipUnsafe(html, end + 1, name);
                        continue;
                    }
                    if (end >= 0) {
                        if (name.equals("br") || name.equals("hr")) text.append('\n');
                        else if (block(name)) text.append("\n\n");
                        at = end + 1;
                        continue;
                    }
                } else if (nameAt < html.length() && (html.charAt(nameAt) == '!' || html.charAt(nameAt) == '?')) {
                    int end = tagEnd(html, nameAt + 1);
                    at = end < 0 ? html.length() : end + 1;
                    continue;
                }
            }
            if (value == '&') {
                int end = html.indexOf(';', at + 1);
                if (end > at && end - at <= 12) {
                    String entity = entity(html.substring(at + 1, end));
                    if (entity != null) {
                        text.append(entity);
                        at = end + 1;
                        continue;
                    }
                }
            }
            if (!Character.isISOControl(value) || value == '\n' || value == '\r' || value == '\t')
                text.append(value);
            at++;
        }
        return text.toString().replace("\r\n", "\n").replace('\r', '\n')
                .replace('\u2028', '\n').replace('\u2029', '\n')
                .replaceAll("[\\t\\x0B\\f \\u00a0]+", " ")
                .replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static int tagEnd(String html, int at) {
        char quote = 0;
        for (int i = at; i < html.length(); i++) {
            char next = html.charAt(i);
            if (quote != 0) { if (next == quote) quote = 0; }
            else if (next == '\'' || next == '"') quote = next;
            else if (next == '>') return i;
        }
        return -1;
    }

    private static int skipUnsafe(String html, int at, String name) {
        int depth = 1;
        boolean rawText = name.equals("script") || name.equals("style");
        for (int next = at; next < html.length(); next++) {
            if (html.charAt(next) != '<') continue;
            int nameAt = next + 1;
            boolean closing = nameAt < html.length() && html.charAt(nameAt) == '/';
            if (closing) nameAt++;
            int endName = nameAt + name.length();
            if (endName >= html.length() || !html.regionMatches(true, nameAt, name, 0, name.length())) continue;
            char boundary = html.charAt(endName);
            if (boundary != '>' && boundary != '/' && !Character.isWhitespace(boundary)) continue;
            if (rawText && !closing) continue;
            int end = tagEnd(html, endName);
            if (end < 0) return html.length();
            if (closing && --depth == 0) return end + 1;
            if (!closing && html.charAt(end - 1) != '/') depth++;
            next = end;
        }
        return html.length();
    }

    private static boolean unsafe(String name) {
        return name.equals("script") || name.equals("style") || name.equals("object")
                || name.equals("iframe") || name.equals("svg") || name.equals("math")
                || name.equals("template") || name.equals("noscript");
    }

    private static boolean block(String name) {
        return name.equals("p") || name.equals("div") || name.equals("li")
                || name.equals("ul") || name.equals("ol") || name.equals("section")
                || name.equals("blockquote") || name.equals("h1") || name.equals("h2")
                || name.equals("h3") || name.equals("h4") || name.equals("h5") || name.equals("h6");
    }

    private static String entity(String name) {
        switch (name) {
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": return "\"";
            case "apos": return "'";
            case "nbsp": return " ";
            case "ndash": return "\u2013";
            case "mdash": return "\u2014";
            case "lsquo": return "\u2018";
            case "rsquo": return "\u2019";
            case "ldquo": return "\u201c";
            case "rdquo": return "\u201d";
            case "hellip": return "\u2026";
            case "bull": return "\u2022";
            case "copy": return "\u00a9";
            default:
                if (!name.startsWith("#")) return null;
                try {
                    boolean hex = name.startsWith("#x") || name.startsWith("#X");
                    int value = Integer.parseInt(name.substring(hex ? 2 : 1), hex ? 16 : 10);
                    if (!Character.isValidCodePoint(value) || value >= 0xD800 && value <= 0xDFFF
                            || value == 0 || Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t')
                        return "";
                    return new String(Character.toChars(value));
                } catch (IllegalArgumentException invalid) { return null; }
        }
    }
}
