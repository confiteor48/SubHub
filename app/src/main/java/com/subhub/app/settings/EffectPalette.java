package com.subhub.app.settings;

/** Immutable three-slot palette whose meaning is defined by the selected censor effect. */
public final class EffectPalette {
    private final int first;
    private final int second;
    private final int third;

    public EffectPalette(int first, int second, int third) {
        this.first = opaque(first);
        this.second = opaque(second);
        this.third = opaque(third);
    }

    public int first() { return first; }
    public int second() { return second; }
    public int third() { return third; }

    public static EffectPalette defaultsFor(CensorAppearance.Type type) {
        CensorAppearance.Type safe = type == null ? CensorAppearance.Type.BOX : type;
        switch (safe) {
            case STATIC:
                return new EffectPalette(0xff000000, 0xffffffff, 0xffffffff);
            case GLITCH:
                return new EffectPalette(
                        0xff00b4ff, 0xffff0050, 0xffffffff);
            case TAPE:
                return new EffectPalette(
                        0xff121216, 0xffe53935, 0xfff3d33b);
            case ERROR_POPUP:
                return new EffectPalette(
                        0xff2f1c39, 0xffb78bc5, 0xffe4c8ee);
            default:
                return new EffectPalette(0xff000000, 0xffffffff, 0xffffffff);
        }
    }

    private static int opaque(int color) {
        return 0xff000000 | (color & 0x00ffffff);
    }
}
