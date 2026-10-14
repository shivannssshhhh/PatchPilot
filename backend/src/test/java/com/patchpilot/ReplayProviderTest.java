package com.patchpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.patchpilot.llm.ReplayProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayProviderTest {
    @Test
    void loadsPromptHashFixturesAndClassifiesSensitiveRoutes() {
        ReplayProvider provider = new ReplayProvider("../demo/replay_cache", new ObjectMapper());

        assertThat(provider.find("users")).isPresent();
        assertThat(provider.find("pagination").orElseThrow().expectedExceptionType()).isEqualTo("TypeError");
        assertThat(provider.find("discount")).isPresent();
        assertThat(provider.find("inventory")).isPresent();
        assertThat(provider.find("payments")).isEmpty();
        assertThat(provider.bugForPath(java.util.Map.of("path", "/payments/charge"))).isEqualTo("payments");
    }
}
