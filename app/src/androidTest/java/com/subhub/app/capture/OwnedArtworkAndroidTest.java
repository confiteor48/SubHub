package com.subhub.app.capture;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.censor.android.SnapshotPaintPolicy;
import com.subhub.app.detection.DetectionPreset;
import com.subhub.app.settings.SettingsRepository;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class OwnedArtworkAndroidTest {
    @Test public void corruptNamedPackFallsBackToEnabledPrivateArtwork() throws Exception {
        try (Fixture f = new Fixture()) {
            File pack = new File(f.directory, "pack"); assertTrue(pack.mkdir());
            try (FileOutputStream output = new FileOutputStream(new File(pack, "corrupt.png"))) { output.write(new byte[]{1, 2, 3}); }
            assertTrue(f.preferences.edit().putString(CustomImageManager.PACK_DIR_KEY, pack.getAbsolutePath()).commit());
            List<Bitmap> images = f.manager.loadOwnedBitmaps(512, 8, 8L * 512 * 512 * 4, f.revision(), () -> false);
            try { assertEquals(1, images.size()); assertEquals(Color.GREEN, images.get(0).getPixel(30, 30)); }
            finally { recycle(images); }
        }
    }
    @Test public void presetCountsDimensionsAndByteLimitsYieldExclusiveSoftwareInputs() throws Exception {
        try (Fixture f = new Fixture()) {
            for (DetectionPreset preset : DetectionPreset.values()) {
                SnapshotPaintPolicy policy = SnapshotPaintPolicy.from(preset);
                List<Bitmap> images = f.manager.loadOwnedBitmaps(policy.artworkDimension, policy.artworkCount, policy.maximumArtworkBytes, f.revision(), () -> false);
                try {
                    assertEquals(1, images.size()); Bitmap image = images.get(0); assertFalse(image.isRecycled());
                    assertNotEquals(Bitmap.Config.HARDWARE, image.getConfig()); assertTrue(image.getWidth() <= policy.artworkDimension);
                    assertTrue(image.getHeight() <= policy.artworkDimension); assertTrue(image.getAllocationByteCount() <= policy.maximumArtworkBytes);
                    assertEquals(Color.GREEN, image.getPixel(30, 30));
                } finally { recycle(images); }
            }
            List<Bitmap> first = f.manager.loadOwnedBitmaps(512, 1, 512L * 512 * 4, f.revision(), () -> false);
            List<Bitmap> second = f.manager.loadOwnedBitmaps(512, 1, 512L * 512 * 4, f.revision(), () -> false);
            try { assertNotSame(first.get(0), second.get(0)); first.get(0).recycle(); assertFalse(second.get(0).isRecycled()); }
            finally { recycle(first); recycle(second); }
        }
    }
    @Test public void staleRevisionCancellationAndInsufficientBudgetReturnNoBorrowedImages() throws Exception {
        try (Fixture f = new Fixture()) {
            assertTrue(f.manager.loadOwnedBitmaps(512, 8, 8L * 512 * 512 * 4, f.revision() + 1, () -> false).isEmpty());
            assertTrue(f.manager.loadOwnedBitmaps(512, 8, 8L * 512 * 512 * 4, f.revision(), () -> true).isEmpty());
            assertTrue(f.manager.loadOwnedBitmaps(512, 8, 4, f.revision(), () -> false).isEmpty());
        }
    }
    private static void recycle(List<Bitmap> images) { for (Bitmap image : images) if (!image.isRecycled()) image.recycle(); }
    private static final class Fixture implements AutoCloseable {
        final Context context = ApplicationProvider.getApplicationContext();
        final SharedPreferences preferences = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
        final CustomImageManager manager = new CustomImageManager(context);
        final String previousMetadata = preferences.getString(CustomImageManager.PREFS_KEY, null);
        final String previousPack = preferences.getString(CustomImageManager.PACK_DIR_KEY, null);
        final boolean hadRevision = preferences.contains(CustomImageManager.REVISION_KEY);
        final long previousRevision = preferences.getLong(CustomImageManager.REVISION_KEY, 0);
        final List<String> added = new ArrayList<>();
        final File directory = new File(context.getCacheDir(), "owned-art-fixture-" + System.nanoTime());
        Fixture() throws Exception {
            assertTrue(directory.mkdir()); assertTrue(preferences.edit().remove(CustomImageManager.PACK_DIR_KEY).putString(CustomImageManager.PREFS_KEY, "[]").commit());
            Bitmap input = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888); input.eraseColor(Color.GREEN);
            File file = new File(directory, "green.png");
            try (FileOutputStream output = new FileOutputStream(file)) { assertTrue(input.compress(Bitmap.CompressFormat.PNG, 100, output)); }
            finally { input.recycle(); }
            assertEquals(1, manager.addImages(Collections.singletonList(Uri.fromFile(file))));
            for (CustomImageManager.Entry entry : manager.listEntries()) added.add(entry.getId());
        }
        long revision() { return preferences.getLong(CustomImageManager.REVISION_KEY, 0); }
        @Override public void close() {
            for (String id : added) manager.delete(id);
            SharedPreferences.Editor editor = preferences.edit();
            if (previousMetadata == null) editor.remove(CustomImageManager.PREFS_KEY); else editor.putString(CustomImageManager.PREFS_KEY, previousMetadata);
            if (previousPack == null) editor.remove(CustomImageManager.PACK_DIR_KEY); else editor.putString(CustomImageManager.PACK_DIR_KEY, previousPack);
            if (hadRevision) editor.putLong(CustomImageManager.REVISION_KEY, previousRevision); else editor.remove(CustomImageManager.REVISION_KEY);
            assertTrue(editor.commit());
            // Only this fixture's named children, no recursive broad cleanup.
            File pack = new File(directory, "pack"), corrupt = new File(pack, "corrupt.png"), green = new File(directory, "green.png");
            if (corrupt.exists()) assertTrue(corrupt.delete()); if (pack.exists()) assertTrue(pack.delete());
            if (green.exists()) assertTrue(green.delete()); assertTrue(directory.delete());
        }
    }
}
