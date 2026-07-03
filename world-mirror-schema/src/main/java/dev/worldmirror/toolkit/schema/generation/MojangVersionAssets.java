package dev.worldmirror.toolkit.schema.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.worldmirror.toolkit.core.ToolkitException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Downloads and prepares launcher metadata, client jars, and libraries from Mojang metadata. */
public final class MojangVersionAssets {
    public static final String VERSION_MANIFEST_V2 = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    public PreparedAssets prepare(Options options) throws IOException, InterruptedException {
        Objects.requireNonNull(options, "options");
        Files.createDirectories(options.workDir());
        Path versionJson = options.versionJson();
        if (versionJson == null) {
            versionJson = resolveVersionJson(options.minecraftVersion(), options.workDir());
        }
        JsonNode version = mapper.readTree(versionJson.toFile());
        Path clientJar = options.clientJar();
        if (clientJar == null) {
            clientJar = downloadClientJar(version, options.workDir());
        }
        List<Path> libraries = downloadLibraries(version, options.workDir().resolve("libraries"));
        return new PreparedAssets(version.path("id").asText(options.minecraftVersion()), versionJson, clientJar, libraries, version);
    }

    private Path resolveVersionJson(String minecraftVersion, Path workDir) throws IOException, InterruptedException {
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            throw new ToolkitException("minecraft version is required when --version-json is not provided");
        }
        Path manifestPath = workDir.resolve("version_manifest_v2.json");
        download(URI.create(VERSION_MANIFEST_V2), manifestPath);
        JsonNode manifest = mapper.readTree(manifestPath.toFile());
        for (JsonNode version : manifest.path("versions")) {
            if (minecraftVersion.equals(version.path("id").asText())) {
                Path out = workDir.resolve(minecraftVersion + ".json");
                download(URI.create(version.path("url").asText()), out);
                return out;
            }
        }
        throw new ToolkitException("Minecraft version not found in Mojang manifest: " + minecraftVersion);
    }

    private Path downloadClientJar(JsonNode version, Path workDir) throws IOException, InterruptedException {
        JsonNode client = version.path("downloads").path("client");
        if (client.isMissingNode() || client.path("url").asText().isBlank()) {
            throw new ToolkitException("version JSON does not contain downloads.client.url");
        }
        Path out = workDir.resolve("client-" + version.path("id").asText("minecraft") + ".jar");
        download(URI.create(client.path("url").asText()), out);
        return out;
    }

    private List<Path> downloadLibraries(JsonNode version, Path libraryDir) throws IOException, InterruptedException {
        Files.createDirectories(libraryDir);
        List<Path> out = new ArrayList<>();
        for (JsonNode library : version.path("libraries")) {
            JsonNode artifact = library.path("downloads").path("artifact");
            if (artifact.isMissingNode() || artifact.path("url").asText().isBlank()) {
                continue;
            }
            String artifactPath = artifact.path("path").asText();
            Path target = libraryDir.resolve(artifactPath.replace('/', '_'));
            download(URI.create(artifact.path("url").asText()), target);
            out.add(target);
        }
        return List.copyOf(out);
    }

    private void download(URI uri, Path target) throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("download failed " + response.statusCode() + " " + uri);
        }
        try (InputStream in = response.body()) {
            Files.copy(in, target);
        }
    }

    public record Options(String minecraftVersion, Path versionJson, Path clientJar, Path workDir) {
        public Options {
            Objects.requireNonNull(workDir, "workDir");
        }
    }

    public record PreparedAssets(String versionId, Path versionJson, Path clientJar, List<Path> libraries, JsonNode versionJsonNode) {}
}
