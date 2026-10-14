package com.patchpilot;

import com.patchpilot.sandbox.LocalRunner;
import com.patchpilot.sandbox.DockerRunner;
import com.patchpilot.sandbox.SandboxService;
import com.patchpilot.sandbox.SandboxResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalRunnerTest {
    @Test
    void runsHealthySuiteAndReproducesBugOnlyInTemporaryCopy() throws Exception {
        Path source = Path.of("../sample_app").toAbsolutePath().normalize();
        String original = Files.readString(source.resolve("shoplite/__init__.py"));
        String python = System.getProperty("patchpilot.test.python", "python");
        LocalRunner runner = new LocalRunner(source.toString(), python, 60);

        DockerRunner docker = new DockerRunner(source.toString(), "docker", "patchpilot-python-sandbox:local", 30, runner);
        Assumptions.assumeFalse(docker.isAvailable(), "This test checks auto-mode fallback when Docker is unavailable");
        SandboxService service = new SandboxService(runner, docker, "auto");

        SandboxResult healthy = service.runHealthySuite();
        assertThat(healthy.mode()).isEqualTo("local");
        assertThat(healthy.passed()).isTrue().as(healthy.output());

        SandboxResult reproduction = runner.runReproduction("pagination");
        assertThat(reproduction.exitCode()).isNotZero();
        assertThat(reproduction.output()).contains("test_incident_reproduces_as_http_error");
        assertThat(Files.readString(source.resolve("shoplite/__init__.py"))).isEqualTo(original);
        assertThat(Files.exists(source.resolve("tests/test_patchpilot_repro.py"))).isFalse();
    }
}
