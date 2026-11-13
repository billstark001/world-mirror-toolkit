package dev.worldmirror.toolkit.anvil;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Checks replay endpoint provenance against the selected mirror's saved lineage. */
final class ReplaySourceRouting {
    private static final int GRAPH_SCHEMA = 2;
    private static final int MAX_GRAPH_BYTES = 16 * 1024 * 1024;
    private static final int DEFAULT_PORT = 25565;
    private static final String SERVER_PREFIX = "server:";
    private static final ObjectMapper JSON = new ObjectMapper();

    record Evidence(ChunkImporter.Routing request, Path graph, List<String> candidates, List<String> lineage) {
        String source() {
            return lineage.isEmpty() ? null : lineage.getLast();
        }
    }

    private record Endpoint(String host, int port) {
        static Endpoint parse(String input) throws IOException {
            String address = input.strip();
            try {
                int firstColon = address.indexOf(':');
                String host;
                int port = DEFAULT_PORT;
                if (address.startsWith("[")) {
                    int close = address.indexOf(']');
                    if (close < 0) throw new IllegalArgumentException();
                    host = ipv6(address.substring(1, close));
                    if (close < address.length() - 1) {
                        if (address.charAt(close + 1) != ':') throw new IllegalArgumentException();
                        port = port(address.substring(close + 2));
                    }
                } else if (firstColon >= 0 && firstColon != address.lastIndexOf(':')) {
                    host = ipv6(address);
                } else {
                    String name = firstColon < 0 ? address : address.substring(0, firstColon);
                    if (name.isBlank() || name.equals(".")) throw new IllegalArgumentException();
                    host = IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
                    if (firstColon >= 0) port = port(address.substring(firstColon + 1));
                }
                return new Endpoint(host, port);
            } catch (IllegalArgumentException | java.net.UnknownHostException invalid) {
                throw new IOException("invalid replay routing endpoint: " + input, invalid);
            }
        }

        private static String ipv6(String host) throws java.net.UnknownHostException {
            int scopeStart = host.indexOf('%');
            String scope = scopeStart < 0 ? "" : host.substring(scopeStart);
            String literal = scopeStart < 0 ? host : host.substring(0, scopeStart);
            if (!literal.contains(":") || !literal.matches("[0-9a-fA-F:.]+")
                    || !scope.isEmpty() && !scope.matches("%[a-zA-Z0-9_.-]+")) {
                throw new IllegalArgumentException();
            }
            // The validated numeric literal cannot trigger DNS or a lookup of a local scope interface.
            return "[" + InetAddress.getByName(literal).getHostAddress() + scope + "]";
        }

        private static int port(String input) {
            if (input.isEmpty() || !input.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new IllegalArgumentException();
            }
            int port = Integer.parseInt(input);
            if (port < 1 || port > 65535) throw new IllegalArgumentException();
            return port;
        }
    }

    private ReplaySourceRouting() {}

    static Evidence verify(Path destination, String mirrorId, String parentId, String replayKey,
            ChunkImporter.Routing request, boolean allowMismatch) throws IOException {
        Path graph = graphPath(destination, request.worldGraph());
        if (parentId == null) {
            return unverified(mirrorId, request, graph, allowMismatch);
        }
        String server = replayKey != null && replayKey.startsWith(SERVER_PREFIX)
                ? replayKey.substring(SERVER_PREFIX.length()) : null;
        if (graph == null) {
            return unverified(mirrorId, request, graph, allowMismatch);
        }
        JsonNode document = readGraph(graph);
        if (server == null || server.equals("unknown")) {
            return unverified(mirrorId, request, graph, allowMismatch);
        }
        Endpoint endpoint;
        try {
            endpoint = Endpoint.parse(server);
        } catch (IOException invalidLabel) {
            return unverified(mirrorId, request, graph, allowMismatch);
        }
        Set<String> candidates = candidates(document, endpoint);
        if (candidates.isEmpty()) return unverified(mirrorId, request, graph, allowMismatch);

        String selected = request.replaySource();
        if (selected == null) {
            if (candidates.size() != 1) {
                throw new IOException("replay endpoint has multiple logical sources: " + String.join(", ", candidates)
                        + "; confirm one with --replay-source");
            }
            selected = candidates.iterator().next();
        } else if (!candidates.contains(selected)) {
            throw new IOException("--replay-source is not a candidate for the replay endpoint: " + selected);
        }
        List<String> lineage = lineage(document.path("nodes"), mirrorId, parentId);
        String root = lineage.getLast();
        if (!selected.equals(root)) {
            throw new IOException("replay source " + selected + " does not match mirror " + mirrorId
                    + " with upstream root " + root);
        }
        return new Evidence(request, graph, List.copyOf(candidates), lineage);
    }

    private static Evidence unverified(String mirrorId, ChunkImporter.Routing request, Path graph,
            boolean allowMismatch) throws IOException {
        if (request.replaySource() != null) {
            throw new IOException("cannot verify --replay-source without a world graph endpoint binding");
        }
        if (!allowMismatch) {
            throw new IOException("mirror " + mirrorId + " has no verifiable replay endpoint binding; "
                    + "supply --world-graph or use --allow-source-mismatch after verifying the target");
        }
        return new Evidence(request, graph, List.of(), List.of());
    }

    private static Path graphPath(Path save, Path requested) throws IOException {
        Path nativeGraph = nativeGraphPath(save);
        if (requested == null) return nativeGraph;
        Path file = requested.toAbsolutePath().normalize();
        ChunkImporter.requirePlainFile(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("world graph config is missing: " + file);
        }
        Path canonical = file.toRealPath();
        if (nativeGraph != null && !nativeGraph.equals(canonical)) {
            throw new IOException("--world-graph cannot override the destination's routing config: " + nativeGraph);
        }
        return canonical;
    }

