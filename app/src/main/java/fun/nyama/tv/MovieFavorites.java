package fun.nyama.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MovieFavorites {
    private static final String PREFS = "nyama_plus_media_favorites";
    private static final String KEY = "ids";
    private final SharedPreferences prefs;

    public MovieFavorites(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isFavorite(String id) { return id != null && ids().contains(id); }

    public boolean toggle(String id) {
        if (id == null || id.trim().isEmpty()) return false;
        Set<String> set = ids();
        boolean added;
        if (set.contains(id)) { set.remove(id); added = false; }
        else { set.add(id); added = true; }
        prefs.edit().putStringSet(KEY, set).apply();
        return added;
    }

    public Set<String> ids() {
        Set<String> raw = prefs.getStringSet(KEY, Collections.emptySet());
        return raw == null ? new HashSet<>() : new HashSet<>(raw);
    }

    public String csv() {
        List<String> list = new ArrayList<>(ids());
        Collections.sort(list);
        StringBuilder sb = new StringBuilder();
        for (String id : list) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }
}
