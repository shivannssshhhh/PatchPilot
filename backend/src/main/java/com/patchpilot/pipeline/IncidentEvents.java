package com.patchpilot.pipeline;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class IncidentEvents {
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> byIncident = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SseEmitter> global = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe(String incidentId) {
        SseEmitter emitter = new SseEmitter(0L);
        CopyOnWriteArrayList<SseEmitter> bucket = incidentId == null
                ? global : byIncident.computeIfAbsent(incidentId, ignored -> new CopyOnWriteArrayList<>());
        bucket.add(emitter);
        Runnable cleanup = () -> {
            bucket.remove(emitter);
            if (incidentId != null && bucket.isEmpty()) byIncident.remove(incidentId, bucket);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        send(emitter, "connected", Map.of("connected", true));
        return emitter;
    }

    public void publish(String incidentId, Map<String, Object> event) {
        sendAll(global, event);
        sendAll(byIncident.getOrDefault(incidentId, new CopyOnWriteArrayList<>()), event);
    }

    private void sendAll(List<SseEmitter> emitters, Map<String, Object> data) {
        for (SseEmitter emitter : emitters) send(emitter, "incident", data);
    }

    private void send(SseEmitter emitter, String name, Map<String, Object> data) {
        try { emitter.send(SseEmitter.event().name(name).data(data)); }
        catch (IOException | IllegalStateException exception) { emitter.completeWithError(exception); }
    }
}
