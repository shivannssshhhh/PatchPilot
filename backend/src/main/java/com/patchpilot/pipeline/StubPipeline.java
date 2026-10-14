package com.patchpilot.pipeline;

import com.patchpilot.model.Incident;
import com.patchpilot.repository.IncidentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class StubPipeline {
    private static final List<String> STATES = List.of("triaging");
    private final IncidentRepository repository;
    private final IncidentEvents events;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final long delayMs;

    public StubPipeline(IncidentRepository repository, IncidentEvents events,
                        @Value("${patchpilot.pipeline.delay-ms:350}") long delayMs) {
        this.repository = repository;
        this.events = events;
        this.delayMs = Math.max(0, delayMs);
    }

    public void start(String incidentId) { executor.submit(() -> advance(incidentId)); }

    @PreDestroy
    public void stop() { executor.shutdownNow(); }

    public void advance(String incidentId) {
        for (String state : STATES) {
            if (delayMs > 0) {
                try { Thread.sleep(delayMs); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
            }
            Incident current = repository.findById(incidentId).orElse(null);
            if (current == null || !("captured".equals(current.status()) || STATES.contains(current.status()))) return;
            Incident updated = new Incident(current.id(), state, current.payload(), current.createdAt(), Instant.now(), current.declineReport());
            repository.save(updated);
            repository.addStep(incidentId, state, "completed", Map.of("message", "Stub pipeline advanced to " + state));
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "status_changed");
            event.put("incidentId", incidentId);
            event.put("status", state);
            event.put("updatedAt", updated.updatedAt().toString());
            events.publish(incidentId, event);
        }
        Incident current = repository.findById(incidentId).orElse(null);
        if (current == null || !"triaging".equals(current.status())) return;
        boolean sensitive = touchesPayments(current.payload());
        String terminalStatus = sensitive ? "declined" : "failed";
        String report = sensitive
                ? "PatchPilot refused to propose a change because this incident affects the payments path, which is treated as sensitive. A human should inspect the payment processor and logs."
                : "No patch was generated or tested. This build only captures and triages incidents; automated repair is not implemented yet. Do not treat this incident as fixed.";
        Incident terminal = new Incident(current.id(), terminalStatus, current.payload(), current.createdAt(), Instant.now(), report);
        repository.save(terminal);
        repository.addStep(incidentId, terminalStatus, "completed", Map.of("report", report));
        repository.addArtifact(incidentId, sensitive ? "decline_report" : "pipeline_limitation", Map.of("message", report));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "status_changed");
        event.put("incidentId", incidentId);
        event.put("status", terminalStatus);
        event.put("updatedAt", terminal.updatedAt().toString());
        events.publish(incidentId, event);
    }

    private boolean touchesPayments(Map<String, Object> payload) {
        Object request = payload.get("request");
        if (request instanceof Map<?, ?> requestDetails) {
            Object path = requestDetails.get("path");
            if (path != null && path.toString().toLowerCase().contains("payment")) return true;
        }
        Object trace = payload.get("stackTrace");
        return trace != null && trace.toString().toLowerCase().contains("payment");
    }
}
