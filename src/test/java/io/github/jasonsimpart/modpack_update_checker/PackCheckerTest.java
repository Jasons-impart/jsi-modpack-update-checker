package io.github.jasonsimpart.modpack_update_checker;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PackCheckerTest {
    @TempDir Path instance;
    private static final String SHA = "19e4813896419075714410460d98505f47f15f14";
    private static PackChecker.Tag tag(String name) { return new PackChecker.Tag(name, SHA); }
    private static PackVersion version(String name) { return PackVersion.parse(name).orElseThrow(); }

    @Test void numericOrderingAndFormalRelease() {
        assertTrue(version("v2.0.0.0-test10").compareTo(version("v2.0.0.0-test2")) > 0);
        assertTrue(version("v2.0.0.0").compareTo(version("v2.0.0.0-test99")) > 0);
        assertTrue(version("v2.0.0.1-test1").compareTo(version("v2.0.0.0")) > 0);
        assertTrue(version("v20.0.0.0").compareTo(version("v3.0.0.0")) > 0);
        assertTrue(PackVersion.parse("v2.0.0").isEmpty());
        assertTrue(PackVersion.parse("v2.0.0.0-test-build-10").isEmpty());
        assertTrue(PackVersion.isDevelopment("v2.0.0.0-test-build-10"));
    }

    @Test void followsInstalledChannelWithoutDowngrades() throws Exception {
        var tags = List.of(tag("v2.0.0.0-test4"), tag("v2.0.0.0-test2"), tag("v2.0.0.0-test3"));
        assertEquals("v2.0.0.0-test4", PackChecker.recommend("v2.0.0.0-test1", tags).name());
        assertNull(PackChecker.recommend("v2.0.0.0-test4", tags));
        assertNull(PackChecker.recommend("v2.0.0.0", tags));
        assertNull(PackChecker.recommend("v3.0.0.0-test1", tags));
        assertEquals("v2.0.0.0", PackChecker.recommend("v2.0.0.0-test4", List.of(tag("v2.0.0.0"))).name());
        assertThrows(IOException.class, () -> PackChecker.recommend("v2.0.0.0-test-build-1", tags));
    }

    @Test void refusesAmbiguousVersionAliases() {
        assertThrows(IOException.class, () -> PackChecker.recommend("v1.0.0.0", List.of(tag("v2.0.0.0"),
                new PackChecker.Tag("v02.0.0.0", "2d863adf796111eaa53dbda14ad9e155cb738cbf"))));
    }

    @Test void recommendationDoesNotDependOnOrderOfOlderAmbiguousTags() throws Exception {
        var older = tag("v2.0.0.0");
        var alias = new PackChecker.Tag("v02.0.0.0", "2d863adf796111eaa53dbda14ad9e155cb738cbf");
        var newest = tag("v3.0.0.0");
        assertEquals(newest, PackChecker.recommend("v1.0.0.0", List.of(older, alias, newest)));
        assertEquals(newest, PackChecker.recommend("v1.0.0.0", List.of(newest, older, alias)));
        assertThrows(IOException.class, () -> PackChecker.recommend("v1.0.0.0", List.of(older, alias)));
        assertThrows(IOException.class, () -> PackChecker.recommend("v1.0.0.0", List.of(alias, older)));
    }

    @Test void acceptsExactlyTwoThousandTagsButRejectsAnIncompleteList() throws Exception {
        var tags = PackChecker.pages(page -> {
            var entries = new JsonArray();
            if (page > 20) return entries;
            for (int index = 0; index < 100; index++) entries.add(JsonParser.parseString(
                    "{\"name\":\"v2.0." + page + "." + index + "\",\"commit\":{\"sha\":\"" + SHA + "\"}}"));
            return entries;
        });
        assertEquals(2000, tags.size());
        var full = new JsonArray();
        for (int index = 0; index < 100; index++) full.add(JsonParser.parseString("{\"name\":\"unrelated\"}"));
        assertThrows(IOException.class, () -> PackChecker.pages(page -> full));
    }

    @Test void readsLaterPagesAndIgnoresNonVersionTags() throws Exception {
        var full = new JsonArray();
        for (int i = 0; i < 100; i++) full.add(JsonParser.parseString("{\"name\":\"unrelated" + i + "\"}"));
        var last = JsonParser.parseString("[{\"name\":\"v2.0.0.0-test4\",\"commit\":{\"sha\":\"" + SHA + "\"}}]").getAsJsonArray();
        var tags = PackChecker.pages(page -> page == 1 ? full : last);
        assertEquals(List.of(tag("v2.0.0.0-test4")), tags);
        assertThrows(IOException.class, () -> PackChecker.pages(page -> full));
        assertThrows(IOException.class, () -> PackChecker.pages(page -> {
            throw new IOException("offline");
        }));
        assertThrows(IOException.class, () -> PackChecker.pages(page -> JsonParser.parseString(
                "[{\"name\":\"v2.0.0.0\",\"commit\":{\"sha\":\"short\"}}]").getAsJsonArray()));
    }

    @Test void configurationUsesExplicitRepositoryAndContainedMarker() throws Exception {
        var json = JsonParser.parseString("""
                {"schemaVersion":1,"displayName":"CDPR",
                 "check":{"source":"github-tags","repository":"owner/pack","onMainMenu":false,
                  "versionRules":{"template":"numeric-four","channelPolicy":"follow-installed"}},
                 "installed":{"releaseInfoPath":"config/marker.json"}}
                """).getAsJsonObject();
        Path root = Path.of("/test/instance");
        var settings = PackChecker.settings(root, json);
        assertEquals("owner/pack", settings.repository());
        assertEquals(root.resolve("config/marker.json"), settings.marker());
        assertFalse(settings.onMainMenu());
        json.getAsJsonObject("installed").addProperty("releaseInfoPath", "../outside.json");
        assertThrows(IOException.class, () -> PackChecker.settings(root, json));
        json.getAsJsonObject("installed").addProperty("releaseInfoPath", "config/marker.json");
        json.getAsJsonObject("check").addProperty("repository", "https://example.com/pack");
        assertThrows(IOException.class, () -> PackChecker.settings(root, json));
    }

    @Test void cachesFailuresAndAllowsManualRecoveryWithoutRecommendingCiBuilds() throws Exception {
        var checker = new PackChecker(instance);
        checker.check(false).get();
        assertEquals(PackChecker.Status.UNCONFIGURED, checker.view().status());
        Files.createDirectories(instance.resolve("config"));
        Files.copy(Path.of("examples/cdpr-config.json"), instance.resolve("config/modpack-update-checker.json"));
        checker.check(false).get();
        assertEquals(PackChecker.Status.UNCONFIGURED, checker.view().status());
        checker.check(true).get();
        assertEquals(PackChecker.Status.ERROR, checker.view().status());
        Path marker = instance.resolve("config/createdelight_release_info.json");
        Files.writeString(marker, "{\"schemaVersion\":1,\"version\":\"v2.0.0.0-test-build-5\"}");
        checker.check(true).get();
        assertEquals(PackChecker.Status.DEVELOPMENT, checker.view().status());
        assertNull(checker.view().target());
        Files.writeString(marker, "{\"schemaVersion\":1,\"version\":\"v2.0.0.0-test4\",\"commitId\":\"short\"}");
        checker.check(true).get();
        assertEquals(PackChecker.Status.ERROR, checker.view().status());
    }
}
