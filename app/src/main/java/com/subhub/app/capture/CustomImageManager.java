package com.subhub.app.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import com.subhub.app.settings.SettingsRepository;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Private internal store for user-selected and active-pack censor images. */
public final class CustomImageManager {
    public static final String PREFS_KEY = "custom_images";
    public static final String PACK_DIR_KEY = "custom_images_pack_dir";
    public static final String REVISION_KEY = "custom_images_revision";
    private static final String TAG = "CustomImageManager";
    private static final long MAX_IMAGE_BYTES = 25L * 1024L * 1024L;
    private static final int MAX_IMAGES = 64;

    private final Context context;
    private final SharedPreferences preferences;
    private final File storeDirectory;

    public CustomImageManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(
                SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
        storeDirectory = new File(this.context.getFilesDir(), "custom_censors");
        if (!storeDirectory.exists() && !storeDirectory.mkdirs()) {
            Log.w(TAG, "Could not create custom censor directory");
        }
    }

    public List<Entry> listEntries() {
        String raw = preferences.getString(PREFS_KEY, null);
        if (raw == null) return Collections.emptyList();
        List<Entry> entries = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) continue;
                String id = item.optString("id", "");
                if (!id.isEmpty() && fileFor(id).isFile()) {
                    entries.add(new Entry(id, item.optBoolean("enabled", true)));
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not parse custom-image metadata", error);
        }
        return Collections.unmodifiableList(entries);
    }

    public int addImages(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return 0;
        List<Entry> entries = new ArrayList<>(listEntries());
        int added = 0;
        for (Uri uri : uris) {
            if (entries.size() >= MAX_IMAGES) break;
            String id = UUID.randomUUID().toString();
            File destination = fileFor(id);
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(destination)) {
                if (input == null) throw new IOException("Selected image could not be opened");
                copyBounded(input, output, MAX_IMAGE_BYTES);
                output.flush();
                Bitmap validation = decode(destination, 256);
                if (validation == null) {
                    throw new IOException("Selected content is not a supported image");
                }
                validation.recycle();
                entries.add(new Entry(id, true));
                added++;
            } catch (Exception error) {
                if (destination.exists() && !destination.delete()) {
                    Log.w(TAG, "Could not remove rejected custom image " + id);
                }
                Log.w(TAG, "Could not import custom image", error);
            }
        }
        if (added > 0) save(entries);
        return added;
    }

    public void setEnabled(String id, boolean enabled) {
        List<Entry> updated = new ArrayList<>();
        for (Entry entry : listEntries()) {
            updated.add(entry.id.equals(id) ? new Entry(id, enabled) : entry);
        }
        save(updated);
    }

    public void delete(String id) {
        List<Entry> updated = new ArrayList<>();
        for (Entry entry : listEntries()) {
            if (!entry.id.equals(id)) updated.add(entry);
        }
        save(updated);
        File file = fileFor(id);
        if (file.exists() && !file.delete()) Log.w(TAG, "Could not delete custom image " + id);
    }

    public boolean hasAnyImages() {
        if (!listEntries().isEmpty()) return true;
        File packDirectory = activePackDirectory();
        File[] files = packDirectory == null ? null : packDirectory.listFiles();
        if (files == null) return false;
        for (File file : files) if (isImageFile(file)) return true;
        return false;
    }

    public List<Bitmap> loadEnabledBitmaps(int maximumDimension) {
        return loadEnabledBitmaps(maximumDimension, MAX_IMAGES);
    }

    /** Loads a bounded live-rendering set so a large library cannot exhaust the app heap. */
    public List<Bitmap> loadEnabledBitmaps(int maximumDimension, int maximumImages) {
        List<Bitmap> result = new ArrayList<>();
        int boundedMaximum = Math.max(1, Math.min(MAX_IMAGES, maximumImages));
        File packDirectory = activePackDirectory();
        File[] packFiles = packDirectory == null ? null : packDirectory.listFiles();
        if (packFiles != null) {
            for (File file : packFiles) {
                if (result.size() >= boundedMaximum) break;
                if (!isImageFile(file)) continue;
                Bitmap bitmap = decode(file, maximumDimension);
                if (bitmap != null) result.add(bitmap);
            }
        }
        if (!result.isEmpty()) return result;
        for (Entry entry : listEntries()) {
            if (result.size() >= boundedMaximum) break;
            if (!entry.enabled) continue;
            Bitmap bitmap = decode(fileFor(entry.id), maximumDimension);
            if (bitmap != null) result.add(bitmap);
        }
        return result;
    }

    /**
     * New exclusive software inputs for one replacement paint preparation, never live/shared pool
     * ownership. Returned bitmap bytes are bounded; a single decoder/native temporary is separate.
     * Cancellation/throw recycles the entire partial set. Caller must recycle every returned input
     * after copying it; none of these inputs may be recorded by a hardware Canvas.
     */
    public List<Bitmap> loadOwnedBitmaps(int maximumDimension, int maximumImages, long maximumBytes,
            long expectedRevision, BooleanSupplier cancelled) {
        if (maximumDimension < 1 || maximumDimension > 1024 || maximumImages < 1 || maximumImages > 16
                || maximumBytes < 4 || maximumBytes > 64L * 1024 * 1024 || expectedRevision < 0 || cancelled == null) {
            throw new IllegalArgumentException("owned artwork budget");
        }
        List<Bitmap> result = new ArrayList<>(maximumImages); long bytes = 0; boolean transferred = false;
        String metadata = preferences.getString(PREFS_KEY, null), directory = preferences.getString(PACK_DIR_KEY, null);
        try {
            if (preferences.getLong(REVISION_KEY, 0) != expectedRevision) return Collections.emptyList();
            File packDirectory = activePackDirectory();
            File[] packFiles = packDirectory == null ? null : packDirectory.listFiles();
            if (packFiles != null) for (File file : packFiles) {
                if (cancelled.getAsBoolean()) return Collections.emptyList();
                if (result.size() >= maximumImages) break;
                if (!isImageFile(file) || file.length() <= 0 || file.length() > MAX_IMAGE_BYTES) continue;
                Bitmap image = decodeOwned(file, maximumDimension, maximumBytes - bytes);
                if (image != null) { result.add(image); bytes += image.getAllocationByteCount(); }
            }
            // Preserve live-rendering fallback after decoding, not export-file name selection.
            if (result.isEmpty()) for (Entry entry : listEntries()) {
                if (cancelled.getAsBoolean()) return Collections.emptyList();
                if (result.size() >= maximumImages) break;
                File file = fileFor(entry.id);
                if (!entry.enabled || file.length() <= 0 || file.length() > MAX_IMAGE_BYTES) continue;
                Bitmap image = decodeOwned(file, maximumDimension, maximumBytes - bytes);
                if (image != null) { result.add(image); bytes += image.getAllocationByteCount(); }
            }
            if (cancelled.getAsBoolean() || preferences.getLong(REVISION_KEY, 0) != expectedRevision
                    || !java.util.Objects.equals(metadata, preferences.getString(PREFS_KEY, null))
                    || !java.util.Objects.equals(directory, preferences.getString(PACK_DIR_KEY, null))) return Collections.emptyList();
            transferred = true; return result;
        } finally {
            if (!transferred) for (Bitmap image : result) image.recycle();
        }
    }

    private static Bitmap decodeOwned(File file, int dimension, long remainingBytes) {
        if (remainingBytes < 4) return null;
        Bitmap image = null; boolean transferred = false;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (((long) bounds.outWidth + sample - 1) / sample > dimension
                    || ((long) bounds.outHeight + sample - 1) / sample > dimension) {
                if (sample >= 1 << 30) return null;
                sample *= 2;
            }
            long width = ((long) bounds.outWidth + sample - 1) / sample;
            long height = ((long) bounds.outHeight + sample - 1) / sample;
            if (4L * width * height > remainingBytes) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample; options.inScaled = false; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB);
            image = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (image == null || image.getConfig() == Bitmap.Config.HARDWARE || image.getWidth() > dimension
                    || image.getHeight() > dimension || image.getAllocationByteCount() > remainingBytes) return null;
            transferred = true; return image;
        } catch (RuntimeException | OutOfMemoryError unavailable) { return null; }
        finally { if (!transferred && image != null) image.recycle(); }
    }

    public Bitmap thumbnail(String id, int maximumDimension) {
        return decode(fileFor(id), maximumDimension);
    }

    /** Private image files eligible for explicit embedding in a portable SubHub pack. */
    public List<File> enabledFilesForPackExport() {
        List<File> result = new ArrayList<>();
        File packDirectory = activePackDirectory();
        File[] packFiles = packDirectory == null ? null : packDirectory.listFiles();
        if (packFiles != null) for (File file : packFiles) {
            if (isImageFile(file)) result.add(file);
        }
        if (!result.isEmpty()) return Collections.unmodifiableList(result);
        for (Entry entry : listEntries()) if (entry.enabled) {
            File file = fileFor(entry.id);
            if (file.isFile()) result.add(file);
        }
        return Collections.unmodifiableList(result);
    }

    File fileFor(String id) {
        return new File(storeDirectory, id + ".bin");
    }

    private File activePackDirectory() {
        String path = preferences.getString(PACK_DIR_KEY, null);
        if (path == null || path.trim().isEmpty()) return null;
        File directory = new File(path);
        return directory.isDirectory() ? directory : null;
    }

    private void save(List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            JSONObject value = new JSONObject();
            try {
                value.put("id", entry.id);
                value.put("enabled", entry.enabled);
                array.put(value);
            } catch (Exception error) {
                Log.w(TAG, "Could not serialize custom-image metadata", error);
            }
        }
        preferences.edit()
                .putString(PREFS_KEY, array.toString())
                .putLong(REVISION_KEY, System.currentTimeMillis())
                .apply();
    }

    private static void copyBounded(InputStream input, FileOutputStream output, long maximum)
            throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maximum) throw new IOException("Image exceeds private import limit");
            output.write(buffer, 0, read);
        }
    }

    private static Bitmap decode(File file, int maximumDimension) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / sample > maximumDimension
                    || bounds.outHeight / sample > maximumDimension) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not decode " + file.getName(), error);
            return null;
        }
    }

    private static boolean isImageFile(File file) {
        if (!file.isFile()) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".webp");
    }

    public static final class Entry {
        private final String id;
        private final boolean enabled;

        public Entry(String id, boolean enabled) {
            this.id = id;
            this.enabled = enabled;
        }

        public String getId() { return id; }
        public boolean isEnabled() { return enabled; }
    }
}
