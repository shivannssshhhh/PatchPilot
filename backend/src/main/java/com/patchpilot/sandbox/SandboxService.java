package com.patchpilot.sandbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

@Service
public class SandboxService {
    private static final Set<String> BUGS = Set.of("users", "pagination", "discount", "inventory", "payments");
    private final LocalRunner localRunner;
    private final DockerRunner dockerRunner;
    private final String mode;

    public SandboxService(LocalRunner localRunner, DockerRunner dockerRunner,
                          @Value("${patchpilot.sandbox.mode:auto}") String mode) {
        this.localRunner = localRunner;
        this.dockerRunner = dockerRunner;
        this.mode = mode.toLowerCase();
    }

    public SandboxResult runHealthySuite() {
        if (!useDocker()) return localRunner.runHealthySuite();
        return withDockerWorkspace(null);
    }

    public SandboxResult runReproduction(String bug) {
        if (!BUGS.contains(bug)) throw new IllegalArgumentException("Unknown seeded bug: " + bug);
        if (!useDocker()) return localRunner.runReproduction(bug);
        return withDockerWorkspace(bug);
    }

    private boolean useDocker() {
        if ("local".equals(mode)) return false;
        boolean available = dockerRunner.isAvailable();
        if ("docker".equals(mode) && !available) {
            throw new IllegalStateException("Docker mode was requested but the Docker daemon is unavailable");
        }
        return available;
    }

    private SandboxResult withDockerWorkspace(String reproductionBug) {
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("patchpilot-docker-sandbox-");
            dockerRunner.copyWorkspace(workspace);
            return dockerRunner.run(workspace, reproductionBug);
        } catch (Exception exception) {
            return new SandboxResult("docker", -1, false, 0, exception.getMessage(), "prepare Docker sandbox");
        } finally {
            if (workspace != null) {
                try (var paths = Files.walk(workspace)) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                } catch (Exception ignored) { }
            }
        }
    }
}
