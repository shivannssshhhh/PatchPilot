package com.patchpilot.controller;

import com.patchpilot.model.Incident;
import com.patchpilot.model.IncidentDetail;
import com.patchpilot.pipeline.IncidentEvents;
import com.patchpilot.service.IncidentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/incidents")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class IncidentController {
    private final IncidentService incidents;
    private final IncidentEvents events;

    public IncidentController(IncidentService incidents, IncidentEvents events) {
        this.incidents = incidents;
        this.events = events;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Incident capture(@RequestBody Map<String, Object> payload) { return incidents.capture(payload); }

    @GetMapping
    public List<Incident> list() { return incidents.list(); }

    @GetMapping("/{id}")
    public IncidentDetail detail(@PathVariable String id) { return incidents.detail(id); }

    @GetMapping(value = "/{id}/patch", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> patch(@PathVariable String id) {
        String patch = incidents.patch(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=patchpilot-" + id + ".patch")
                .contentType(MediaType.TEXT_PLAIN)
                .body(patch);
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAll() { return events.subscribe(null); }

    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamOne(@PathVariable String id) {
        incidents.get(id);
        return events.subscribe(id);
    }

    @PostMapping("/{id}/approve")
    public Incident approve(@PathVariable String id) { return incidents.approve(id); }

    @PostMapping("/{id}/reject")
    public Incident reject(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        Object reason = body == null ? null : body.get("reason");
        return incidents.reject(id, reason == null ? null : reason.toString());
    }

    @PostMapping("/{id}/retry")
    public Incident retry(@PathVariable String id) { return incidents.retry(id); }
}
