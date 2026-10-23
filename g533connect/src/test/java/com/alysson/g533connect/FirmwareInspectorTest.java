package com.alysson.g533connect;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import static org.junit.Assert.*;

public class FirmwareInspectorTest {
    @Test public void unknownFirmwareNotPresentedAsAvneraG533() throws Exception {
        String result = FirmwareInspector.inspect(new byte[20294]);
        assertTrue(result.contains("NÃO IDENTIFICADO"));
        assertFalse(result.contains("macconfig em"));
    }
    @Test public void oversizedFirmwareRejected() throws Exception {
        byte[] data = new byte[2097153];
        try {
            FirmwareInspector.inspect(new ByteArrayInputStream(data));
            fail("Must reject oversized file");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("2 MB"));
        }
    }
    @Test public void exactHashIdentifiersStable() {
        assertEquals(64, FirmwareInspector.CLIENT_SHA.length());
        assertEquals(64, FirmwareInspector.HOST_SHA.length());
        assertNotEquals(FirmwareInspector.CLIENT_SHA, FirmwareInspector.HOST_SHA);
    }
}
