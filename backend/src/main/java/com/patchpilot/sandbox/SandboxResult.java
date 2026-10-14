package com.patchpilot.sandbox;

public record SandboxResult(
        String mode,
        int exitCode,
        boolean timedOut,
        long durationMillis,
        String output,
        String command) {

    public boolean passed() {
        return exitCode == 0 && !timedOut;
    }
}
