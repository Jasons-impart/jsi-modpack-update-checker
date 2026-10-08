package io.github.jasonsimpart.modpack_update_checker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

final class PackChecker {
    enum Status { IDLE, CHECKING, UNCONFIGURED, CURRENT, AVAILABLE, DEVELOPMENT, ERROR }
    record Tag(String name, String commit) {}
    record View(Status status, String pack, String installed, Tag target, String repository, String error) {
        static View of(Status status) { return new View(status, "", "", null, "", ""); }
    }
    record Settings(String name, String repository, Path marker, boolean onMainMenu) {}
    @FunctionalInterface interface PageFetcher { JsonArray fetch(int page) throws Exception; }
    private volatile View view = View.of(Status.IDLE);
    private final Path gameDirectory;
    private final java.util.concurrent.Executor executor = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "modpack-update-checker");
        thread.setDaemon(true);
        return thread;
    });

    PackChecker(Path gameDirectory) { this.gameDirectory = gameDirectory.toAbsolutePath().normalize(); }
    View view() { return view; }

    synchronized CompletableFuture<Void> check(boolean manual) {
        if (view.status == Status.CHECKING || (!manual && view.status != Status.IDLE)) return CompletableFuture.completedFuture(null);
        view = View.of(Status.CHECKING);
        return CompletableFuture.runAsync(() -> {
            try {
                Path configuration = gameDirectory.resolve("config/modpack-update-checker.json");
                if (!Files.exists(configuration)) {
                    view = View.of(Status.UNCONFIGURED);
                    return;
                }
                Settings settings = settings(gameDirectory, read(configuration));
                if (!manual && !settings.onMainMenu) {
                    view = new View(Status.IDLE, settings.name, "", null, settings.repository, "");
                    return;
                }
                view = new View(Status.CHECKING, settings.name, "", null, settings.repository, "");
                JsonObject marker = read(settings.marker);
                require(marker.has("schemaVersion") && marker.get("schemaVersion").getAsInt() == 1, "Unsupported installed marker schema");
                String installed = text(marker, "version");
                if (marker.has("commitId")) require(text(marker, "commitId").matches("[0-9a-fA-F]{40}"), "Invalid installed commitId");
                if (PackVersion.isDevelopment(installed)) {
                    view = new View(Status.DEVELOPMENT, settings.name, installed, null, settings.repository, "");
                    return;
                }
                require(PackVersion.parse(installed).isPresent(), "Unsupported installed version: " + installed);
                view = new View(Status.CHECKING, settings.name, installed, null, settings.repository, "");
                var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                var tags = pages(page -> {
                    var request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + settings.repository
                            + "/tags?per_page=100&page=" + page)).timeout(Duration.ofSeconds(20))
                            .header("Accept", "application/vnd.github+json").header("User-Agent", "Modpack-Update-Checker")
                            .GET().build();
                    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    require(response.statusCode() == 200, "GitHub HTTP " + response.statusCode()
                            + response.headers().firstValue("x-ratelimit-reset").map(value -> " (rate limit reset: " + value + ")").orElse(""));
                    return JsonParser.parseString(response.body()).getAsJsonArray();
                });
                Tag target = recommend(installed, tags);
                view = new View(target == null ? Status.CURRENT : Status.AVAILABLE,
                        settings.name, installed, target, settings.repository, "");
            } catch (Exception error) {
                View previous = view;
                view = new View(Status.ERROR, previous.pack, previous.installed, null, previous.repository,
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            }
        }, executor);
    }

    static Settings settings(Path root, JsonObject json) throws IOException {
        require(json.has("schemaVersion") && json.get("schemaVersion").getAsInt() == 1, "Unsupported checker schema");
        JsonObject check = json.getAsJsonObject("check");
        require(text(check, "source").equals("github-tags"), "Unsupported check source");
        JsonObject rules = check.getAsJsonObject("versionRules");
        require(text(rules, "template").equals("numeric-four"), "Unsupported version template");
        require(text(rules, "channelPolicy").equals("follow-installed"), "Unsupported channel policy");
        String repository = text(check, "repository");
        require(repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"), "Expected GitHub owner/repository");
        Path relative = Path.of(text(json.getAsJsonObject("installed"), "releaseInfoPath"));
        Path marker = root.resolve(relative).normalize();
        require(!relative.isAbsolute() && marker.startsWith(root) && !marker.equals(root), "Marker must be inside game directory");
        return new Settings(text(json, "displayName"), repository, marker,
                !check.has("onMainMenu") || check.get("onMainMenu").getAsBoolean());
    }

    static List<Tag> pages(PageFetcher fetcher) throws Exception {
        var tags = new ArrayList<Tag>();
        for (int page = 1; page <= 20; page++) {
            JsonArray entries = fetcher.fetch(page);
            for (var entry : entries) {
                var json = entry.getAsJsonObject();
                String name = text(json, "name");
                if (PackVersion.parse(name).isEmpty()) continue;
                String commit = text(json.getAsJsonObject("commit"), "sha");
                require(commit.matches("[0-9a-fA-F]{40}"), "Invalid tag commit: " + name);
                tags.add(new Tag(name, commit));
            }
            if (entries.size() < 100) return tags;
        }
        if (fetcher.fetch(21).isEmpty()) return tags;
        throw new IOException("Too many tag pages; refusing an incomplete version list");
    }

    static Tag recommend(String installed, List<Tag> tags) throws IOException {
        PackVersion current = PackVersion.parse(installed).orElseThrow(() -> new IOException("Unsupported installed version"));
        Tag best = null;
        PackVersion bestVersion = current;
        String ambiguity = null;
        for (Tag tag : tags) {
            var candidate = PackVersion.parse(tag.name);
            if (candidate.isEmpty()) continue;
            PackVersion version = candidate.get();
            if (current.test() == null && version.test() != null) continue;
            int order = version.compareTo(bestVersion);
            if (order > 0) { best = tag; bestVersion = version; ambiguity = null; }
            else if (order == 0 && best != null && !best.commit.equalsIgnoreCase(tag.commit)) {
                ambiguity = "Ambiguous version tags: " + best.name + " / " + tag.name;
            }
        }
        if (ambiguity != null) throw new IOException(ambiguity);
        return best;
    }

    private static JsonObject read(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) { return JsonParser.parseReader(reader).getAsJsonObject(); }
    }
    private static String text(JsonObject json, String key) throws IOException {
        require(json != null && json.has(key) && json.get(key).isJsonPrimitive()
                && json.get(key).getAsJsonPrimitive().isString(), "Missing string field: " + key);
        String value = json.get(key).getAsString();
        require(!value.isBlank(), "Empty field: " + key);
        return value;
    }
    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
