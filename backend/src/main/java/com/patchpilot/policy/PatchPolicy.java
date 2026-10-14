package com.patchpilot.policy;

import com.patchpilot.llm.ReplayFix;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class PatchPolicy {
    private static final Set<String> ALLOWED_PATHS = Set.of("shoplite/__init__.py");
    private static final int MAX_CHANGED_LINES = 30;

    public Decision evaluate(ReplayFix fix, boolean reproduced, boolean verified) {
        String path = fix.filePath().replace('\\', '/');
        if (touchesSensitivePath(path)) return Decision.declined("The patch touches a sensitive path.", 100);
        if (!ALLOWED_PATHS.contains(path)) return Decision.declined("The patch path is outside the allowlist.", 100);
        if (!reproduced) return Decision.declined("A failing reproduction was not confirmed before the patch.", 100);
        if (!verified) return Decision.declined("The full suite and reproduction check did not pass after the patch.", 100);
        if (fix.expected() == null || fix.expected().isBlank() || fix.replacement() == null || fix.replacement().isBlank()) {
            return Decision.declined("The patch does not contain a concrete source change.", 100);
        }
        int changedLines = countLines(fix.expected()) + countLines(fix.replacement());
        if (changedLines > MAX_CHANGED_LINES) return Decision.declined("The patch exceeds the changed-line limit.", 100);
        String normalized = fix.replacement().toLowerCase(Locale.ROOT);
        if (normalized.matches("(?s).*except\\s*:\\s*pass.*") || normalized.contains("except exception: pass")) {
            return Decision.declined("The patch appears to swallow an error.", 100);
        }
        int score = Math.max(0, 30 + changedLines - 24 - (int) Math.round(fix.confidence() * 5));
        return new Decision(true, score, score <= 30 ? "Safe to review" : score <= 60 ? "Review carefully" : "Risk is too high");
    }

    public boolean touchesSensitivePath(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("/auth") || normalized.contains("payment") || normalized.contains("secret")
                || normalized.contains("migration") || normalized.contains("infrastructure") || normalized.contains("infra/");
    }

    private int countLines(String value) { return value.split("\\R", -1).length; }

    public record Decision(boolean accepted, int riskScore, String reason) {
        static Decision declined(String reason, int score) { return new Decision(false, score, reason); }
    }
}
