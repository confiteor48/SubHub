package com.subhub.app.censor.android;
import static org.junit.Assert.*;
import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
/** Unchanged qualified photo witness, extracted for the end-to-end fixture. */
final class PositivePhotoWitness {
    private static final String TARGET="com.subhub.app.test";
    private static final String SHA="88431cd9653ccd539741b555fb0a46b61558b301d4110412b5bc28b5e3ea6cb5";
    private static final int[][] POINTS={{200,120},{100,300},{250,50},{400,400},{420,100}};
    private static final Set<String> GEOMETRY_KEYS=new HashSet<>(Arrays.asList("bitmapSize","viewOrigin","viewSize","padding","drawableBounds","imageMatrix","attached","shown","windowFocus","viewAlpha","rootAlpha","drawCount","viewScroll"));
    static JSONObject inspect(UiAutomation ui) throws Exception {
        AccessibilityNodeInfo root = ui.getRootInActiveWindow(); if (root == null) return null;
        List<AccessibilityNodeInfo> frames = Collections.emptyList(), images = Collections.emptyList();
        try {
            if (!TARGET.contentEquals(root.getPackageName())) return null;
            frames = root.findAccessibilityNodeInfosByViewId("android:id/custom"); if (frames.size() != 1) return null;
            AccessibilityNodeInfo frame = frames.get(0); if (!frame.refresh()) return null;
            Bundle extras = frame.getExtras();
            if (!SHA.equals(extras.getString(ProductTargetFixtureActivity.VISUAL_SHA))
                    || extras.getLong(ProductTargetFixtureActivity.VISUAL_COMMAND) != 1 || extras.getLong(ProductTargetFixtureActivity.VISUAL_FRAME) != 1) return null;
            Bundle value = extras.getBundle(ProductTargetFixtureActivity.VISUAL_GEOMETRY); if (value == null) return null;
            assertEquals(GEOMETRY_KEYS, value.keySet());
            if (!value.getBoolean("attached") || !value.getBoolean("shown") || !value.getBoolean("windowFocus") || value.getLong("drawCount") < 1) return null;
            int[] origin = value.getIntArray("viewOrigin"), size = value.getIntArray("viewSize"); assertNotNull(origin); assertNotNull(size);
            assertEquals(2, origin.length); assertEquals(2, size.length);
            images = root.findAccessibilityNodeInfosByViewId("android:id/icon"); if (images.size() != 1) return null;
            Rect actual = new Rect(); images.get(0).getBoundsInScreen(actual);
            if (!actual.equals(new Rect(origin[0], origin[1], origin[0] + size[0], origin[1] + size[1])) || actual.isEmpty()) return null;
            JSONObject result = new JSONObject().put("windowId", root.getWindowId()).put("command", 1).put("frame", 1).put("inputSha256", SHA);
            for (String key : GEOMETRY_KEYS) {
                Object item = value.get(key);
                if (item instanceof int[]) result.put(key, new JSONArray(item));
                else if (item instanceof float[]) result.put(key, new JSONArray(item));
                else result.put(key, item);
            }
            return result;
        } finally {
            for (AccessibilityNodeInfo value : frames) if (value != null && value != root) value.recycle();
            for (AccessibilityNodeInfo value : images) if (value != null && value != root) value.recycle();
            root.recycle();
        }
    }
    static boolean sameGeometry(JSONObject before, JSONObject after) throws Exception {
        for (Iterator<String> keys = before.keys(); keys.hasNext();) {
            String key = keys.next(); if (key.equals("drawCount")) continue;
            if (!before.get(key).toString().equals(after.get(key).toString())) return false;
        }
        return after.getLong("drawCount") >= before.getLong("drawCount");
    }
    static JSONObject evaluate(JSONObject value, Bitmap pixels, Bitmap source) throws Exception {
        JSONArray origin = value.getJSONArray("viewOrigin"), view = value.getJSONArray("viewSize"), padding = value.getJSONArray("padding");
        JSONArray bounds = value.getJSONArray("drawableBounds"), matrix = value.getJSONArray("imageMatrix");
        assertEquals(new JSONArray(new int[]{512,512}).toString(), value.getJSONArray("bitmapSize").toString());
        assertEquals(new JSONArray(new int[]{0,0}).toString(), value.getJSONArray("viewScroll").toString());
        assertEquals(1, value.getDouble("viewAlpha"), 0); assertEquals(1, value.getDouble("rootAlpha"), 0);
        assertEquals(9, matrix.length()); for (int index : new int[]{1,3,6,7}) assertEquals(0, matrix.getDouble(index), 0);
        assertEquals(1, matrix.getDouble(8), 0);
        int ox = origin.getInt(0), oy = origin.getInt(1), width = view.getInt(0), height = view.getInt(1);
        assertTrue(ox >= 0 && oy >= 0 && ox + width <= pixels.getWidth() && oy + height <= pixels.getHeight());
        double sx = matrix.getDouble(0), sy = matrix.getDouble(4), tx = matrix.getDouble(2), ty = matrix.getDouble(5);
        int dw = bounds.getInt(2) - bounds.getInt(0), dh = bounds.getInt(3) - bounds.getInt(1);
        assertTrue(sx * dw / 512 >= 1 && sy * dh / 512 >= 1);
        JSONArray witnesses = new JSONArray(); int matched = 0; Set<String> locations = new HashSet<>();
        for (int[] point : POINTS) {
            int px = (int) Math.floor(ox + padding.getInt(0) + sx * (bounds.getInt(0) + (point[0] + .5) * dw / 512) + tx);
            int py = (int) Math.floor(oy + padding.getInt(1) + sy * (bounds.getInt(1) + (point[1] + .5) * dh / 512) + ty);
            assertTrue(locations.add(px + ":" + py));
            assertTrue(ox + padding.getInt(0) <= px - 1 && px + 1 < ox + width - padding.getInt(2));
            assertTrue(oy + padding.getInt(1) <= py - 1 && py + 1 < oy + height - padding.getInt(3));
            int[] low = {255,255,255}, high = {0,0,0};
            for (int y = point[1] - 2; y <= point[1] + 2; y++) for (int x = point[0] - 2; x <= point[0] + 2; x++) {
                int[] rgb = rgb(source.getPixel(x,y)); for (int c = 0; c < 3; c++) { low[c] = Math.min(low[c], rgb[c]); high[c] = Math.max(high[c], rgb[c]); }
            }
            for (int c = 0; c < 3; c++) { low[c] = Math.max(0, low[c] - 8); high[c] = Math.min(255, high[c] + 8); }
            JSONArray samples = new JSONArray(); boolean all = true;
            for (int y = py - 1; y <= py + 1; y++) for (int x = px - 1; x <= px + 1; x++) {
                int[] actual = rgb(pixels.getPixel(x,y)); boolean match = true;
                for (int c = 0; c < 3; c++) match &= low[c] <= actual[c] && actual[c] <= high[c];
                all &= match; samples.put(new JSONObject().put("screen", new JSONArray(new int[]{x,y})).put("rgb", new JSONArray(actual)).put("matches", match));
            }
            if (all) matched++;
            witnesses.put(new JSONObject().put("source", new JSONArray(point)).put("center", new JSONArray(new int[]{px,py}))
                    .put("minimum", new JSONArray(low)).put("maximum", new JSONArray(high)).put("samples", samples).put("matches", all));
        }
        return new JSONObject().put("matches", matched == 5).put("witnesses", witnesses).put("matchedPhotoPatches", matched)
                .put("totalPhotoPatches", 5).put("screenPixels", 45).put("inputSha256", SHA).put("physicalPixelClock", false).put("fullImageFidelity", false);
    }
    private static int[] rgb(int color) { return new int[]{Color.red(color), Color.green(color), Color.blue(color)}; }
}
