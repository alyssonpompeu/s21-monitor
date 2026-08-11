package com.alysson.bcm4375lab;

import java.io.BufferedReader;
import java.io.InputStreamReader;

final class RootReader {
    static Result run(String command) {
        StringBuilder out = new StringBuilder();
        int code = -1;
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) out.append(line).append('\n');
            }
            code = p.waitFor();
        } catch (Exception e) {
            out.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
            if (p != null) p.destroy();
        }
        return new Result(code, out.toString());
    }

    static final class Result {
        final int code;
        final String output;
        Result(int code, String output) {
            this.code = code;
            this.output = output == null ? "" : output;
        }
    }

    private RootReader() {}
}
