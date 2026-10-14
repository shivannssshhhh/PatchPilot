package com.patchpilot.pipeline;

import com.patchpilot.llm.ReplayFix;
import com.patchpilot.llm.ReplayProvider;
import com.patchpilot.llm.PatchProvider;
import com.patchpilot.model.Incident;
import com.patchpilot.policy.PatchPolicy;
import com.patchpilot.repository.IncidentRepository;
import com.patchpilot.sandbox.SandboxResult;
import com.patchpilot.sandbox.SandboxService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class RepairPipeline {
    private final IncidentRepository repository;
    private final IncidentEvents events;
    private final SandboxService sandbox;
    private final ReplayProvider replay;
    private final PatchProvider patchProvider;
    private final PatchPolicy policy;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final long delayMs;

    public RepairPipeline(IncidentRepository repository, IncidentEvents events, SandboxService sandbox,
                        ReplayProvider replay, PatchProvider patchProvider, PatchPolicy policy,
                        @Value("${patchpilot.pipeline.delay-ms:350}") long delayMs) {
        this.repository = repository;
        this.events = events;
        this.sandbox = sandbox;
        this.replay = replay;
        this.patchProvider = patchProvider;
        this.policy = policy;
        this.delayMs = Math.max(0, delayMs);
    }

    public void start(String incidentId) { executor.submit(() -> repair(incidentId)); }

    @PreDestroy
    public void stop() { executor.shutdownNow(); }

    private void repair(String incidentId) {
        Path workspace = null;
        try {
            Incident incident = repository.findById(incidentId).orElse(null);
            if (incident == null || !"captured".equals(incident.status())) return;
            progress(incidentId, "triaging", "Classifying the incident against the local replay fixtures.", Map.of());
            pause();
            incident = repository.findById(incidentId).orElse(null);
            if (incident == null) return;

            String bug = replay.bugForPath(incident.payload().get("request"));
            if (bug == null) {
                finish(incidentId, "failed", "This incident does not match a supported ShopLite replay fixture. No patch was proposed.", Map.of());
                return;
            }
            String sensitiveSurface = requestPath(incident.payload()) + " " + String.valueOf(incident.payload().get("stackTrace"));
            if ("payments".equals(bug) || policy.touchesSensitivePath(sensitiveSurface)) {
                finish(incidentId, "declined", "PatchPilot refused to propose a change because this incident affects a sensitive payments path.", Map.of("reason", "sensitive_path"));
                return;
            }
            ReplayFix fix = patchProvider.find(bug).orElse(null);
            if (fix == null) {
                finish(incidentId, "failed", "No deterministic replay fixture matched this incident. No patch was proposed.", Map.of());
                return;
            }
            Object reportedType = incident.payload().get("exceptionType");
            if (reportedType == null || !reportedType.toString().equals(fix.expectedExceptionType())) {
                finish(incidentId, "declined", "The replay fixture's expected exception type did not match the incident, so PatchPilot stopped.", Map.of("expected", fix.expectedExceptionType(), "reported", String.valueOf(reportedType)));
                return;
            }

            progress(incidentId, "reproducing", "Running the incident reproduction in a temporary sandbox copy.", Map.of("bugId", bug));
            workspace = sandbox.createWorkspace();
            SandboxResult reproduction = sandbox.runInWorkspace(workspace, bug, false);
            boolean reproduced = !reproduction.timedOut() && reproduction.exitCode() != 0
                    && reproduction.output().contains("test_incident_reproduces_as_http_error");
            repository.addArtifact(incidentId, "reproduction_result", Map.of(
                    "bugId", bug, "expectedExceptionType", fix.expectedExceptionType(),
                    "confirmed", reproduced, "sandbox", reproduction.mode(),
                    "exitCode", reproduction.exitCode(), "timedOut", reproduction.timedOut(),
                    "durationMs", reproduction.durationMillis(), "output", reproduction.output()));
            if (!reproduced) {
                finish(incidentId, "declined", "The seeded failure could not be reproduced in the sandbox. No patch was proposed.", Map.of("sandbox", reproduction.mode(), "output", reproduction.output()));
                return;
            }

            progress(incidentId, "patching", "Loading a deterministic, prompt-hash replay patch.", Map.of("provider", "replay"));
            String diff = applyReplayFix(workspace, fix);
            progress(incidentId, "verifying", "Running the full ShopLite suite and the reproduction check after the patch.", Map.of("sandbox", "isolated_copy"));
            SandboxResult verification = sandbox.runInWorkspace(workspace, bug, true);
            repository.addArtifact(incidentId, "verification_result", Map.of(
                    "passed", verification.passed(), "sandbox", verification.mode(),
                    "exitCode", verification.exitCode(), "timedOut", verification.timedOut(),
                    "durationMs", verification.durationMillis(), "output", verification.output()));
            if (!verification.passed()) {
                finish(incidentId, "failed", "The full suite or reproduction check failed after the candidate patch. It is not available for approval.", Map.of("output", verification.output()));
                return;
            }

            progress(incidentId, "scoring", "Checking patch scope, sensitive paths, and evidence.", Map.of());
            PatchPolicy.Decision decision = policy.evaluate(fix, reproduced, verification.passed());
            if (!decision.accepted()) {
                finish(incidentId, "declined", decision.reason(), Map.of("riskScore", decision.riskScore()));
                return;
            }
            repository.addArtifact(incidentId, "verified_patch", Map.of(
                    "bugId", bug, "filePath", fix.filePath(), "diff", diff,
                    "explanation", fix.explanation(), "riskScore", decision.riskScore(),
                    "riskVerdict", decision.reason(), "reproducedBeforePatch", true,
                    "fullSuitePassedAfterPatch", true, "sandbox", verification.mode()));
            finish(incidentId, "ready_for_review", "The candidate patch is available with reproduction and verification evidence. Human review is required.", Map.of("riskScore", decision.riskScore(), "riskVerdict", decision.reason()));
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            finish(incidentId, "failed", "Repair stopped safely: " + message, Map.of("exceptionType", exception.getClass().getSimpleName()));
        } finally {
            sandbox.deleteWorkspace(workspace);
        }
    }

    private String applyReplayFix(Path workspace, ReplayFix fix) throws IOException {
        Path file = workspace.resolve(fix.filePath()).normalize();
        if (!file.startsWith(workspace.normalize()) || !Files.isRegularFile(file)) {
            throw new IOException("Replay patch path is outside the sandbox copy");
        }
        String source = Files.readString(file);
        int matches = occurrences(source, fix.expected());
        if (matches != 1) throw new IOException("Replay patch anchor must match exactly once; found " + matches);
        Files.writeString(file, source.replace(fix.expected(), fix.replacement()));
        return unifiedDiff("sample_app/" + fix.filePath(), fix.expected(), fix.replacement(), source);
    }

    private String unifiedDiff(String path, String oldValue, String newValue, String source) {
        int offset = source.indexOf(oldValue);
        int line = 1 + (int) source.substring(0, offset).chars().filter(character -> character == '\n').count();
        String[] oldLines = oldValue.split("\\R", -1);
        String[] newLines = newValue.split("\\R", -1);
        String[] sourceLines = source.split("\\R", -1);
        int targetIndex = line - 1;
        String before = targetIndex > 0 ? sourceLines[targetIndex - 1] : null;
        int afterIndex = targetIndex + oldLines.length;
        String after = afterIndex < sourceLines.length ? sourceLines[afterIndex] : null;
        int contextBefore = before == null ? 0 : 1;
        int contextAfter = after == null ? 0 : 1;
        int oldStart = line - contextBefore;
        int oldCount = contextBefore + oldLines.length + contextAfter;
        int newCount = contextBefore + newLines.length + contextAfter;
        StringBuilder diff = new StringBuilder("--- a/").append(path).append("\n+++ b/").append(path).append("\n@@ -").append(oldStart).append(',').append(oldCount)
                .append(" +").append(oldStart).append(',').append(newCount).append(" @@\n");
        if (before != null) diff.append(' ').append(before).append('\n');
        for (String oldLine : oldLines) diff.append('-').append(oldLine).append('\n');
        for (String newLine : newLines) diff.append('+').append(newLine).append('\n');
        if (after != null) diff.append(' ').append(after).append('\n');
        return diff.toString();
    }

    private int occurrences(String source, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(value, offset)) >= 0) { count++; offset += value.length(); }
        return count;
    }

    private String requestPath(Map<String, Object> payload) {
        Object request = payload.get("request");
        if (request instanceof Map<?, ?> data && data.get("path") != null) return data.get("path").toString();
        return "";
    }

    private void progress(String id, String status, String message, Map<String, Object> details) {
        Incident current = repository.findById(id).orElse(null);
        if (current == null) return;
        saveStatus(current, status, null);
        Map<String, Object> stepDetails = new LinkedHashMap<>(details);
        stepDetails.put("message", message);
        repository.addStep(id, status, "completed", stepDetails);
        publish(id, status);
        pause();
    }

    private void finish(String id, String status, String report, Map<String, Object> details) {
        Incident current = repository.findById(id).orElse(null);
        if (current == null) return;
        String declineReport = "declined".equals(status) || "failed".equals(status) ? report : null;
        saveStatus(current, status, declineReport);
        Map<String, Object> stepDetails = new LinkedHashMap<>(details);
        stepDetails.put("message", report);
        repository.addStep(id, status, "completed", stepDetails);
        if ("declined".equals(status) || "failed".equals(status)) {
            repository.addArtifact(id, "decline_report", Map.of("message", report));
        }
        publish(id, status);
    }

    private void saveStatus(Incident current, String status, String report) {
        Incident updated = new Incident(current.id(), status, current.payload(), current.createdAt(), Instant.now(), report);
        repository.save(updated);
    }

    private void publish(String id, String status) {
        Incident updated = repository.findById(id).orElse(null);
        if (updated == null) return;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "status_changed");
        event.put("incidentId", id);
        event.put("status", status);
        event.put("updatedAt", updated.updatedAt().toString());
        events.publish(id, event);
    }

    private void pause() {
        if (delayMs <= 0) return;
        try { Thread.sleep(delayMs); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
