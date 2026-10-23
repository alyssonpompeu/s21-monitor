package com.alysson.g533connect;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.nio.charset.StandardCharsets;

public final class FirmwareInspector {
    public static final String CLIENT_SHA = "c3624e58de9f93414ed94c0ab3690ccef35a82dbb525cf600b00ac5b68e67ba3";
    public static final String HOST_SHA = "94f705f835e31f902dfbec77ef872c0fab5cf5339ccf756baa49173d85d5e7a2";

    private FirmwareInspector() {}

    public static String inspect(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream(70000);
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (b.size() + n > 2097152)
                throw new IllegalArgumentException("Firmware excede 2 MB; selecione BIN Avnera.");
            b.write(buffer, 0, n);
        }
        return inspect(b.toByteArray());
    }

    public static String inspect(byte[] bytes) throws Exception {
        byte[] dig = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hash = new StringBuilder(64);
        for (byte one : dig) hash.append(String.format(Locale.US, "%02x", one & 0xff));
        String sha = hash.toString();
        boolean client = bytes.length == 20294 && sha.equals(CLIENT_SHA);
        boolean host = bytes.length == 65554 && sha.equals(HOST_SHA);
        String kind = client ? "HEADSET / client" : host ? "DONGLE / host" : "NÃO IDENTIFICADO";
        StringBuilder out = new StringBuilder();
        out.append("AVNERA G533 • INSPEÇÃO LOCAL SOMENTE LEITURA\n")
           .append("Tamanho: ").append(bytes.length).append(" bytes\n")
           .append("SHA-256: ").append(sha).append('\n')
           .append("Identidade: ").append(kind).append('\n');
        if (!client && !host) {
            out.append("Hash desconhecido. Sem interpretação de offsets; não é seguro assumir modelo G533.\n");
            return out.toString();
        }
        String marker = client ? "0x4F17" : "0x10000";
        int at = client ? 0x4f17 : 0x10000;
        int length = client ? 26 : 18;
        out.append("macconfig em ").append(marker).append(", ").append(length).append(" bytes:\n");
        for (int i = 0; i < length; i++) {
            out.append(String.format(Locale.US, "%02X", bytes[at + i] & 255));
            if (i + 1 < length) out.append(' ');
        }
        out.append("\nCampo desconhecido: isso é configuração binária, NÃO um pacote de pareamento.\n");
        int versionOffset = indexOf(bytes, "V1.13".getBytes(StandardCharsets.ISO_8859_1));
        out.append("Marcador V1.13 no offset: ")
           .append(versionOffset < 0 ? "não encontrado" : String.format(Locale.US, "0x%X", versionOffset))
           .append('\n');
        out.append("AV7300: mesmas marcas Avnera não provam a mesma ISA. Desmontagem precisa de validação por arquitetura.\n");
        return out.toString();
    }

    private static int indexOf(byte[] array, byte[] needle) {
        outer: for (int k = 0; k <= array.length - needle.length; k++) {
            for (int j = 0; j < needle.length; j++)
                if (array[k + j] != needle[j]) continue outer;
            return k;
        }
        return -1;
    }
}
