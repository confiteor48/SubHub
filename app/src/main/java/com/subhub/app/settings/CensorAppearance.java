package com.subhub.app.settings;

import android.graphics.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Immutable visual configuration shared by live overlays and image export. */
public final class CensorAppearance {
    public enum Type {
        BOX("box"), PIXELATE("pixelate"), BLUR("blur"), CUSTOM("custom"),
        STATIC("static"), GLITCH("glitch"), TAPE("tape"),
        ERROR_POPUP("error_popup");

        private final String preferenceValue;

        Type(String preferenceValue) { this.preferenceValue = preferenceValue; }
        public String getPreferenceValue() { return preferenceValue; }

        public static Type fromPreference(String value) {
            if (value == null) return BOX;
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if ("lockpad".equals(normalized)) return ERROR_POPUP;
            for (Type type : values()) {
                if (type.preferenceValue.equals(normalized)) return type;
            }
            return BOX;
        }
    }

    public enum BorderEffect {
        CLASSIC, GLOW, GRADIENT, RAINBOW;

        public static BorderEffect fromPreference(String value) {
            if (value != null) {
                try {
                    return valueOf(value.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // Use classic for unknown imported values.
                }
            }
            return CLASSIC;
        }

        public String preferenceValue() { return name().toLowerCase(Locale.ROOT); }
    }

    private final Type type;
    private final int intensity;
    private final float sizePadding;
    private final boolean showBorder;
    private final boolean animateBorder;
    private final BorderEffect borderEffect;
    private final boolean showText;
    private final int borderColor;
    private final int gradientStart;
    private final int gradientEnd;
    private final EffectPalette effectPalette;
    private final List<String> phrases;
    private final boolean reverseMode;
    private final int reverseStrength;
    private final String reverseCutoutShape;
    private final String errorTitle;
    private final String errorMessage;

    public CensorAppearance(
            Type type,
            int intensity,
            boolean showBorder,
            boolean showText,
            int borderColor) {
        this(type, intensity, 0.20f, showBorder, false, BorderEffect.CLASSIC,
                showText, borderColor, EffectPalette.defaultsFor(type),
                Collections.singletonList("BLOCKED"), false, 100,
                "rectangle", "SubHub", "Access blocked.");
    }

    public CensorAppearance(
            Type type,
            int intensity,
            float sizePadding,
            boolean showBorder,
            boolean animateBorder,
            BorderEffect borderEffect,
            boolean showText,
            int borderColor,
            List<String> phrases,
            boolean reverseMode,
            int reverseStrength,
            String reverseCutoutShape,
            String errorTitle,
            String errorMessage) {
        this(type, intensity, sizePadding, showBorder, animateBorder, borderEffect, showText,
                borderColor, EffectPalette.defaultsFor(type), phrases, reverseMode,
                reverseStrength, reverseCutoutShape, errorTitle, errorMessage);
    }

    public CensorAppearance(
            Type type,
            int intensity,
            float sizePadding,
            boolean showBorder,
            boolean animateBorder,
            BorderEffect borderEffect,
            boolean showText,
            int borderColor,
            EffectPalette effectPalette,
            List<String> phrases,
            boolean reverseMode,
            int reverseStrength,
            String reverseCutoutShape,
            String errorTitle,
            String errorMessage) {
        this(type, intensity, sizePadding, showBorder, animateBorder, borderEffect, showText,
                borderColor, effectPalette, phrases, reverseMode, reverseStrength, reverseCutoutShape,
                errorTitle, errorMessage, borderColor, 0xff4cd8eb);
    }

    public CensorAppearance(Type type, int intensity, float sizePadding, boolean showBorder,
            boolean animateBorder, BorderEffect borderEffect, boolean showText, int borderColor,
            EffectPalette effectPalette, List<String> phrases, boolean reverseMode, int reverseStrength,
            String reverseCutoutShape, String errorTitle, String errorMessage,
            int gradientStart, int gradientEnd) {
        this.type = type == null ? Type.BOX : type;
        this.intensity = clamp(intensity, 0, 100);
        this.sizePadding = Math.max(0f, Math.min(1f, sizePadding));
        this.showBorder = showBorder;
        this.animateBorder = animateBorder;
        this.borderEffect = borderEffect == null ? BorderEffect.CLASSIC : borderEffect;
        this.showText = showText;
        this.borderColor = borderColor;
        this.gradientStart = gradientStart;
        this.gradientEnd = gradientEnd;
        this.effectPalette = effectPalette == null
                ? EffectPalette.defaultsFor(this.type) : effectPalette;
        List<String> safePhrases = phrases == null ? Collections.emptyList() : phrases;
        this.phrases = Collections.unmodifiableList(new ArrayList<>(safePhrases));
        this.reverseMode = reverseMode;
        this.reverseStrength = clamp(reverseStrength, 1, 100);
        this.reverseCutoutShape = normalizeShape(reverseCutoutShape);
        this.errorTitle = emptyFallback(errorTitle, "SubHub");
        this.errorMessage = emptyFallback(errorMessage, "Access blocked.");
    }

    public static CensorAppearance defaults() {
        return new CensorAppearance(Type.BOX, 50, true, true, Color.rgb(255, 0, 128));
    }

    public Type getType() { return type; }
    public int getIntensity() { return intensity; }
    public float getSizePadding() { return sizePadding; }
    public boolean isShowBorder() { return showBorder; }
    public boolean isAnimateBorder() { return animateBorder; }
    public BorderEffect getBorderEffect() { return borderEffect; }
    public boolean isShowText() { return showText; }
    public int getBorderColor() { return borderColor; }
    public int getGradientStart() { return gradientStart; }
    public int getGradientEnd() { return gradientEnd; }
    public EffectPalette getEffectPalette() { return effectPalette; }
    public List<String> getPhrases() { return phrases; }
    public boolean isReverseMode() { return reverseMode; }
    public int getReverseStrength() { return reverseStrength; }
    public String getReverseCutoutShape() { return reverseCutoutShape; }
    public String getErrorTitle() { return errorTitle; }
    public String getErrorMessage() { return errorMessage; }

    /** Whether this effect needs a retained screenshot after inference has completed. */
    public boolean requiresSourceFrame() {
        Type effectiveType = type;
        if (reverseMode && (type == Type.BOX || type == Type.CUSTOM)) {
            effectiveType = Type.PIXELATE;
        }
        return effectiveType == Type.PIXELATE
                || effectiveType == Type.BLUR
                || effectiveType == Type.GLITCH;
    }

    public String phraseFor(int stableId) {
        if (phrases.isEmpty()) return "BLOCKED";
        return phrases.get(Math.floorMod(stableId, phrases.size()));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String normalizeShape(String value) {
        if ("rounded".equalsIgnoreCase(value) || "ellipse".equalsIgnoreCase(value)) {
            return value.toLowerCase(Locale.ROOT);
        }
        return "rectangle";
    }

    private static String emptyFallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
