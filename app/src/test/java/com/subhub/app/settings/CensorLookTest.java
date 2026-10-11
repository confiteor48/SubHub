package com.subhub.app.settings;

import static org.junit.Assert.*;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class CensorLookTest {
    @Test public void snapshotsExcludeDetectionCoverageAccountsAndAssets() {
        Map<String, Object> source = new HashMap<>();
        source.put("censor_type", "error_popup");
        source.put("detection_quality", "high");
        source.put("enabled_categories", new HashSet<String>());
        source.put("censor_coverage", "whole_person");
        source.put("controller_pin_hash", "private");
        source.put("custom_image_uris", "private");
        CensorLook look = new CensorLook("id", "Private look", source);
        assertEquals(1, look.values().size());
        assertEquals("error_popup", look.values().get("censor_type"));
    }

    @Test public void snapshotsCopyMutableSetsAndRoundTripExactTypes() throws Exception {
        Set<String> phrases = new HashSet<>(); phrases.add("WAIT");
        Map<String, Object> source = new HashMap<>();
        source.put("custom_phrases", phrases);
        source.put("censor_size_padding", .37f);
        source.put("censor_intensity", 86);
        source.put("show_text", false);
        source.put("effect_palette_error_popup_2", "#C58DE7");
        CensorLook look = new CensorLook("id", "  Named look  ", source);
        phrases.add("CHANGED");
        CensorLook decoded = CensorLook.decode(look.encode());
        assertEquals("Named look", decoded.name());
        assertEquals(look.values(), decoded.values());
        assertEquals(1, ((Set<?>) decoded.values().get("custom_phrases")).size());
        assertTrue(decoded.values().get("censor_size_padding") instanceof Float);
        assertTrue(decoded.values().get("censor_intensity") instanceof Integer);
    }

    @Test public void malformedKnownValuesFailInsteadOfWeakeningSettings() throws Exception {
        for (Object value : new Object[]{Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.1f, "0.5"}) {
            Map<String, Object> source = new HashMap<>(); source.put("censor_size_padding", value);
            assertThrows(IllegalArgumentException.class, () -> new CensorLook("id", "Name", source));
        }
        CensorLook look = CensorLookCatalog.all().get(0);
        JSONObject fractional = look.encode();
        fractional.getJSONObject("settings").getJSONObject("censor_intensity").put("value", 42.5);
        assertThrows(JSONException.class, () -> CensorLook.decode(fractional));
        JSONObject invalid = look.encode();
        invalid.getJSONObject("settings").getJSONObject("show_text").put("value", "false");
        assertThrows(JSONException.class, () -> CensorLook.decode(invalid));
    }

    @Test public void catalogUsesOnlyCurrentStylesAndMeaningfulDistinctLooks() throws Exception {
        Set<String> ids = new HashSet<>(); Set<Map<String, Object>> combinations = new HashSet<>();
        Set<String> styles = new HashSet<>();
        for (CensorLook look : CensorLookCatalog.all()) {
            assertTrue(ids.add(look.id())); assertTrue(combinations.add(look.values()));
            styles.add((String) look.values().get("censor_type"));
            assertEquals(look.values(), CensorLook.decode(look.encode()).values());
            assertFalse(look.values().containsKey("detection_quality"));
        }
        assertEquals(15, ids.size());
        assertEquals(new HashSet<>(java.util.Arrays.asList("box", "pixelate", "blur",
                "static", "glitch", "tape", "error_popup")), styles);
        assertEquals(CensorAppearance.Type.ERROR_POPUP, CensorAppearance.Type.fromPreference("lockpad"));
        assertEquals(CensorAppearance.Type.ERROR_POPUP, CensorAppearance.Type.fromPreference("error_popup"));
    }
}
