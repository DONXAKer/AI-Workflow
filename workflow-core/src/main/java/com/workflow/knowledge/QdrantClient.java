package com.workflow.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Thin REST client for Qdrant — only the surface the project indexer / search tool
 * actually uses: collection management, point upsert, vector search, and delete-by-filter.
 *
 * <p>Disabled by default. Activates when {@code workflow.knowledge.qdrant.url} is set
 * (see {@code application.yaml}). All operations swallow transport failures and log a
 * warning — the higher layer falls back to no-results rather than crashing the run.
 */
@Component
public class QdrantClient {

    private static final Logger log = LoggerFactory.getLogger(QdrantClient.class);
    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final ObjectMapper mapper;
    private final WebClient client;

    @Autowired
    public QdrantClient(@Value("${workflow.knowledge.qdrant.url:}") String configuredUrl,
                        ObjectMapper mapper) {
        this.baseUrl = configuredUrl == null ? "" : configuredUrl.replaceAll("/+$", "");
        this.mapper = mapper;
        this.client = baseUrl.isBlank() ? null : WebClient.builder().baseUrl(baseUrl).build();
    }

    public boolean isEnabled() { return client != null; }

    /**
     * Ensures the collection exists with the given vector size. Idempotent: if the
     * collection already exists (any vector size) the call is a no-op.
     */
    public void ensureCollection(String collection, int vectorSize) {
        if (!isEnabled()) return;
        try {
            // GET /collections/<name> returns 404 if missing — we handle that as "create"
            String existing = client.get()
                .uri("/collections/{name}", collection)
                .retrieve()
                .onStatus(s -> s.value() == 404, r -> reactor.core.publisher.Mono.empty())
                .bodyToMono(String.class)
                .timeout(RPC_TIMEOUT)
                .onErrorReturn("")
                .block();
            if (existing != null && !existing.isEmpty() && mapper.readTree(existing).path("status").asText("").equals("ok")) {
                return;
            }
        } catch (Exception ignored) { /* fall through to create */ }

        try {
            ObjectNode body = mapper.createObjectNode();
            ObjectNode vectors = body.putObject("vectors");
            vectors.put("size", vectorSize);
            vectors.put("distance", "Cosine");
            client.put()
                .uri("/collections/{name}", collection)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(RPC_TIMEOUT)
                .block();
            log.info("Qdrant collection '{}' created (dim={})", collection, vectorSize);
        } catch (Exception e) {
            log.warn("Qdrant ensureCollection({}, {}) failed: {}", collection, vectorSize, e.getMessage());
        }
    }

    /** Inserts or updates points. {@code wait=true} so caller sees consistent state on return. */
    public boolean upsert(String collection, List<Point> points) {
        if (!isEnabled() || points.isEmpty()) return false;
        try {
            ObjectNode body = mapper.createObjectNode();
            ArrayNode arr = body.putArray("points");
            for (Point p : points) {
                ObjectNode node = arr.addObject();
                node.put("id", p.id);
                ArrayNode vec = node.putArray("vector");
                for (float f : p.vector) vec.add(f);
                node.set("payload", p.payload);
            }
            client.put()
                .uri(uriBuilder -> uriBuilder.path("/collections/{name}/points")
                    .queryParam("wait", "true").build(collection))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(RPC_TIMEOUT)
                .block();
            return true;
        } catch (Exception e) {
            log.warn("Qdrant upsert({}, {} points) failed: {}", collection, points.size(), e.getMessage());
            return false;
        }
    }

    /** Returns the top-K most similar points to {@code queryVector}. */
    public List<SearchResult> search(String collection, float[] queryVector, int limit) {
        if (!isEnabled()) return List.of();
        try {
            ObjectNode body = mapper.createObjectNode();
            ArrayNode vec = body.putArray("vector");
            for (float f : queryVector) vec.add(f);
            body.put("limit", Math.max(1, Math.min(limit, 50)));
            body.put("with_payload", true);

            String resp = client.post()
                .uri("/collections/{name}/points/search", collection)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(RPC_TIMEOUT)
                .block();
            JsonNode result = mapper.readTree(resp == null ? "{}" : resp).path("result");
            List<SearchResult> out = new ArrayList<>();
            if (result.isArray()) {
                for (JsonNode hit : result) {
                    out.add(new SearchResult(
                        hit.path("id").asText(""),
                        hit.path("score").asDouble(0.0),
                        hit.path("payload")));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Qdrant search({}) failed: {}", collection, e.getMessage());
            return List.of();
        }
    }

    /**
     * Deletes all points whose {@code path} payload field equals one of {@code paths}.
     * Used to drop stale chunks before re-upserting a re-indexed file.
     */
    public boolean deleteByPaths(String collection, List<String> paths) {
        if (!isEnabled() || paths.isEmpty()) return false;
        try {
            ObjectNode body = mapper.createObjectNode();
            ObjectNode filter = body.putObject("filter");
            ArrayNode should = filter.putArray("should");
            for (String path : paths) {
                ObjectNode match = should.addObject();
                match.put("key", "path");
                match.putObject("match").put("value", path);
            }
            client.post()
                .uri(uriBuilder -> uriBuilder.path("/collections/{name}/points/delete")
                    .queryParam("wait", "true").build(collection))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(RPC_TIMEOUT)
                .block();
            return true;
        } catch (Exception e) {
            log.warn("Qdrant deleteByPaths({}, {} paths) failed: {}", collection, paths.size(), e.getMessage());
            return false;
        }
    }

    /** Builds a stable UUID-shaped point id from project + path + chunk index. */
    public static String pointId(String projectSlug, String path, int chunkIndex) {
        return UUID.nameUUIDFromBytes((projectSlug + ":" + path + ":" + chunkIndex)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /** Vector point bound for upsert. {@code id} should be deterministic per (project, path, chunk). */
    public record Point(String id, float[] vector, ObjectNode payload) {}

    /** Result of {@link #search} — payload is the original JSON object stored at upsert time. */
    public record SearchResult(String id, double score, JsonNode payload) {}
}
