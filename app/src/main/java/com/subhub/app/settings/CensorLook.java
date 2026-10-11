package com.subhub.app.settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** A bounded appearance-only snapshot; detector and account settings are never accepted. */
public final class CensorLook {
    public static final int MAX_NAME = 40;
    private static final Map<String, Class<?>> TYPES;
    static {
        Map<String, Class<?>> keys = new LinkedHashMap<>();
        for (String key : new String[]{"censor_type", "border_effect", "border_color",
                "border_gradient_start", "border_gradient_end", "reverse_cutout_shape",
                "error_popup_title", "error_popup_text"}) keys.put(key, String.class);
        keys.put("censor_intensity", Integer.class);
        keys.put("censor_size_padding", Float.class);
        keys.put("reverse_strength", Float.class);
        for (String key : new String[]{"show_border", "animate_border", "show_text", "reverse_mode"})
            keys.put(key, Boolean.class);
        keys.put("enabled_phrase_categories", Set.class);
        keys.put("custom_phrases", Set.class);
        for (String style : new String[]{"box", "pixelate", "blur", "custom", "static",
                "glitch", "tape", "error_popup"})
            for (int slot = 1; slot <= 3; slot++)
                keys.put("effect_palette_" + style + "_" + slot, String.class);
        TYPES = Collections.unmodifiableMap(keys);
    }

    private final String id;
    private final String name;
    private final Map<String, Object> values;

    public CensorLook(String id, String name, Map<String, ?> source) {
        if (id == null || id.isEmpty() || id.length() > 80)
            throw new IllegalArgumentException("Invalid preset identifier");
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > MAX_NAME || clean.contains("\n") || clean.contains("\r"))
            throw new IllegalArgumentException("Use a name of 1 to 40 characters");
        this.id = id;
        this.name = clean;
        Map<String, Object> accepted = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            Class<?> type = TYPES.get(entry.getKey());
            if (type == null) continue;
            Object value = entry.getValue();
            if (value == null || !type.isInstance(value))
                throw new IllegalArgumentException("Invalid appearance value: " + entry.getKey());
            validate(entry.getKey(), value);
            if (value instanceof Set<?>) value = Collections.unmodifiableSet(new LinkedHashSet<>((Set<?>) value));
            accepted.put(entry.getKey(), value);
        }
        this.values = Collections.unmodifiableMap(accepted);
    }

    public String id() { return id; }
    public String name() { return name; }
    public Map<String, Object> values() { return values; }
    public static Set<String> keys() { return TYPES.keySet(); }
    public CensorLook renamed(String name) { return new CensorLook(id, name, values); }

    private static void validate(String key, Object value) {
        if (value instanceof String) {
            String string = (String) value;
            if (string.length() > 500) throw new IllegalArgumentException("Appearance text is too long");
            if ((key.contains("color") || key.startsWith("effect_palette_")
                    || key.startsWith("border_gradient_")) && !string.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))
                throw new IllegalArgumentException("Invalid appearance color");
            if (key.equals("censor_type") && !java.util.Arrays.asList("box", "pixelate", "blur",
                    "custom", "static", "glitch", "tape", "error_popup", "lockpad").contains(string))
                throw new IllegalArgumentException("Unknown censor style");
            if (key.equals("border_effect") && !java.util.Arrays.asList("classic", "glow", "gradient", "rainbow").contains(string))
                throw new IllegalArgumentException("Unknown border effect");
            if (key.equals("reverse_cutout_shape") && !java.util.Arrays.asList("rectangle", "rounded", "ellipse").contains(string))
                throw new IllegalArgumentException("Unknown cutout shape");
        } else if (value instanceof Float) {
            float number = (Float) value;
            if (!Float.isFinite(number) || number < 0 || number > 1)
                throw new IllegalArgumentException("Appearance amount must be between zero and one");
        } else if (value instanceof Integer) {
            int number = (Integer) value;
            if (number < 0 || number > 100) throw new IllegalArgumentException("Invalid intensity");
        } else if (value instanceof Set<?>) {
            Set<?> set = (Set<?>) value;
            if (set.size() > 128) throw new IllegalArgumentException("Too many appearance phrases");
            for (Object item : set) if (!(item instanceof String) || ((String) item).length() > 500)
                throw new IllegalArgumentException("Invalid appearance phrase");
        }
    }

    public JSONObject encode() throws JSONException {
        JSONObject result = new JSONObject().put("id", id).put("name", name);
        JSONObject settings = new JSONObject();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            String type = value instanceof Set<?> ? "set" : value instanceof Float ? "float"
                    : value instanceof Integer ? "int" : value instanceof Boolean ? "boolean" : "string";
            if (value instanceof Set<?>) value = new JSONArray((Set<?>) value);
            settings.put(entry.getKey(), new JSONObject().put("type", type).put("value", value));
        }
        return result.put("settings", settings);
    }

    public static CensorLook decode(JSONObject encoded) throws JSONException {
        JSONObject settings = encoded.getJSONObject("settings");
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : TYPES.keySet()) {
            if (!settings.has(key)) continue;
            JSONObject entry = settings.getJSONObject(key);
            Object value = entry.get("value");
            String type = entry.getString("type");
            Class<?> expected = TYPES.get(key);
            if (expected == Set.class && type.equals("set") && value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                if (array.length() > 128) throw new JSONException("Too many preset phrases");
                Set<String> set = new LinkedHashSet<>();
                for (int index = 0; index < array.length(); index++) {
                    Object item = array.get(index);
                    if (!(item instanceof String)) throw new JSONException("Invalid preset phrase");
                    set.add((String) item);
                }
                value = set;
            } else if (expected == Float.class && type.equals("float") && value instanceof Number) {
                value = ((Number) value).floatValue();
            } else if (expected == Integer.class && type.equals("int") && value instanceof Number) {
                double number = ((Number) value).doubleValue();
                if (number != Math.rint(number) || number < 0 || number > 100)
                    throw new JSONException("Invalid preset intensity");
                value = (int) number;
            } else if (!(expected == Boolean.class && type.equals("boolean") && value instanceof Boolean)
                    && !(expected == String.class && type.equals("string") && value instanceof String)) {
                throw new JSONException("Invalid preset value type");
            }
            values.put(key, value);
        }
        try { return new CensorLook(encoded.getString("id"), encoded.getString("name"), values); }
        catch (IllegalArgumentException invalid) { throw new JSONException(invalid.getMessage()); }
    }
}
