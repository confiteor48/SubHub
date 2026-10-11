package com.subhub.app.settings;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Saved looks are shared by editors, while applying one affects only the explicit target store. */
public final class CensorLookStore {
    private static final String STORE = "subhub_censor_looks";
    private static final String KEY = "looks_v1";
    public static final int MAX_SAVED = 32;
    private final SharedPreferences storage;
    public CensorLookStore(Context context) {
        storage = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    public List<CensorLook> saved() {
        String raw = storage.getString(KEY, "{\"version\":1,\"looks\":[]}");
        if (raw == null || raw.length() > 512_000) throw new IllegalStateException("Saved presets could not be read");
        try {
            JSONObject root = new JSONObject(raw);
            if (root.getInt("version") != 1) throw new JSONException("Unsupported preset version");
            JSONArray array = root.getJSONArray("looks");
            if (array.length() > MAX_SAVED) throw new JSONException("Too many saved presets");
            List<CensorLook> result = new ArrayList<>();
            java.util.Set<String> ids = new HashSet<>();
            for (int index = 0; index < array.length(); index++) {
                CensorLook look = CensorLook.decode(array.getJSONObject(index));
                if (!ids.add(look.id())) throw new JSONException("Duplicate preset identifier");
                result.add(look);
            }
            return Collections.unmodifiableList(result);
        } catch (JSONException invalid) {
            throw new IllegalStateException("Saved presets could not be read", invalid);
        }
    }

    public CensorLook save(String name, SharedPreferences source) {
        List<CensorLook> looks = new ArrayList<>(saved());
        if (looks.size() >= MAX_SAVED) throw new IllegalArgumentException("You can save up to 32 presets");
        uniqueName(looks, name, null);
        CensorLook look = new CensorLook(UUID.randomUUID().toString(), name, source.getAll());
        looks.add(look);
        write(looks);
        return look;
    }

    public void rename(String id, String name) {
        List<CensorLook> looks = new ArrayList<>(saved());
        uniqueName(looks, name, id);
        for (int index = 0; index < looks.size(); index++) if (looks.get(index).id().equals(id)) {
            looks.set(index, looks.get(index).renamed(name));
            write(looks);
            return;
        }
        throw new IllegalArgumentException("Preset no longer exists");
    }

    public void update(String id, SharedPreferences source) {
        List<CensorLook> looks = new ArrayList<>(saved());
        for (int index = 0; index < looks.size(); index++) if (looks.get(index).id().equals(id)) {
            looks.set(index, new CensorLook(id, looks.get(index).name(), source.getAll()));
            write(looks);
            return;
        }
        throw new IllegalArgumentException("Preset no longer exists");
    }

    public void delete(String id) {
        List<CensorLook> looks = new ArrayList<>(saved());
        if (!looks.removeIf(look -> look.id().equals(id))) return;
        write(looks);
    }

    private static void uniqueName(List<CensorLook> looks, String name, String except) {
        String clean = name == null ? "" : name.trim();
        for (CensorLook look : looks) if (!look.id().equals(except) && look.name().equalsIgnoreCase(clean))
            throw new IllegalArgumentException("That preset name is already in use");
    }

    private void write(List<CensorLook> looks) {
        try {
            JSONArray array = new JSONArray();
            for (CensorLook look : looks) array.put(look.encode());
            String raw = new JSONObject().put("version", 1).put("looks", array).toString();
            if (raw.length() > 512_000) throw new IllegalArgumentException("Saved presets are too large");
            if (!storage.edit().putString(KEY, raw).commit())
                throw new IllegalStateException("Preset could not be saved");
        } catch (JSONException invalid) { throw new IllegalStateException("Preset could not be saved", invalid); }
    }

    public static void apply(CensorLook look, SharedPreferences destination) {
        SharedPreferences.Editor editor = destination.edit();
        // Built-ins change their recipe only. Personal phrases and other styles' palettes survive.
        // Named snapshots deliberately restore the exact appearance captured by their owner.
        if (!look.id().startsWith("builtin:"))
            for (String key : CensorLook.keys()) editor.remove(key);
        for (Map.Entry<String, Object> entry : look.values().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof java.util.Set<?>) {
                java.util.Set<String> set = new LinkedHashSet<>();
                for (Object item : (java.util.Set<?>) value) set.add((String) item);
                editor.putStringSet(entry.getKey(), set);
            }
        }
        if (!editor.commit()) throw new IllegalStateException("Preset could not be applied");
    }
}
