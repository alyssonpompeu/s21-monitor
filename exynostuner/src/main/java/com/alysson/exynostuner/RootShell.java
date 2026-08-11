package com.alysson.exynostuner;

import java.io.BufferedReader;
import java.io.InputStreamReader;

final class RootShell {
    static final class Result {
        final int code;
        final String output;
        Result(int code, String output) {
            this.code = code;
            this.output = output == null ? "" : output;
        }
    }

    static Result run(String command) {
        return exec(new String[]{"su", "-c", command});
    }

    static Result direct(String command) {
        return exec(new String[]{"/system/bin/sh", "-c", command});
    }

    static String q(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static Result exec(String[] argv) {
        Process p = null;
        try {
            p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (out.length() > 0) out.append('\n');
                    out.append(line);
                }
            }
            int code = p.waitFor();
            return new Result(code, out.toString());
        } catch (Exception e) {
            return new Result(-1, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (p != null) p.destroy();
        }
    }
}
