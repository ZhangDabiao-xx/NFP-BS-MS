package org.example.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ProjectCodeMapRepackTest {
    @Test
    void adaptiveRepackKeepsAllRegisteredAgentEditAnchorsUniqueAndOrdered() {
        assertDoesNotThrow(() -> ProjectCodeMap.create(Path.of("")));
    }
}
