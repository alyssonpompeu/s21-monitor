package com.alysson.bcm4375lab;

import java.util.Arrays;
import java.util.Locale;

/**
 * RF constants recovered from the user's original FlySky FS-i6 firmware 2.0.17.
 *
 * The 45 register/value pairs occur consecutively in the extracted transmitter
 * firmware at file offset 0xDD88. The A7105 ID write 06 54 75 C5 2A is visible
 * in the same firmware near 0xDD15.
 */
final class FsI6ReverseProfile {
    static final String SOURCE_NAME = "FlySky FS-i6 firmware 2.0.17";
    static final String FIRMWARE_SHA256 = "fc15e11c414082172f97f0444e806790158e811670fd3227b480ea7ba1290541";
    static final int PROFILE_FILE_OFFSET = 0xDD88;
    static final long A7105_ID = 0x5475C52AL;
    static final int FIFO_LENGTH_REG = 0x03;
    static final int FIFO_LENGTH_VALUE = 0x25; // FEP=37 => 38-byte TX payload
    static final int DATA_RATE_REG = 0x0E;
    static final int DATA_RATE_VALUE = 0x00;   // A7105 16 MHz profile: 500 kbps

    // Exact register/value pairs recovered from the FS-i6 2.0.17 binary.
    private static final int[][] REG_PAIRS = new int[][]{
            {0x01,0x42},{0x03,0x25},{0x04,0x00},{0x07,0x00},{0x08,0x00},{0x09,0x00},
            {0x0A,0x00},{0x0B,0x01},{0x0C,0x3C},{0x0D,0x05},{0x0E,0x00},{0x0F,0x50},
            {0x10,0x9E},{0x11,0x4B},{0x12,0x00},{0x13,0x02},{0x14,0x16},{0x15,0x2B},
            {0x16,0x12},{0x17,0x40},{0x18,0x62},{0x19,0x80},{0x1A,0x80},{0x1B,0x00},
            {0x1C,0x0A},{0x1D,0x32},{0x1E,0x03},{0x1F,0x1F},{0x20,0x1E},{0x21,0x00},
            {0x22,0x00},{0x24,0x00},{0x25,0x00},{0x26,0x3B},{0x27,0x00},{0x28,0x17},
            {0x29,0x47},{0x2A,0x80},{0x2B,0x03},{0x2C,0x01},{0x2D,0x45},{0x2E,0x18},
            {0x2F,0x00},{0x30,0x01},{0x31,0x0F}
    };

    static int[][] registerPairs() {
        int[][] out = new int[REG_PAIRS.length][2];
        for (int i=0;i<REG_PAIRS.length;i++) out[i] = Arrays.copyOf(REG_PAIRS[i],2);
        return out;
    }

    static String compactRegisterDump() {
        StringBuilder b = new StringBuilder();
        for (int i=0;i<REG_PAIRS.length;i++) {
            if (i>0) b.append(i%8==0 ? '\n' : ' ');
            b.append(String.format(Locale.US,"%02X:%02X",REG_PAIRS[i][0],REG_PAIRS[i][1]));
        }
        return b.toString();
    }

    static String summary() {
        return "FS-i6 2.0.17 profile @0x"+Integer.toHexString(PROFILE_FILE_OFFSET).toUpperCase(Locale.US)+
                "\nA7105 ID: 0x"+Long.toHexString(A7105_ID).toUpperCase(Locale.US)+
                "\nFIFO: 38 bytes (reg03=0x25)"+
                "\nData-rate profile: reg0E=0x00 (500 kbps @16 MHz)"+
                "\nTX shaping/deviation regs: 14=0x16 15=0x2B"+
                "\nFirmware SHA-256:\n"+FIRMWARE_SHA256;
    }

    private FsI6ReverseProfile() {}
}
