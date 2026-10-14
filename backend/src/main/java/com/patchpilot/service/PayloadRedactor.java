package com.patchpilot.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class PayloadRedactor {
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern SECRET = Pattern.compile("(?i)(password|token|secret|api[_-]?key)\\s*([=:])\\s*([^\\s,;&]+)");
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(password|token|secret|api[_-]?key).*");

    public Map<String, Object> redact(Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> safe = (Map<String, Object>) redactValue(payload);
        return safe;
    }

    private Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                copy.put(name, SECRET_KEY.matcher(name).matches() ? "[REDACTED]" : redactValue(item));
            });
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(redactValue(item));
            return copy;
        }
        if (value instanceof String text) {
            return EMAIL.matcher(SECRET.matcher(text).replaceAll("$1$2[REDACTED]")).replaceAll("[REDACTED-EMAIL]");
        }
        return value;
    }
}
