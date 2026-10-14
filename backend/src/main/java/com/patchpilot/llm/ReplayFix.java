package com.patchpilot.llm;

public record ReplayFix(String bugId, String filePath, String expected, String replacement,
                        String explanation, String expectedExceptionType, double confidence) {
}
