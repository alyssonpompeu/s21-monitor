package com.alysson.g533phy;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class IQAnalyzerTest {
    @Test public void syntheticSignalHasOnOffEnergyAndPhase() {
        IQAnalyzer.Demo d = IQAnalyzer.demo();
        IQAnalyzer.Result r = IQAnalyzer.compare(d.off, d.on, 4_000_000, 2439.35, 1_000_000.0);
        assertTrue("synthetic ON power should be higher", r.powerChangeDb > 6.0);
        assertTrue(r.phase.contains("Hipótese π/4-DQPSK"));
        assertTrue(r.phase.contains("sps: 4"));
        assertEquals(IQAnalyzer.FFT, r.deltaSpectrumDb.length);
        assertEquals(32768, r.offSamples);
        assertEquals(32768, r.onSamples);
    }

    @Test public void loadComplexFloat32Interleaved() throws Exception {
        ByteBuffer b = ByteBuffer.allocate(4096 * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4096; i++) { b.putFloat(i * 0.01f); b.putFloat(-i * 0.01f); }
        IQAnalyzer.Samples samples = IQAnalyzer.load(new ByteArrayInputStream(b.array()), IQAnalyzer.Format.CF32LE);
        assertEquals(4096, samples.size());
        assertEquals(2.0f, samples.re[200], 0.0001);
        assertEquals(-2.0f, samples.im[200], 0.0001);
    }

    @Test public void loadComplexSigned16Interleaved() throws Exception {
        ByteBuffer b = ByteBuffer.allocate(4096 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4096; i++) { b.putShort((short) 16384); b.putShort((short) -8192); }
        IQAnalyzer.Samples samples = IQAnalyzer.load(new ByteArrayInputStream(b.array()), IQAnalyzer.Format.CS16LE);
        assertEquals(0.5f, samples.re[50], 0.0001);
        assertEquals(-0.25f, samples.im[50], 0.0001);
    }

    @Test public void rejectShortFile() throws Exception {
        try {
            IQAnalyzer.load(new ByteArrayInputStream(new byte[8]), IQAnalyzer.Format.CF32LE);
            fail("Expected IOException");
        } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("4096")); }
    }

    @Test public void rejectInvalidSampleRate() {
        IQAnalyzer.Demo d = IQAnalyzer.demo();
        try {
            IQAnalyzer.compare(d.off, d.on, 0, 2439.35, null);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { }
    }
}
