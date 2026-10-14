package com.patchpilot.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

@Component
public class ReplayProvider implements PatchProvider {
    private static final Map<String, String> EXPECTED_TYPES = Map.of(
            "users", "KeyError",
            "pagination", "TypeError",
            "discount", "KeyError",
            "inventory", "ZeroDivisionError");
    private static final Map<String, String> ROUTES = Map.of(
            "users", "/users/1",
            "pagination", "/orders?page=2",
            "discount", "/cart/discount",
            "inventory", "/inventory/sync");

    private final Path cacheDirectory;
    private final ObjectMapper objectMapper;

    public ReplayProvider(@Value("${patchpilot.replay-cache-path:../demo/replay_cache}") String cachePath,
                          ObjectMapper objectMapper) {
        this.cacheDirectory = Path.of(cachePath).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    public String bugForPath(Object requestValue) {
        if (!(requestValue instanceof Map<?, ?> request)) return null;
        Object pathValue = request.get("path");
        if (pathValue == null) return null;
        String path = pathValue.toString();
        if (path.startsWith("/users/")) return "users";
        if ("/orders".equals(path)) return "pagination";
        if ("/cart/discount".equals(path)) return "discount";
        if ("/inventory/sync".equals(path)) return "inventory";
        if (path.startsWith("/payments")) return "payments";
        return null;
    }

    @Override
    public Optional<ReplayFix> find(String bugId) {
        String expectedType = EXPECTED_TYPES.get(bugId);
        String route = ROUTES.get(bugId);
        if (expectedType == null || route == null) return Optional.empty();
        String prompt = "PatchPilot replay v1\nBug id: " + bugId + "\nException type: " + expectedType + "\nPath: " + route;
        try {
            Path entry = cacheDirectory.resolve(sha256(prompt) + ".json");
            if (!Files.isRegularFile(entry)) return Optional.empty();
            ReplayFix fix = objectMapper.readValue(entry.toFile(), ReplayFix.class);
            if (!bugId.equals(fix.bugId()) || !expectedType.equals(fix.expectedExceptionType())) return Optional.empty();
            return Optional.of(fix);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public String expectedExceptionType(String bugId) { return EXPECTED_TYPES.get(bugId); }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
