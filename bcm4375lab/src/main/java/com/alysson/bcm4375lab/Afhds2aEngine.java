package com.alysson.bcm4375lab;

import java.util.Arrays;
import java.util.Locale;

/**
 * Minimal AFHDS2A packet engine derived from the public Multiprotocol
 * AFHDS2A/A7105 implementation. This class only builds protocol data; it does
 * not pretend that a Wi-Fi PHY is an A7105/GFSK PHY.
 */
final class Afhds2aEngine {
    static final int TX_PACKET_SIZE = 38;
    static final int NUM_FREQ = 16;

    private final byte[] txId = new byte[4];
    private final byte[] rxId = new byte[] {(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff};
    private final int[] hops = new int[NUM_FREQ];
    private int hopIndex;

    Afhds2aEngine(int stableId) {
        txId[0] = (byte)(stableId & 0xff);
        txId[1] = (byte)((stableId >>> 8) & 0xff);
        txId[2] = (byte)((stableId >>> 16) & 0xff);
        txId[3] = (byte)((stableId >>> 24) & 0xff);
        calcChannels(stableId);
    }

    byte[] txId() { return Arrays.copyOf(txId, txId.length); }
    int[] hops() { return Arrays.copyOf(hops, hops.length); }
    int currentHop() { return hops[hopIndex & 0x0f]; }
    int advanceHop() { int ch = hops[hopIndex & 0x0f]; hopIndex=(hopIndex+1)&0x0f; return ch; }

    void setRxId(byte[] id) {
        if (id == null || id.length != 4) throw new IllegalArgumentException("rx id");
        System.arraycopy(id,0,rxId,0,4);
    }

    private void calcChannels(int protocolId) {
        int idx=0;
        long rnd = protocolId & 0xffffffffL;
        while (idx < NUM_FREQ) {
            int bandNo = ((((idx << 1) | ((idx >> 1) & 1)) + (txId[3] & 0xff)) & 3);
            rnd = (rnd * 0x0019660DL + 0x3C6EF35FL) & 0xffffffffL;
            int next = bandNo * 41 + 1 + (int)((rnd >>> idx) % 41L);
            boolean good=true;
            for (int i=0;i<idx;i++) if (Math.abs(next-hops[i]) < 5) { good=false; break; }
            if (good) hops[idx++]=next;
        }
    }

    /** phase 1..4 maps to BB/BC bind phases in the reference implementation. */
    byte[] buildBindPacket(int phase) {
        if (phase < 1 || phase > 4) throw new IllegalArgumentException("phase");
        byte[] p = new byte[TX_PACKET_SIZE];
        System.arraycopy(txId,0,p,1,4);
        Arrays.fill(p,5,9,(byte)0xff);
        p[10]=0;
        for (int i=0;i<NUM_FREQ;i++) p[11+i]=(byte)hops[i];
        Arrays.fill(p,27,37,(byte)0xff);
        p[37]=0;
        if (phase == 1) {
            p[0]=(byte)0xbb;
            p[9]=1;
        } else {
            p[0]=(byte)0xbc;
            if (phase == 4) {
                System.arraycopy(rxId,0,p,5,4);
                Arrays.fill(p,11,27,(byte)0xff);
            }
            int bindStage=phase-1;
            if (bindStage>2) bindStage=2;
            p[9]=(byte)bindStage;
            p[27]=1;
            p[28]=(byte)0x80;
        }
        return p;
    }

    /**
     * Builds the normal 0x58 sticks packet for the first 14 channels. Values
     * are standard RC pulse-domain microseconds, clamped 1000..2000. RX42 use
     * only needs the first few channels; unused channels default to 1500.
     */
    byte[] buildSticksPacket(int[] channelsUs) {
        byte[] p = new byte[TX_PACKET_SIZE];
        p[0]=0x58;
        System.arraycopy(txId,0,p,1,4);
        System.arraycopy(rxId,0,p,5,4);
        int[] ch = new int[14];
        Arrays.fill(ch,1500);
        if (channelsUs != null) {
            for (int i=0;i<Math.min(ch.length,channelsUs.length);i++) ch[i]=clamp(channelsUs[i],1000,2000);
        }
        for (int i=0;i<14;i++) {
            int v=ch[i];
            p[9+i*2]=(byte)(v & 0xff);
            p[10+i*2]=(byte)((v >>> 8) & 0x0f);
        }
        int nextHop=(hopIndex+1)&0x0f;
        p[34] |= (byte)(nextHop << 4);
        p[36] |= (byte)(nextHop != 0 ? 0x80 : 0x90);
        p[37]=0;
        return p;
    }

    String describeHops() {
        StringBuilder s=new StringBuilder();
        for(int i=0;i<hops.length;i++) {
            if(i>0) s.append(' ');
            s.append(String.format(Locale.US,"%03d",hops[i]));
        }
        return s.toString();
    }

    static String hex(byte[] p) {
        StringBuilder s=new StringBuilder();
        for(int i=0;i<p.length;i++) {
            if(i>0) s.append(' ');
            s.append(String.format(Locale.US,"%02X",p[i]&0xff));
        }
        return s.toString();
    }

    private static int clamp(int x,int lo,int hi){ return Math.max(lo,Math.min(hi,x)); }

    private Afhds2aEngine() { throw new AssertionError(); }
}
