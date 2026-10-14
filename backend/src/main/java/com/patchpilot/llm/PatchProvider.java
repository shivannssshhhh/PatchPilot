package com.patchpilot.llm;

import java.util.Optional;

/** Provider boundary for proposing a constrained repair from an incident fixture or model. */
public interface PatchProvider {
    Optional<ReplayFix> find(String bugId);
}
