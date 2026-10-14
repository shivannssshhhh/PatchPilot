package com.patchpilot.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.patchpilot.model.Artifact;
import com.patchpilot.model.Incident;
import com.patchpilot.model.PipelineStep;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class IncidentRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public IncidentRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Incident save(Incident incident) {
        jdbc.update("""
                INSERT INTO incidents(id, status, payload, created_at, updated_at, decline_report)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET status=excluded.status, payload=excluded.payload,
                  updated_at=excluded.updated_at, decline_report=excluded.decline_report
                """, incident.id(), incident.status(), writeJson(incident.payload()),
                incident.createdAt().toString(), incident.updatedAt().toString(), incident.declineReport());
        return incident;
    }

    public Optional<Incident> findById(String id) {
        return jdbc.query("SELECT * FROM incidents WHERE id = ?", this::mapIncident, id).stream().findFirst();
    }

    public List<Incident> findAll() {
        return jdbc.query("SELECT * FROM incidents ORDER BY created_at DESC", this::mapIncident);
    }

    public PipelineStep addStep(String incidentId, String name, String status, Map<String, Object> details) {
        Instant now = Instant.now();
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO pipeline_steps(incident_id, name, status, details, created_at) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, incidentId);
            statement.setString(2, name);
            statement.setString(3, status);
            statement.setString(4, writeJson(details));
            statement.setString(5, now.toString());
            return statement;
        }, key);
        Number generated = key.getKey();
        Long id = generated == null ? null : generated.longValue();
        return new PipelineStep(id == null ? 0 : id, incidentId, name, status, details, now);
    }

    public List<PipelineStep> stepsFor(String incidentId) {
        return jdbc.query("SELECT * FROM pipeline_steps WHERE incident_id = ? ORDER BY id",
                (rs, row) -> new PipelineStep(rs.getLong("id"), rs.getString("incident_id"),
                        rs.getString("name"), rs.getString("status"), readJson(rs.getString("details")),
                        Instant.parse(rs.getString("created_at"))), incidentId);
    }

    public Artifact addArtifact(String incidentId, String type, Map<String, Object> content) {
        Instant now = Instant.now();
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO artifacts(incident_id, type, content, created_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, incidentId);
            statement.setString(2, type);
            statement.setString(3, writeJson(content));
            statement.setString(4, now.toString());
            return statement;
        }, key);
        Number generated = key.getKey();
        Long id = generated == null ? null : generated.longValue();
        return new Artifact(id == null ? 0 : id, incidentId, type, content, now);
    }

    public List<Artifact> artifactsFor(String incidentId) {
        return jdbc.query("SELECT * FROM artifacts WHERE incident_id = ? ORDER BY id",
                (rs, row) -> new Artifact(rs.getLong("id"), rs.getString("incident_id"),
                        rs.getString("type"), readJson(rs.getString("content")),
                        Instant.parse(rs.getString("created_at"))), incidentId);
    }

    private Incident mapIncident(ResultSet rs, int row) throws SQLException {
        return new Incident(rs.getString("id"), rs.getString("status"), readJson(rs.getString("payload")),
                Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at")),
                rs.getString("decline_report"));
    }

    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Could not serialize incident data", exception); }
    }

    private Map<String, Object> readJson(String value) {
        try { return mapper.readValue(value, MAP_TYPE); }
        catch (Exception exception) { throw new IllegalStateException("Could not read stored incident data", exception); }
    }
}
