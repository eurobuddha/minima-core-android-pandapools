package com.eurobuddha.pandapools;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class NodeTransportTest {
    @Test public void transportFailureCannotMasqueradeAsCompleteNodeReply() {
        assertFalse(NodeTransport.failure("Disconnected").has("status"));
    }
    @Test public void rejectsPathTraversalAndNonRequestNames() {
        assertFalse(NodeTransport.validId("../../secrets"));
        assertFalse(NodeTransport.validId(null));
        assertTrue(NodeTransport.validId("12345678-1234-1234-1234-123456789abc"));
    }
    @Test public void readsLargeReplyWithoutPuttingItInBinderAndBoundsAllocation() throws Exception {
        File file = File.createTempFile("transport-test", ".json");
        try {
            String payload = "x".repeat(300_000);
            Files.write(file.toPath(), payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(payload, NodeTransport.read(file));
            Files.write(file.toPath(), new byte[NodeTransport.MAX_BYTES + 1]);
            assertThrows(java.io.IOException.class, () -> NodeTransport.read(file));
        } finally { file.delete(); }
    }
}
