package com.alysson.bcm4375lab;

import java.util.Arrays;
import java.util.Locale;

/** AFHDS2A protocol engine; RF modulation is supplied by a separate backend. */
final class Afhds2aEngine {
    static final int TX_PACKET_SIZE = 38;
    static final int RX_PACKET_SIZE = 37;
    static final int NUM_FREQ = 16;
    static final int BIND_CHANNEL_A = 0x0D;
    static final int BIND_CHANNEL_B = 0x8C;
    static final int PERIOD_US = 3850;

    private final byte[] txId = new byte[4];
    private final byte[] rxId = new byte[] {(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff};
    private final int[] hops = new int[NUM_FREQ];
    private int hopIndex;
    private boolean bound;

    Afhds2aEngine(int stableId) {
        txId[0] = (byte)(stableId & 0xff);
        txId[1] = (byte)((stableId >>> 8) & 0xff);
        txId[2] = (byte)((stableId >>> 16) & 0xff);
        txId[3] = (byte)((stableId >>> 24) & 0xff);
        calcChannels(stableId);
    }

    byte[] txId() { return Arrays.copyOf(txId,4); }
    byte[] rxId() { return Arrays.copyOf(rxId,4); }
    boolean isBound() { return bound; }
    int[] hops() { return Arrays.copyOf(hops,hops.length); }
    int currentHop() { return hops[hopIndex & 0x0f]; }
    int advanceHop() { int ch=hops[hopIndex & 0x0f]; hopIndex=(hopIndex+1)&0x0f; return ch; }
    void resetHop() { hopIndex=0; }

    void setRxId(byte[] id) {
        if (id==null || id.length!=4) throw new IllegalArgumentException("rx id");
        System.arraycopy(id,0,rxId,0,4);
        bound = !allFF(rxId);
    }

    /** Mirrors the FS-i6/AFHDS2A response rule: 0xBC, stage byte 1, RX ID in [5..8]. */
    boolean acceptBindReply(byte[] rx37) {
        if (rx37==null || rx37.length < RX_PACKET_SIZE) return false;
        if ((rx37[0]&0xff)!=0xBC || (rx37[9]&0xff)!=0x01) return false;
        System.arraycopy(rx37,5,rxId,0,4);
        bound = !allFF(rxId);
        return bound;
    }

    private void calcChannels(int protocolId) {
        int idx=0;
        long rnd=protocolId & 0xffffffffL;
        while(idx<NUM_FREQ) {
            int bandNo=((((idx<<1)|((idx>>1)&1))+(txId[3]&0xff))&3);
            rnd=(rnd*0x0019660DL+0x3C6EF35FL)&0xffffffffL;
            int next=bandNo*41+1+(int)((rnd>>>idx)%41L);
            boolean good=true;
            for(int i=0;i<idx;i++) if(Math.abs(next-hops[i])<5){good=false;break;}
            if(good) hops[idx++]=next;
        }
    }

    /** phase 1..4 maps to BB/BC bind phases used by FS-i6/AFHDS2A. */
    byte[] buildBindPacket(int phase) {
        if(phase<1||phase>4) throw new IllegalArgumentException("phase");
        byte[] p=new byte[TX_PACKET_SIZE];
        System.arraycopy(txId,0,p,1,4);
        Arrays.fill(p,5,9,(byte)0xff);
        p[10]=0;
        for(int i=0;i<NUM_FREQ;i++) p[11+i]=(byte)hops[i];
        Arrays.fill(p,27,37,(byte)0xff);
        p[37]=0;
        if(phase==1) {
            p[0]=(byte)0xBB; p[9]=1;
        } else {
            p[0]=(byte)0xBC;
            if(phase==4) {
                System.arraycopy(rxId,0,p,5,4);
                Arrays.fill(p,11,27,(byte)0xff);
            }
            p[9]=(byte)Math.min(phase-1,2);
            p[27]=1; p[28]=(byte)0x80;
        }
        return p;
    }

    /** Exact 38-byte normal sticks packet; 14 channels encoded little-endian microseconds. */
    byte[] buildSticksPacket(int[] channelsUs) {
        byte[] p=basePacket(0x58);
        int[] ch=new int[14]; Arrays.fill(ch,1500);
        if(channelsUs!=null) for(int i=0;i<Math.min(14,channelsUs.length);i++) ch[i]=clamp(channelsUs[i],1000,2000);
        for(int i=0;i<14;i++) {
            int v=ch[i];
            p[9+i*2]=(byte)(v&0xff);
            p[10+i*2]=(byte)((v>>>8)&0xff);
        }
        p[37]=0;
        return p;
    }

    byte[] buildFailsafePacket(int[] channelsUs, boolean enabled) {
        byte[] p=basePacket(0x56);
        for(int i=0;i<14;i++) {
            if(!enabled) { p[9+i*2]=(byte)0xff; p[10+i*2]=(byte)0xff; }
            else {
                int v=1500;
                if(channelsUs!=null && i<channelsUs.length) v=clamp(channelsUs[i],1000,2000);
                p[9+i*2]=(byte)(v&0xff); p[10+i*2]=(byte)((v>>>8)&0xff);
            }
        }
        p[37]=0;
        return p;
    }

    /** Default FS-i6-style receiver settings request: 50 Hz, iBUS output. */
    byte[] buildSettingsPacket() {
        byte[] p=basePacket(0xAA);
        p[9]=(byte)0xFD; p[10]=(byte)0xFF;
        p[11]=50; p[12]=0; p[13]=0; p[14]=0;
        Arrays.fill(p,15,37,(byte)0xff);
        p[18]=0x05; p[19]=(byte)0xDC; p[20]=0x05; p[21]=(byte)0xDE;
        p[37]=0;
        return p;
    }

    private byte[] basePacket(int type) {
        byte[] p=new byte[TX_PACKET_SIZE];
        p[0]=(byte)type;
        System.arraycopy(txId,0,p,1,4);
        System.arraycopy(rxId,0,p,5,4);
        return p;
    }

    String describeHops() {
        StringBuilder s=new StringBuilder();
        for(int i=0;i<hops.length;i++) { if(i>0)s.append(' '); s.append(String.format(Locale.US,"%03d",hops[i])); }
        return s.toString();
    }

    String describeIds() {
        return "TX="+hex(txId)+" RX="+hex(rxId)+(bound?" (BOUND)":" (não aprendido)");
    }

    static String hex(byte[] p) {
        StringBuilder s=new StringBuilder();
        for(int i=0;i<p.length;i++){ if(i>0)s.append(' '); s.append(String.format(Locale.US,"%02X",p[i]&0xff)); }
        return s.toString();
    }

    private static boolean allFF(byte[] b){ for(byte x:b) if((x&0xff)!=0xff)return false; return true; }
    private static int clamp(int x,int lo,int hi){return Math.max(lo,Math.min(hi,x));}
    private Afhds2aEngine(){throw new AssertionError();}
}
