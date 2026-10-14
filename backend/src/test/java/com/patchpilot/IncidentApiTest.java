package com.patchpilot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:file:patchpilot-test?mode=memory&cache=shared",
        "patchpilot.pipeline.delay-ms=0"
})
class IncidentApiTest {
    @Autowired
    private TestRestTemplate rest;

    @Test
    void healthEndpointReportsHealthy() {
        ResponseEntity<Map> response = rest.getForEntity("/api/health", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "healthy");
    }

    @Test
    void capturePersistsIncidentStepsAndPayloadArtifact() throws Exception {
        Map<String, Object> payload = Map.of(
                "exceptionType", "TypeError",
                "message", "slice indices must be integers",
                "stackTrace", "traceback text",
                "request", Map.of("method", "GET", "path", "/orders", "params", Map.of("page", "2")),
                "logs", java.util.List.of("sample log"));

        ResponseEntity<Map> created = rest.postForEntity("/api/incidents", payload, Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(created.getBody()).containsKeys("id", "status", "payload");
        String id = (String) created.getBody().get("id");

        ResponseEntity<Map> detail = rest.getForEntity("/api/incidents/{id}", Map.class, id);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody()).containsKeys("incident", "steps", "artifacts");
        Map incident = (Map) detail.getBody().get("incident");
        assertThat(incident.get("id")).isEqualTo(id);
        assertThat(incident.get("payload")).isEqualTo(payload);
        awaitReadyForReview(id);
        detail = rest.getForEntity("/api/incidents/{id}", Map.class, id);
        incident = (Map) detail.getBody().get("incident");
        assertThat(incident.get("status")).isEqualTo("ready_for_review");
        java.util.List<Map> steps = (java.util.List<Map>) detail.getBody().get("steps");
        assertThat(steps).extracting(step -> step.get("name")).contains("triaging", "reproducing", "patching", "verifying", "scoring", "ready_for_review");
        assertThat((java.util.List<?>) detail.getBody().get("artifacts")).hasSize(4);

        ResponseEntity<String> patch = rest.getForEntity("/api/incidents/{id}/patch", String.class, id);
        assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patch.getHeaders().getFirst("Content-Disposition")).contains("attachment");
        assertThat(patch.getBody()).contains("start = page_value * 2", "start = (max(1, int(page_value)) - 1) * 2");
        assertPatchAppliesFromRepositoryRoot(patch.getBody());

        ResponseEntity<java.util.List> list = rest.exchange("/api/incidents", HttpMethod.GET, HttpEntity.EMPTY, java.util.List.class);
        assertThat(list.getBody()).anySatisfy(row -> assertThat(((Map) row).get("id")).isEqualTo(id));
    }

    @Test
    void paymentIncidentsAreDeclinedWithAnHonestReport() throws InterruptedException {
        Map<String, Object> payload = Map.of(
                "exceptionType", "RuntimeException",
                "message", "payment processor simulation failed",
                "request", Map.of("method", "POST", "path", "/payments/charge"));
        ResponseEntity<Map> created = rest.postForEntity("/api/incidents", payload, Map.class);
        String id = (String) created.getBody().get("id");

        for (int attempt = 0; attempt < 60; attempt++) {
            ResponseEntity<Map> response = rest.getForEntity("/api/incidents/{id}", Map.class, id);
            Map incident = (Map) response.getBody().get("incident");
            if ("declined".equals(incident.get("status"))) {
                assertThat(incident.get("declineReport").toString()).contains("payments path");
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Payment incident did not receive its refusal report");
    }

    @Test
    void supportedReplayFixturesPrepareVerifiedPatches() throws Exception {
        java.util.List<Map<String, Object>> cases = java.util.List.of(
                Map.of("exceptionType", "KeyError", "message", "'naem'", "request", Map.of("path", "/users/1")),
                Map.of("exceptionType", "KeyError", "message", "'FORG10'", "request", Map.of("path", "/cart/discount")),
                Map.of("exceptionType", "ZeroDivisionError", "message", "integer division or modulo by zero", "request", Map.of("path", "/inventory/sync")));
        for (Map<String, Object> payload : cases) {
            ResponseEntity<Map> created = rest.postForEntity("/api/incidents", payload, Map.class);
            String id = (String) created.getBody().get("id");
            awaitReadyForReview(id);
            ResponseEntity<String> patch = rest.getForEntity("/api/incidents/{id}/patch", String.class, id);
            assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(patch.getBody()).contains("--- a/sample_app/shoplite/__init__.py", "+++ b/sample_app/shoplite/__init__.py");
            assertPatchAppliesFromRepositoryRoot(patch.getBody());
        }
    }

    @Test
    void backendRedactsSecretsAndEmailsBeforePersistence() {
        Map<String, Object> payload = Map.of(
                "message", "token=abc123 contact a.user@example.test",
                "logs", java.util.List.of("password: hunter2"));
        ResponseEntity<Map> created = rest.postForEntity("/api/incidents", payload, Map.class);
        String id = (String) created.getBody().get("id");
        ResponseEntity<Map> detail = rest.getForEntity("/api/incidents/{id}", Map.class, id);
        String serialized = detail.getBody().toString();

        assertThat(serialized).doesNotContain("abc123", "hunter2", "a.user@example.test");
        assertThat(serialized).contains("[REDACTED]", "[REDACTED-EMAIL]");
    }

    private void awaitReadyForReview(String id) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            ResponseEntity<Map> response = rest.getForEntity("/api/incidents/{id}", Map.class, id);
            Map incident = (Map) response.getBody().get("incident");
            if ("ready_for_review".equals(incident.get("status"))) return;
            if ("failed".equals(incident.get("status")) || "declined".equals(incident.get("status"))) {
                throw new AssertionError("Pipeline ended early: " + incident.get("declineReport") + " artifacts=" + response.getBody().get("artifacts"));
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Replay repair pipeline did not prepare a verified patch in time");
    }

    private void assertPatchAppliesFromRepositoryRoot(String patch) throws Exception {
        java.nio.file.Path patchFile = java.nio.file.Files.createTempFile("patchpilot-", ".patch");
        try {
            java.nio.file.Files.writeString(patchFile, patch);
            Process process = new ProcessBuilder("git", "apply", "--check", patchFile.toString())
                    .directory(java.nio.file.Path.of("..").toAbsolutePath().normalize().toFile())
                    .redirectErrorStream(true).start();
            assertThat(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output + "\nPATCH:\n" + patch).isZero();
        } finally {
            java.nio.file.Files.deleteIfExists(patchFile);
        }
    }
}