    private static Path nativeGraphPath(Path save) throws IOException {
        Path worlds = save.getParent();
        if (worlds == null || worlds.getParent() == null) {
            return null;
        }
        String directory = worlds.getFileName().toString().toLowerCase(Locale.ROOT);
        Path conventionalRoot = worlds.resolveSibling(directory);
        if (!Set.of("saves", "downloaded_worlds").contains(directory)
                || !Files.isDirectory(conventionalRoot, LinkOption.NOFOLLOW_LINKS)
                || !Files.isSameFile(worlds, conventionalRoot)) return null;
        Path config = worlds.getParent().resolve("config");
        ChunkImporter.requirePlainDirectory(config);
        Path worldMirror = config.resolve("worldmirror");
        ChunkImporter.requirePlainDirectory(worldMirror);
        requireRecoveredConfig(worldMirror);
        Path file = worldMirror.resolve("world-graph.json");
        ChunkImporter.requirePlainFile(file);
        return Files.notExists(file, LinkOption.NOFOLLOW_LINKS) ? null : file;
    }

    private static JsonNode readGraph(Path path) throws IOException {
        requireRecoveredConfig(path.getParent());
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_GRAPH_BYTES + 1);
        }
        if (bytes.length > MAX_GRAPH_BYTES) throw new IOException("world graph exceeds size limit: " + path);
        JsonNode value = JSON.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
        var schema = value.path("schema");
        if (!schema.isIntegralNumber() || !schema.canConvertToInt() || schema.intValue() != GRAPH_SCHEMA
                || !value.path("endpointRules").isObject() || !value.path("nodes").isObject()) {
            throw new IOException("unsupported world graph config; refresh it in World Mirror: " + path);
        }
        return value;
    }

    private static void requireRecoveredConfig(Path directory) throws IOException {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var journals = Files.newDirectoryStream(directory, ".worldmirror-transaction-*")) {
            if (journals.iterator().hasNext()) {
                throw new IOException("world graph requires recovery in World Mirror: " + directory);
            }
        }
    }

    private static Set<String> candidates(JsonNode document, Endpoint endpoint) throws IOException {
        JsonNode rules = document.path("endpointRules");
        JsonNode rule = null;
        for (var fields = rules.fields(); fields.hasNext();) {
            var entry = fields.next();
            if (!endpoint.equals(Endpoint.parse(entry.getKey()))) continue;
            if (rule != null) {
                throw new IOException("invalid or duplicate endpoint rule in world graph: " + entry.getKey());
            }
            rule = entry.getValue();
        }
        if (rule == null) return Set.of();
        Set<String> candidates = sourceIds(rule);
        String group = rule.path("addressGroupId").textValue();
        String policy = rule.path("idPolicy").textValue();
        if (group == null || group.isBlank() || policy == null
                || !Set.of("ENDPOINT_ONLY", "PREFER_BOUND_ID", "REQUIRE_BOUND_ID").contains(policy)) {
            throw new IOException("unsupported address group or stable ID policy; refresh the graph in World Mirror");
        }
        if (policy.equals("ENDPOINT_ONLY")) return candidates;
        Set<String> groupSources = new HashSet<>(candidates);
        for (JsonNode sibling : rules) {
            if (group.equals(sibling.path("addressGroupId").textValue())) groupSources.addAll(sourceIds(sibling));
        }
        JsonNode bindings = document.path("stableBindings");
        if (!bindings.isArray()) throw new IOException("invalid stable ID bindings in world graph");
        for (JsonNode binding : bindings) {
            if (!group.equals(binding.path("addressGroupId").textValue())) continue;
            String source = binding.path("sourceId").textValue();
            if (!typed(source, "remote:")) throw new IOException("invalid stable ID source in world graph");
            if (groupSources.contains(source)) candidates.add(source);
        }
        return candidates;
    }

    private static Set<String> sourceIds(JsonNode rule) throws IOException {
        if (!rule.path("sourceIds").isArray()) throw new IOException("invalid logical sources in endpoint rule");
        Set<String> candidates = new TreeSet<>();
        for (JsonNode source : rule.path("sourceIds")) {
            String id = source.textValue();
            if (!typed(id, "remote:") || !candidates.add(id)) {
                throw new IOException("invalid or duplicate logical source in endpoint rule");
            }
        }
        return candidates;
    }

    private static List<String> lineage(JsonNode nodes, String mirrorId, String parentId) throws IOException {
        List<String> lineage = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(mirrorId);
        String id = parentId;
        while (id != null && seen.add(id)) {
            lineage.add(id);
            JsonNode node = nodes.path(id);
            if (typed(id, "remote:") && id.equals(node.path("id").textValue())
                    && "REMOTE".equals(node.path("kind").textValue()) && !node.hasNonNull("parentId")) {
                return List.copyOf(lineage);
            }
            if (!typed(id, "mirror:") || !id.equals(node.path("id").textValue())
                    || !"MIRROR".equals(node.path("kind").textValue())) break;
            id = node.path("parentId").textValue();
        }
        throw new IOException("mirror " + mirrorId + " has no verified remote upstream lineage");
    }

    private static boolean typed(String id, String prefix) {
        return id != null && id.startsWith(prefix) && !id.substring(prefix.length()).isBlank()
                && id.chars().noneMatch(Character::isISOControl);
    }
}
