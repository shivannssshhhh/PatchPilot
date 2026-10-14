package com.patchpilot;

import com.patchpilot.llm.ReplayFix;
import com.patchpilot.policy.PatchPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PatchPolicyTest {
    private final PatchPolicy policy = new PatchPolicy();

    @Test
    void acceptsSmallScopedPatchOnlyWithBothEvidenceChecks() {
        ReplayFix fix = new ReplayFix("pagination", "shoplite/__init__.py", "start = page_value * 2",
                "start = (max(1, int(page_value)) - 1) * 2", "Fix offset", "TypeError", 0.93);

        PatchPolicy.Decision decision = policy.evaluate(fix, true, true);

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.riskScore()).isBetween(0, 30);
    }

    @Test
    void refusesMissingEvidenceSensitivePathsAndErrorSwallowing() {
        ReplayFix safe = new ReplayFix("pagination", "shoplite/__init__.py", "old = 1", "new = 2", "", "TypeError", 0.9);
        ReplayFix sensitive = new ReplayFix("payment", "shoplite/payments.py", "old = 1", "new = 2", "", "RuntimeError", 0.9);
        ReplayFix swallowing = new ReplayFix("pagination", "shoplite/__init__.py", "old = 1", "except: pass", "", "TypeError", 0.9);

        assertThat(policy.evaluate(safe, false, true).accepted()).isFalse();
        assertThat(policy.evaluate(safe, true, false).accepted()).isFalse();
        assertThat(policy.evaluate(sensitive, true, true).accepted()).isFalse();
        assertThat(policy.evaluate(swallowing, true, true).accepted()).isFalse();
    }
}
