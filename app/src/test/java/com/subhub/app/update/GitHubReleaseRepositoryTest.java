package com.subhub.app.update;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class GitHubReleaseRepositoryTest {
    @Test public void updaterTargetsTheMovedCanonicalRepository() {
        assertEquals("https://api.github.com/repos/confiteor48/SubHub/releases?per_page=30",
                GitHubReleaseRepository.RELEASES_URL);
    }
    @Test public void ignoresDraftsButKeepsPublishedPrereleases() throws Exception {
        JSONArray releases = new JSONArray("["
                + release("v0.6.0", true, false, true) + ","
                + release("v0.5.0-beta.1", false, true, true) + ","
                + release("bad-tag", false, false, true) + "]");
        List<GitHubReleaseRepository.Release> parsed =
                GitHubReleaseRepository.parseReleases(releases);
        assertEquals(1, parsed.size());
        assertEquals("v0.5.0-beta.1", parsed.get(0).tag);
        assertEquals("2026-08-26T12:00:00Z", parsed.get(0).publishedAt);
        assertEquals(true, parsed.get(0).prerelease);
    }

    @Test public void releaseWithoutUpdaterManifestCannotInstall() throws Exception {
        JSONArray releases = new JSONArray("[" + release("v0.5.0", false, false, false) + "]");
        assertEquals("", GitHubReleaseRepository.parseReleases(releases).get(0).manifestUrl);
    }

    @Test public void devUpdatesOffNeverLoadsPrereleaseManifests() throws Exception {
        List<GitHubReleaseRepository.Release> parsed = GitHubReleaseRepository.parseReleases(new JSONArray("["
                + release("v0.6.5-dev.2", false, true, true) + ","
                + release("v0.6.4", false, false, true) + "]"));
        UpdateCandidate candidate = GitHubReleaseRepository.selectCandidate(parsed, false, 20, 35,
                new String[]{"arm64-v8a"}, item -> {
                    assertFalse(item.prerelease);
                    return manifest(item.tag, 21, 26, "universal");
                });
        assertEquals("v0.6.4", candidate.manifest.tag);
    }

    @Test public void sameCoreDevBuildCanReplaceInstalledStableByAndroidCode() throws Exception {
        List<GitHubReleaseRepository.Release> parsed = GitHubReleaseRepository.parseReleases(new JSONArray("["
                + release("v0.6.4-dev.123", false, true, true) + "]"));
        UpdateCandidate candidate = GitHubReleaseRepository.selectCandidate(parsed, true, 2_000_122, 35,
                new String[]{"arm64-v8a"}, item -> manifest(item.tag, 2_000_123, 26, "universal"));
        assertTrue(candidate.prerelease);
        assertEquals(2_000_123L, candidate.manifest.versionCode);
    }

    @Test public void newerStableCanReplaceInstalledDevOfSameCore() throws Exception {
        List<GitHubReleaseRepository.Release> parsed = GitHubReleaseRepository.parseReleases(new JSONArray("["
                + release("v0.6.4", false, false, true) + "]"));
        UpdateCandidate candidate = GitHubReleaseRepository.selectCandidate(parsed, false, 2_000_123, 35,
                new String[]{"arm64-v8a"}, item -> manifest(item.tag, 2_000_124, 26, "universal"));
        assertEquals("0.6.4", candidate.manifest.versionName);
        assertFalse(candidate.prerelease);
    }

    @Test public void highestCompatibleCodeWinsRegardlessOfFeedAndSemverOrder() throws Exception {
        List<GitHubReleaseRepository.Release> parsed = GitHubReleaseRepository.parseReleases(new JSONArray("["
                + release("v9.0.0", false, false, true) + ","
                + release("v0.6.4-dev.123", false, true, true) + ","
                + release("v0.6.4", false, false, true) + "]"));
        UpdateCandidate candidate = GitHubReleaseRepository.selectCandidate(parsed, true, 20, 35,
                new String[]{"arm64-v8a"}, item -> manifest(item.tag,
                        item.tag.equals("v0.6.4-dev.123") ? 2_000_123 : 21, 26, "universal"));
        assertEquals("v0.6.4-dev.123", candidate.manifest.tag);
    }

    @Test public void malformedMissingWrongTagWrongSdkAndWrongAbiCannotMaskValidRelease() throws Exception {
        List<GitHubReleaseRepository.Release> parsed = GitHubReleaseRepository.parseReleases(new JSONArray("["
                + release("v0.9.0", false, false, true) + ","
                + release("v0.8.0", false, false, true) + ","
                + release("v0.7.0", false, false, true) + ","
                + release("v0.6.4", false, false, true) + ","
                + release("v0.5.0", false, false, true) + "]"));
        UpdateCandidate candidate = GitHubReleaseRepository.selectCandidate(parsed, true, 20, 35,
                new String[]{"arm64-v8a"}, item -> {
                    if (item.tag.equals("v0.9.0")) throw new IllegalArgumentException("Malformed manifest");
                    if (item.tag.equals("v0.8.0")) return manifest(item.tag, 99, 99, "universal");
                    if (item.tag.equals("v0.7.0")) return manifest(item.tag, 98, 26, "x86");
                    if (item.tag.equals("v0.5.0")) return manifest("v0.6.0", 97, 26, "universal");
                    return manifest(item.tag, 21, 26, "universal");
                });
        assertEquals("v0.6.4", candidate.manifest.tag);
    }

    @Test public void olderAndEqualCodesNeverOfferDowngrades() throws Exception {
        List<GitHubReleaseRepository.Release> parsed = GitHubReleaseRepository.parseReleases(new JSONArray("["
                + release("v99.0.0", false, false, true) + "]"));
        for (long code : new long[]{20, 21}) {
            assertNull(GitHubReleaseRepository.selectCandidate(parsed, true, 21, 35,
                    new String[]{"arm64-v8a"}, item -> manifest(item.tag, code, 26, "universal")));
        }
    }

    @Test public void prereleaseSuffixCannotBypassOptInWithFalseGithubFlag() throws Exception {
        List<GitHubReleaseRepository.Release> parsed = GitHubReleaseRepository.parseReleases(new JSONArray("["
                + release("v0.6.4-dev.123", false, false, true) + "]"));
        assertTrue(parsed.get(0).prerelease);
        assertNull(GitHubReleaseRepository.selectCandidate(parsed, false, 20, 35,
                new String[]{"arm64-v8a"}, item -> { throw new AssertionError("must not fetch"); }));
    }

    @Test public void cachedGithubPrereleaseFlagSurvivesRoundTripEvenWithoutVersionSuffix() throws Exception {
        UpdateCandidate candidate = new UpdateCandidate(manifest("v0.6.4", 21, 26, "universal"),
                "notes", "https://github.com/release", true);
        assertTrue(UpdateCandidate.parse(candidate.json()).prerelease);
        assertTrue(UpdateCandidate.parse(new UpdateCandidate(
                manifest("v0.6.4-dev.123", 21, 26, "universal"), "", "").json()).prerelease);
    }

    static UpdateManifest manifest(String tag, long code, int sdk, String abi) throws Exception {
        JSONObject asset = new JSONObject().put("abi", abi).put("name", "test.apk")
                .put("url", "https://github.com/confiteor48/SubHub/releases/download/" + tag + "/test.apk")
                .put("sha256", "a".repeat(64)).put("size", 42);
        return UpdateManifest.parse(new JSONObject().put("schema", 1).put("packageName", "com.subhub.app")
                .put("versionName", tag.substring(1)).put("versionCode", code).put("minSdk", sdk)
                .put("tag", tag).put("assets", new JSONArray().put(asset)).toString());
    }

    @Test public void sixHourScheduleIsStable() {
        assertEquals(6L * 60L * 60L * 1000L, UpdateScheduler.INTERVAL_MILLIS);
        assertEquals(UpdateScheduler.INTERVAL_MILLIS, UpdateScheduler.intervalMillis(false));
    }

    @Test public void developmentChannelUsesFifteenMinutesForPeriodicAndStaleChecks() {
        long interval = 15L * 60L * 1000L;
        assertEquals(interval, UpdateScheduler.intervalMillis(true));
        long last = 1_800_000_000_000L;
        assertFalse(UpdateScheduler.checkDue(last + interval - 1, last, true));
        assertTrue(UpdateScheduler.checkDue(last + interval, last, true));
        assertFalse(UpdateScheduler.checkDue(last + interval, last, false));
        assertFalse(UpdateScheduler.checkDue(last + UpdateScheduler.INTERVAL_MILLIS - 1, last, false));
        assertTrue(UpdateScheduler.checkDue(last + UpdateScheduler.INTERVAL_MILLIS, last, false));
        assertFalse(UpdateScheduler.checkDue(last - 1, last, true));
    }

    @Test public void manifestChangelogWinsOverReleaseDownloadGuidance() throws Exception {
        UpdateManifest manifest = UpdateManifest.parse("{\"schema\":1,"
                + "\"packageName\":\"com.subhub.app\",\"versionName\":\"0.6.0\","
                + "\"versionCode\":8,\"minSdk\":26,\"tag\":\"v0.6.0\","
                + "\"releaseNotes\":\"## Fixed\\n\\n- Installer handoff\",\"assets\":[{"
                + "\"abi\":\"universal\",\"name\":\"SubHub-0.6.0-universal.apk\","
                + "\"url\":\"https://github.com/bozo-industries/SubHub/releases/download/"
                + "v0.6.0/SubHub-0.6.0-universal.apk\","
                + "\"sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                + "\"size\":42}]}");
        String notes = GitHubReleaseRepository.releaseNotes(manifest,
                "## Choose your APK\\n\\nUniversal works everywhere");
        assertEquals("## Fixed\n\n- Installer handoff", notes);
        assertFalse(notes.contains("Universal"));
    }

    private static String release(String tag, boolean draft, boolean prerelease, boolean manifest) {
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        String assets = manifest ? "[{\"name\":\"SubHub-" + version
                + "-update.json\",\"browser_download_url\":\"https://github.com/manifest\"}]" : "[]";
        return "{\"tag_name\":\"" + tag + "\",\"draft\":" + draft
                + ",\"prerelease\":" + prerelease + ",\"body\":\"notes\","
                + "\"published_at\":\"2026-08-26T12:00:00Z\","
                + "\"html_url\":\"https://github.com/release\",\"assets\":" + assets + "}";
    }
}
