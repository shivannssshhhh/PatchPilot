package com.patchpilot.model;

import java.util.List;

public record IncidentDetail(Incident incident, List<PipelineStep> steps, List<Artifact> artifacts) {
}
