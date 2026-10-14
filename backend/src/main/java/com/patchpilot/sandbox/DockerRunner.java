package com.patchpilot.sandbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class DockerRunner {
    private final Path sampleApp;
    private final String dockerExecutable;
    private final String image;
    private final Duration timeout;
    private final LocalRunner localRunner;

    public DockerRunner(
            @Value("${patchpilot.sample-app.path:../sample_app}") String sampleAppPath,
            @Value("${patchpilot.sandbox.docker-executable:docker}") String dockerExecutable,
            @Value("${patchpilot.sandbox.docker-image:patchpilot-python-sandbox:local}") String image,
            @Value("${patchpilot.sandbox.timeout-seconds:30}") long timeoutSeconds,
            LocalRunner localRunner) {
        this.sampleApp = Path.of(sampleAppPath).toAbsolutePath().normalize();
        this.dockerExecutable = dockerExecutable;
        this.image = image;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.localRunner = localRunner;
    }

    public boolean isAvailable() {
        try {
            Process process = new ProcessBuilder(dockerExecutable, "info", "--format", "{{.ServerVersion}}")
                    .redirectErrorStream(true).start();
            boolean completed = process.waitFor(3, TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            return completed && process.exitValue() == 0;
        } catch (Exception exception) {
            return false;
        }
    }

    public SandboxResult run(Path workspace, String reproductionBug) {
        return run(workspace, reproductionBug, false);
    }

    public SandboxResult run(Path workspace, String reproductionBug, boolean verifyFix) {
        long start = System.nanoTime();
        try {
            if (reproductionBug != null) {
                if (verifyFix) localRunner.addFixVerificationTest(workspace, reproductionBug);
                else localRunner.addReproductionTest(workspace, reproductionBug);
            }
        } catch (IOException exception) {
            return new SandboxResult("docker", -1, false, 0, exception.getMessage(), "prepare sandbox workspace");
        }
        List<String> command = new ArrayList<>(List.of(dockerExecutable, "run", "--rm", "--network", "none", "--memory", "512m",
                "--cpus", "1", "--pids-limit", "64", "--security-opt", "no-new-privileges", "--cap-drop", "ALL",
                "--read-only", "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
                "-e", "PATCHPILOT_API_URL=http://127.0.0.1:9/api/incidents"));
        if (reproductionBug != null) command.addAll(List.of("-e", "SHOPLITE_REPRO_BUG=" + reproductionBug));
        if (verifyFix && reproductionBug != null) command.addAll(List.of("-e", "PATCHPILOT_FIXED_BUG=" + reproductionBug));
        command.addAll(List.of("-v", workspace.toAbsolutePath().normalize() + ":/workspace:rw", image, "-q"));
        try {
            ensureImage();
            ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true);
            Process process = builder.start();
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readOutput(process));
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            String captured = output.get(3, TimeUnit.SECONDS);
            return new SandboxResult("docker", completed ? process.exitValue() : -1, !completed,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), captured, String.join(" ", command));
        } catch (Exception exception) {
            return new SandboxResult("docker", -1, false,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), exception.getMessage(), String.join(" ", command));
        }
    }

    public Path copyWorkspace(Path destination) throws IOException {
        localRunner.copyTreeForDocker(sampleApp, destination);
        return destination;
    }

    private void ensureImage() throws IOException, InterruptedException {
        Process inspect = new ProcessBuilder(dockerExecutable, "image", "inspect", image).redirectErrorStream(true).start();
        if (inspect.waitFor(10, TimeUnit.SECONDS) && inspect.exitValue() == 0) return;
        List<String> build = new ArrayList<>(List.of(dockerExecutable, "build", "-t", image, "-f",
                sampleApp.resolve("Dockerfile.sandbox").toString(), sampleApp.toString()));
        Process process = new ProcessBuilder(build).redirectErrorStream(true).start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readOutput(process));
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timed out building the local Python sandbox image");
        }
        String buildOutput = output.join();
        if (process.exitValue() != 0) throw new IOException("Could not build sandbox image: " + buildOutput);
    }

    private String readOutput(Process process) {
        try (var input = process.getInputStream()) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        catch (IOException exception) { return "Could not read Docker output: " + exception.getMessage(); }
    }
}
