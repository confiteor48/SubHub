package com.subhub.app.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Original combinations of the existing effects. No detector options belong to a visual look. */
public final class CensorLookCatalog {
    private static final List<CensorLook> LOOKS;
    static {
        List<CensorLook> looks = new ArrayList<>();
        looks.add(look("blackout", "Blackout", "box", 100, .20f, false, "classic", false,
                "#160D1D", "#9860BE", "#EEE4F4"));
        looks.add(look("plum-glow", "Plum glow", "box", 100, .25f, true, "glow", true,
                "#22112F", "#C58DE7", "#EEE4F4"));
        looks.add(look("strict-denial", "Strict denial", "box", 100, .30f, true, "classic", true,
                "#0F0815", "#B278CB", "#EEE4F4"));
        looks.add(look("soft-blur", "Soft blur", "blur", 35, .20f, false, "classic", false,
                "#35203F", "#9860BE", "#EEE4F4"));
        looks.add(look("deep-blur", "Deep blur", "blur", 90, .30f, false, "classic", false,
                "#35203F", "#9860BE", "#EEE4F4"));
        looks.add(look("coarse-pixels", "Coarse pixels", "pixelate", 90, .20f, true, "classic", false,
                "#160D1D", "#9B6EA8", "#EEE4F4"));
        looks.add(look("neon-pixels", "Neon pixels", "pixelate", 65, .25f, true, "gradient", true,
                "#160D1D", "#C58DE7", "#55C6D0"));
        looks.add(look("plum-static", "Plum static", "static", 75, .25f, true, "classic", true,
                "#160D1D", "#9B6EA8", "#EEE4F4"));
        looks.add(look("signal-static", "Signal static", "static", 90, .20f, false, "classic", false,
                "#16121A", "#DDD5E2", "#EEE4F4"));
        looks.add(look("digital-glitch", "Digital glitch", "glitch", 80, .25f, true, "gradient", true,
                "#55C6D0", "#CB5C99", "#E9C5EF"));
        looks.add(look("denial-tape", "Denial tape", "tape", 80, .25f, false, "classic", true,
                "#211126", "#A960BA", "#E4C3ED"));
        looks.add(look("warning-tape", "Warning tape", "tape", 90, .30f, false, "classic", true,
                "#17121D", "#D86A80", "#DEC178"));
        looks.add(look("lockpad", "Lockpad", "error_popup", 75, .20f, false, "classic", true,
                "#2F1C39", "#B78BC5", "#E4C8EE"));
        looks.add(look("rose-lockpad", "Rose lockpad", "error_popup", 75, .25f, false, "classic", true,
                "#321724", "#C886A1", "#EDCCD9"));
        looks.add(look("mono-lockpad", "Monochrome lockpad", "error_popup", 75, .20f, false, "classic", false,
                "#24222A", "#A4A0AE", "#E0DDE7"));
        LOOKS = Collections.unmodifiableList(looks);
    }
    private CensorLookCatalog() { }
    public static List<CensorLook> all() { return LOOKS; }

    private static CensorLook look(String id, String name, String style, int intensity, float padding,
                                   boolean border, String borderEffect, boolean text,
                                   String first, String second, String third) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("censor_type", style);
        values.put("censor_intensity", intensity);
        values.put("censor_size_padding", padding);
        values.put("show_border", border);
        values.put("border_effect", borderEffect);
        values.put("animate_border", border && (borderEffect.equals("glow") || borderEffect.equals("gradient")));
        values.put("show_text", text);
        values.put("border_color", second);
        values.put("border_gradient_start", second);
        values.put("border_gradient_end", third);
        values.put("reverse_mode", false);
        values.put("reverse_strength", 1f);
        values.put("reverse_cutout_shape", "rectangle");
        values.put("enabled_phrase_categories", new LinkedHashSet<>(Arrays.asList("denial")));
        values.put("effect_palette_" + style + "_1", first);
        values.put("effect_palette_" + style + "_2", second);
        values.put("effect_palette_" + style + "_3", third);
        return new CensorLook("builtin:" + id, name, values);
    }
}
