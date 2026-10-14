package com.patchpilot.service;

import com.patchpilot.model.Artifact;
import com.patchpilot.model.Incident;
import com.patchpilot.model.IncidentDetail;
import com.patchpilot.model.PipelineStep;
import com.patchpilot.pipeline.IncidentEvents;
import com.patchpilot.pipeline.StubPipeline;
import com.patchpilot.repository.IncidentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class IncidentService {
    private final IncidentRepository repository;
    private final IncidentEvents events;
    private final StubPipeline pipeline;

    public IncidentService(IncidentRepository repository, IncidentEvents events, StubPipeline pipeline) {
        this.repository = repository;
        this.events = events;
        this.pipeline = pipeline;
    }

    public Incident capture(Map<String, Object> payload) {
        Instant now = Instant.now();
        Incident incident = new Incident(UUID.randomUUID().toString(), "captured", new LinkedHashMap<>(payload), now, now, null);
        repository.save(incident);
        repository.addStep(incident.id(), "capture", "completed", Map.of("message", "Incident payload stored"));
        repository.addArtifact(incident.id(), "incident_payload", payload);
        publishStatus(incident);
        pipeline.start(incident.id());
        return incident;
    }

    public List<Incident> list() { return repository.findAll(); }

    public IncidentDetail detail(String id) {
        Incident incident = get(id);
        List<PipelineStep> steps = repository.stepsFor(id);
        List<Artifact> artifacts = repository.artifactsFor(id);
        return new IncidentDetail(incident, steps, artifacts);
    }

    public Incident get(String id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident not found"));
    }

    public Incident approve(String id) {
        Incident incident = get(id);
        if (!"ready_for_review".equals(incident.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Incident is not ready for review");
        boolean verifiedPatchExists = repository.artifactsFor(id).stream().anyMatch(artifact -> "verified_patch".equals(artifact.type()));
        if (!verifiedPatchExists) throw new ResponseStatusException(HttpStatus.CONFLICT, "Approval requires a verified patch artifact; none is available for this incident");
        return changeStatus(incident, "approved", null);
    }

    public Incident reject(String id, String reason) {
        Incident incident = get(id);
        if (!"ready_for_review".equals(incident.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Incident is not ready for review");
        return changeStatus(incident, "rejected", reason == null || reason.isBlank() ? "Rejected by reviewer" : reason);
    }

    public Incident retry(String id) {
        Incident incident = get(id);
        if (!List.of("ready_for_review", "rejected", "declined", "failed").contains(incident.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Incident cannot be retried in its current state");
        }
        Instant now = Instant.now();
        Incident reset = new Incident(incident.id(), "captured", incident.payload(), incident.createdAt(), now, null);
        repository.save(reset);
        repository.addStep(id, "retry", "completed", Map.of("message", "Pipeline retry requested"));
        publishStatus(reset);
        pipeline.start(id);
        return reset;
    }

    private Incident changeStatus(Incident incident, String status, String reason) {
        Incident updated = new Incident(incident.id(), status, incident.payload(), incident.createdAt(), Instant.now(), reason);
        repository.save(updated);
        repository.addStep(incident.id(), status, "completed", reason == null ? Map.of() : Map.of("reason", reason));
        publishStatus(updated);
        return updated;
    }

    private void publishStatus(Incident incident) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "status_changed");
        event.put("incidentId", incident.id());
        event.put("status", incident.status());
        event.put("updatedAt", incident.updatedAt().toString());
        events.publish(incident.id(), event);
    }
}
