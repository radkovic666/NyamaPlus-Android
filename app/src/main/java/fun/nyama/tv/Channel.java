package fun.nyama.tv;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Channel {
    public final int number;
    public final String name;
    public final String tvgId;
    public final String tvgName;
    public final String logo;
    public final String group;
    public final String url;
    public final Map<String, String> attributes;
    public final Map<String, String> streamHeaders;

    public Channel(int number, String name, String tvgId, String tvgName, String logo,
                   String group, String url, Map<String, String> attributes,
                   Map<String, String> streamHeaders) {
        this.number = number;
        this.name = name == null || name.trim().isEmpty() ? "Channel " + number : name.trim();
        this.tvgId = clean(tvgId);
        this.tvgName = clean(tvgName);
        this.logo = clean(logo);
        this.group = clean(group);
        this.url = clean(url);
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes == null ? Collections.emptyMap() : attributes));
        this.streamHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(streamHeaders == null ? Collections.emptyMap() : streamHeaders));
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    public String stableKey() {
        if (!tvgId.isEmpty()) return "id:" + tvgId;
        return "name:" + name.toLowerCase();
    }
}
