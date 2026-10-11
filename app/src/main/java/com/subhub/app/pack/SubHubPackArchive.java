package com.subhub.app.pack;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Strict ZIP codec for the current .sub format only. */
public final class SubHubPackArchive {
    public static final String EXTENSION = ".sub";
    public static final String MIME_TYPE = "application/zip";
    private static final int MAX_MANIFEST_BYTES = 512 * 1024;
    private static final int MAX_SECTION_BYTES = 2 * 1024 * 1024;
    private static final long MAX_ASSET_BYTES = 25L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 256L * 1024L * 1024L;

    private SubHubPackArchive() {}

    /** Local library preview only. Full integrity/asset validation still runs before use. */
    static Overview readOverview(java.io.File file) throws IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file, StandardCharsets.UTF_8)) {
            Set<String> names = new LinkedHashSet<>();
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = normalize(entry.getName());
                if (++count > PackInputLimiter.MAX_ENTRIES || !isSafeEntryPath(name) || !names.add(name)) {
                    throw new IOException("Invalid local pack inventory");
                }
            }
            JSONObject manifest = readJsonEntry(zip, "manifest.json", MAX_MANIFEST_BYTES);
            Map<String, JSONObject> sections = new LinkedHashMap<>();
            for (String section : jsonStrings(manifest.optJSONArray("includedSections"))) {
                if (!SubHubPackSchema.SECTIONS.contains(section)) throw new IOException("Unknown section");
                sections.put(section, readJsonEntry(zip, "sections/" + section + ".json", MAX_SECTION_BYTES));
            }
            JSONObject hashes = manifest.optJSONObject("assetHashes");
            int assets = hashes == null ? 0 : hashes.length();
            return new Overview(SubHubPack.fromManifest(manifest, sections, Map.of()), assets);
        } catch (IOException error) { throw error; }
        catch (Exception invalid) { throw new IOException("Invalid local pack metadata", invalid); }
    }

    /** Read only decorative cover bytes; unrelated pack artwork is never inflated for a card. */
    static byte[] readCover(java.io.File file) throws IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file, StandardCharsets.UTF_8)) {
            JSONObject manifest = readJsonEntry(zip, "manifest.json", MAX_MANIFEST_BYTES);
            JSONObject hashes = manifest.optJSONObject("assetHashes");
            if (hashes == null) return null;
            List<String> covers = new ArrayList<>();
            java.util.Iterator<String> keys = hashes.keys();
            while (keys.hasNext()) {
                String path = keys.next();
                if (path.startsWith("assets/cover/") && isSafeAssetPath(path)) covers.add(path);
            }
            Collections.sort(covers);
            if (covers.isEmpty()) return null;
            String path = covers.get(0);
            ZipEntry entry = zip.getEntry(path);
            if (entry == null || entry.isDirectory()) throw new IOException("Missing cover image");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = zip.getInputStream(entry)) {
                PackInputLimiter.copy(input, bytes, MAX_ASSET_BYTES, "Pack cover");
            }
            byte[] cover = bytes.toByteArray();
            if (!constantEquals(hashes.optString(path, ""), sha256(cover))) {
                throw new IOException("Cover image integrity check failed");
            }
            return cover;
        } catch (IOException error) { throw error; }
        catch (Exception invalid) { throw new IOException("Invalid cover image", invalid); }
    }

    private static JSONObject readJsonEntry(java.util.zip.ZipFile zip, String name, long limit)
            throws Exception {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) throw new IOException("Missing pack metadata");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = zip.getInputStream(entry)) {
            PackInputLimiter.copy(input, bytes, limit, "Pack metadata");
        }
        return new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
    }

    static final class Overview {
        final SubHubPack pack;
        final int assetCount;
        Overview(SubHubPack pack, int assetCount) { this.pack = pack; this.assetCount = assetCount; }
    }

    public static void write(SubHubPack pack, OutputStream output) throws IOException {
        if (pack == null || output == null) throw new IOException("Pack output is unavailable");
        try {
            Map<String, byte[]> assets = pack.assetsForArchive();
            Map<String, String> assetHashes = new LinkedHashMap<>();
            long totalBytes = 0L;
            for (Map.Entry<String, byte[]> item : assets.entrySet()) {
                if (!isSafeAssetPath(item.getKey())) throw new IOException("Unsafe asset path");
                if (item.getValue().length > MAX_ASSET_BYTES) throw new IOException("Pack asset is too large");
                totalBytes += item.getValue().length;
                if (totalBytes > MAX_TOTAL_BYTES) throw new IOException("Pack exceeds 256 MiB");
                assetHashes.put(item.getKey(), sha256(item.getValue()));
            }
            // JSON's wire representation removes integral decimal suffixes (2.0 -> 2).
            // Hash the exact parsed wire values that the reader will see, not Float objects.
            JSONObject manifest = new JSONObject(pack.manifestWithoutIntegrity(assetHashes).toString());
            Map<String, JSONObject> sections = new LinkedHashMap<>();
            for (String section : pack.getIncludedSections()) {
                JSONObject value = SubHubPackSchema.sanitizeSection(section, pack.getSection(section));
                sections.put(section, new JSONObject(value.toString()));
            }
            manifest.put("integrity", integrity(manifest, sections));
            byte[] manifestBytes = manifest.toString(2).getBytes(StandardCharsets.UTF_8);
            if (manifestBytes.length > MAX_MANIFEST_BYTES) {
                throw new IOException("Pack manifest is too large");
            }
            totalBytes += manifestBytes.length;
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                put(zip, "manifest.json", manifestBytes);
                for (Map.Entry<String, JSONObject> section : sections.entrySet()) {
                    byte[] sectionBytes = section.getValue().toString(2)
                            .getBytes(StandardCharsets.UTF_8);
                    if (sectionBytes.length > MAX_SECTION_BYTES) {
                        throw new IOException("Pack section is too large: " + section.getKey());
                    }
                    totalBytes += sectionBytes.length;
                    if (totalBytes > MAX_TOTAL_BYTES) throw new IOException("Pack exceeds 256 MiB");
                    put(zip, "sections/" + section.getKey() + ".json", sectionBytes);
                }
                for (Map.Entry<String, byte[]> asset : assets.entrySet()) {
                    put(zip, asset.getKey(), asset.getValue());
                }
            }
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Could not encode SubHub pack", error);
        }
    }

    public static SubHubPack read(InputStream input) throws IOException {
        if (input == null) throw new IOException("Selected pack is unavailable");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0L;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input),
                StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (++count > PackInputLimiter.MAX_ENTRIES) throw new IOException("Pack has too many files");
                String name = normalize(entry.getName());
                if (!isSafeEntryPath(name) || entries.containsKey(name)) {
                    throw new IOException("Pack contains an unsafe or duplicate path");
                }
                long limit = name.equals("manifest.json") ? MAX_MANIFEST_BYTES
                        : name.startsWith("sections/") ? MAX_SECTION_BYTES : MAX_ASSET_BYTES;
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                long bytes = PackInputLimiter.copy(zip, data, limit, "Pack entry");
                total += bytes;
                if (total > MAX_TOTAL_BYTES) throw new IOException("Pack expands beyond 256 MiB");
                entries.put(name, data.toByteArray());
            }
        }
        byte[] manifestBytes = entries.remove("manifest.json");
        if (manifestBytes == null) throw new IOException("Pack has no manifest.json");
        try {
            JSONObject manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
            if (!SubHubPack.FORMAT.equals(manifest.optString("format"))) {
                throw new IOException("Not a SubHub pack archive");
            }
            if (manifest.optInt("schemaVersion", -1) != SubHubPack.SCHEMA_VERSION) {
                throw new IOException("Unsupported pack format; recreate the arrangement in Studio");
            }
            Set<String> included = jsonStrings(manifest.optJSONArray("includedSections"));
            Map<String, JSONObject> sections = new LinkedHashMap<>();
            for (String section : included) {
                if (!SubHubPackSchema.SECTIONS.contains(section)) throw new IOException("Unknown pack section");
                byte[] bytes = entries.remove("sections/" + section + ".json");
                if (bytes == null) throw new IOException("Pack section is missing: " + section);
                // Validate original archive data before portable-field sanitization.
                sections.put(section, new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
            }
            Map<String, byte[]> assets = new LinkedHashMap<>();
            JSONObject expectedHashes = manifest.optJSONObject("assetHashes");
            if (expectedHashes == null) expectedHashes = new JSONObject();
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                if (!isSafeAssetPath(item.getKey())) throw new IOException("Unexpected pack entry");
                String expected = expectedHashes.optString(item.getKey(), "");
                if (expected.isEmpty() || !constantEquals(expected, sha256(item.getValue()))) {
                    throw new IOException("Pack asset integrity check failed");
                }
                assets.put(item.getKey(), item.getValue());
            }
            if (expectedHashes.length() != assets.size()) throw new IOException("Pack asset list is incomplete");
            String expectedIntegrity = manifest.optString("integrity", "");
            if (expectedIntegrity.isEmpty()) throw new IOException("Pack integrity digest is missing");
            JSONObject without = PackVerifier.deepCopy(manifest);
            without.put("integrity", "");
            if (!constantEquals(expectedIntegrity, integrity(without, sections))) {
                throw new IOException("Pack integrity digest does not match");
            }
            return SubHubPack.fromManifest(manifest, sections, assets);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Pack metadata is malformed", error);
        }
    }

    public static boolean isSafeAssetPath(String path) {
        if (path == null) return false;
        String value = normalize(path);
        return isSafeEntryPath(value) && (value.startsWith("assets/censor/")
                || value.startsWith("assets/popup/") || value.startsWith("assets/cover/"));
    }

    private static boolean isSafeEntryPath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains(":")
                || path.indexOf('\u0000') >= 0 || path.contains("\\")) return false;
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
        }
        return true;
    }

    private static String normalize(String path) { return path == null ? "" : path.replace('\\', '/'); }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String integrity(JSONObject manifest, Map<String, JSONObject> sections)
            throws Exception {
        StringBuilder canonical = new StringBuilder(PackVerifier.canonicalize(manifest));
        List<String> names = new ArrayList<>(sections.keySet());
        Collections.sort(names);
        for (String name : names) canonical.append('\n').append(name).append(':')
                .append(PackVerifier.canonicalize(sections.get(name)));
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(left.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                right.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    private static Set<String> jsonStrings(JSONArray values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) for (int index = 0; index < values.length(); index++) {
            String value = values.optString(index, "");
            if (!value.isBlank()) result.add(value);
        }
        return result;
    }
}
