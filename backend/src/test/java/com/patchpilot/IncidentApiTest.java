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
        "patchpilot.pipeline.delay-ms=20"
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
    void capturePersistsIncidentStepsAndPayloadArtifact() throws InterruptedException {
        Map<String, Object> payload = Map.of(
                "exceptionType", "ValueError",
                "message", "bad sample input",
                "stackTrace", "traceback text",
                "request", Map.of("method", "GET", "path", "/orders", "params", Map.of("page", "bad")),
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
        awaitTerminalStatus(id);
        detail = rest.getForEntity("/api/incidents/{id}", Map.class, id);
        incident = (Map) detail.getBody().get("incident");
        assertThat(incident.get("status")).isEqualTo("failed");
        java.util.List<Map> steps = (java.util.List<Map>) detail.getBody().get("steps");
        assertThat(steps).extracting(step -> step.get("name")).contains("triaging", "failed");
        assertThat((java.util.List<?>) detail.getBody().get("artifacts")).hasSize(2);

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

    private void awaitTerminalStatus(String id) throws InterruptedException {
        for (int attempt = 0; attempt < 60; attempt++) {
            ResponseEntity<Map> response = rest.getForEntity("/api/incidents/{id}", Map.class, id);
            Map incident = (Map) response.getBody().get("incident");
            if ("failed".equals(incident.get("status")) || "declined".equals(incident.get("status"))) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Honest stub pipeline did not reach a terminal status in time");
    }
}
