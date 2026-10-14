package com.patchpilot.sandbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class LocalRunner {
    private static final Set<String> SKIP_DIRS = Set.of(".venv", ".pytest_cache", "__pycache__", ".git");
    private static final String REPRO_TEST = """
            import os
            from shoplite import create_app

            BUG_CASES = {
                "users": ("GET", "/users/1", None),
                "pagination": ("GET", "/orders?page=2", None),
                "discount": ("POST", "/cart/discount", {"code": "FORGE10", "subtotal": 10}),
                "inventory": ("POST", "/inventory/sync", {"items": []}),
                "payments": ("POST", "/payments/charge", {"amount": 5}),
            }

            def test_incident_reproduces_as_http_error():
                bug = os.environ["SHOPLITE_REPRO_BUG"]
                method, path, payload = BUG_CASES[bug]
                client = create_app({"TESTING": False, "ACTIVE_BUGS": {bug}}).test_client()
                response = client.open(path, method=method, json=payload)
                assert response.status_code == 200, (
                    f"Expected healthy response for reproduction check, got {response.status_code}"
                )
            """;

    private final Path sampleApp;
    private final String pythonExecutable;
    private final Duration timeout;

    public LocalRunner(
            @Value("${patchpilot.sample-app.path:../sample_app}") String sampleAppPath,
            @Value("${patchpilot.sandbox.python-executable:python}") String pythonExecutable,
            @Value("${patchpilot.sandbox.timeout-seconds:30}") long timeoutSeconds) {
        this.sampleApp = Path.of(sampleAppPath).toAbsolutePath().normalize();
        this.pythonExecutable = resolvePython(pythonExecutable, this.sampleApp);
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    private String resolvePython(String configured, Path source) {
        if (!"python".equals(configured)) return configured;
        Path windowsVenv = source.resolve(".venv/Scripts/python.exe");
        if (Files.isExecutable(windowsVenv)) return windowsVenv.toString();
        Path unixVenv = source.resolve(".venv/bin/python");
        if (Files.isExecutable(unixVenv)) return unixVenv.toString();
        return configured;
    }

    public SandboxResult runHealthySuite() {
        return run(List.of("-q"), null);
    }

    public SandboxResult runReproduction(String bug) {
        if (!Set.of("users", "pagination", "discount", "inventory", "payments").contains(bug)) {
            throw new IllegalArgumentException("Unknown seeded bug: " + bug);
        }
        return run(List.of("-q"), bug);
    }

    public void copyTreeForDocker(Path source, Path target) throws IOException {
        copyTree(source, target);
    }

    public void addReproductionTest(Path workspace, String bug) throws IOException {
        if (!Set.of("users", "pagination", "discount", "inventory", "payments").contains(bug)) {
            throw new IllegalArgumentException("Unknown seeded bug: " + bug);
        }
        Files.writeString(workspace.resolve("tests/test_patchpilot_repro.py"), REPRO_TEST, StandardCharsets.UTF_8);
    }

    private SandboxResult run(List<String> testArguments, String reproductionBug) {
        long start = System.nanoTime();
        Path copy = null;
        try {
            copy = Files.createTempDirectory("patchpilot-sandbox-");
            copyTree(sampleApp, copy);
            if (reproductionBug != null) addReproductionTest(copy, reproductionBug);
            List<String> command = new java.util.ArrayList<>();
            command.add(pythonExecutable);
            command.add("-m");
            command.add("pytest");
            command.addAll(testArguments);
            ProcessBuilder builder = new ProcessBuilder(command).directory(copy.toFile()).redirectErrorStream(true);
            builder.environment().put("PATCHPILOT_API_URL", "http://127.0.0.1:9/api/incidents");
            if (reproductionBug != null) builder.environment().put("SHOPLITE_REPRO_BUG", reproductionBug);
            Process process = builder.start();
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readOutput(process));
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            String captured = output.get(3, TimeUnit.SECONDS);
            int exitCode = completed ? process.exitValue() : -1;
            return new SandboxResult("local", exitCode, !completed,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), captured,
                    String.join(" ", command));
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            return new SandboxResult("local", -1, false,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), message,
                    pythonExecutable + " -m pytest");
        } finally {
            if (copy != null) deleteTree(copy);
        }
    }

    private void copyTree(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) throw new IOException("Sample app directory not found: " + source);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (!directory.equals(source) && SKIP_DIRS.contains(directory.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private String readOutput(Process process) {
        try (var input = process.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "Could not read sandbox output: " + exception.getMessage();
        }
    }

    private void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
