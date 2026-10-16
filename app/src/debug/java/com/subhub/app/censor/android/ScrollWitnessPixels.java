package com.subhub.app.censor.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure bounded reader for the synthetic source/witness fixture, not a product detector. */
public final class ScrollWitnessPixels {
    public static final int TARGET = 0xffe72942, WITNESS = 0xff00ffff, COVER = 0xffff0080;
    private ScrollWitnessPixels() { }

    public static final class Box {
        public final int left, top, right, bottom;
        public Box(int left, int top, int right, int bottom) {
            if (left < 0 || top < 0 || right <= left || bottom <= top) throw new IllegalArgumentException("marker box");
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        public int height() { return bottom - top; }
        public double centerY() { return (top + bottom) / 2.0; }
        public boolean contains(double x, double y) { return x >= left && x < right && y >= top && y < bottom; }
    }

    /** Only one horizontal marker run per row is supported; malformed input is not zero work. */
    public static List<Box> bands(int[] pixels, int width, int height, int color) {
        if (pixels == null || width < 8 || height < 8 || width > 4096 || height > 4096
                || (long) width * height != pixels.length) throw new IllegalArgumentException("marker pixels");
        int minWidth = Math.max(4, width / 80), minHeight = Math.max(4, height / 600);
        List<Box> result = new ArrayList<>();
        int left = width, right = 0, top = -1;
        for (int y = 0; y <= height; y++) {
            int rowLeft = width, rowRight = 0, runs = 0;
            if (y < height) for (int x = 0; x < width;) {
                if (!matches(pixels[y * width + x], color)) { x++; continue; }
                int start = x++;
                while (x < width && matches(pixels[y * width + x], color)) x++;
                if (x - start >= minWidth) { runs++; rowLeft = Math.min(rowLeft, start); rowRight = Math.max(rowRight, x); }
            }
            if (runs > 1) throw new IllegalArgumentException("ambiguous horizontal marker runs");
            if (runs == 1) {
                if (top < 0) top = y;
                left = Math.min(left, rowLeft); right = Math.max(right, rowRight);
            } else if (top >= 0) {
                if (y - top >= minHeight) {
                    if (result.size() >= 64) throw new IllegalArgumentException("marker capacity");
                    result.add(new Box(left, top, right, y));
                }
                top = -1; left = width; right = 0;
            }
        }
        return Collections.unmodifiableList(result);
    }
    private static boolean matches(int actual, int expected) {
        if ((actual >>> 24) < 250) return false;
        for (int shift = 0; shift < 24; shift += 8) if (Math.abs(((actual >>> shift) & 255) - ((expected >>> shift) & 255)) > 4) return false;
        return true;
    }

    /** Coverage geometry only. Nearest bands cannot prove semantic identity or compositor time. */
    public static final class Sample {
        public final int witnesses, masks, paired, missingCenters, unpairedMaskBands, tallMaskBands;
        public final double absoluteCenterErrorSum, maximumCenterError;
        private Sample(int witnesses, int masks, int paired, int missing, int unpaired, int tall, double sum, double maximum) {
            this.witnesses = witnesses; this.masks = masks; this.paired = paired; missingCenters = missing;
            unpairedMaskBands = unpaired; tallMaskBands = tall; absoluteCenterErrorSum = sum; maximumCenterError = maximum;
        }
    }
    public static Sample measure(List<Box> witnesses, List<Box> masks, int offsetX, int markerHeight) {
        if (witnesses == null || masks == null || markerHeight < 4 || witnesses.size() > 64 || masks.size() > 64)
            throw new IllegalArgumentException("marker comparison");
        int full = 0, missing = 0, paired = 0, tall = 0; double sum = 0, maximum = 0;
        boolean[] used = new boolean[masks.size()];
        for (Box witness : witnesses) {
            if (witness.height() < markerHeight - 2) continue;
            full++; double x = (witness.left + witness.right) / 2.0 + offsetX, y = witness.centerY();
            int nearest = -1; double distance = Double.POSITIVE_INFINITY; boolean covered = false;
            for (int i = 0; i < masks.size(); i++) {
                Box mask = masks.get(i); covered |= mask.contains(x, y);
                if (x >= mask.left && x < mask.right && Math.abs(mask.centerY() - y) < distance) {
                    distance = Math.abs(mask.centerY() - y); nearest = i;
                }
            }
            if (!covered) missing++;
            if (nearest >= 0) { used[nearest] = true; paired++; sum += distance; maximum = Math.max(maximum, distance); }
        }
        int unpaired = 0;
        for (int i = 0; i < masks.size(); i++) {
            if (masks.get(i).height() > markerHeight * 1.5) tall++;
            if (!used[i]) {
                // A partial edge witness can legitimately own a partial edge mask.
                Box mask = masks.get(i); boolean edge = false;
                for (Box witness : witnesses) if (witness.height() < markerHeight - 2)
                    edge |= mask.contains((witness.left + witness.right) / 2.0 + offsetX, witness.centerY());
                if (!edge) unpaired++;
            }
        }
        return new Sample(full, masks.size(), paired, missing, unpaired, tall, sum, maximum);
    }
}
