package com.alysson.g991baudiolab;

import android.util.Base64;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class RootShell {
    static final class Result {
        final int code;
        final String out;
        Result(int code, String out) { this.code = code; this.out = out; }
        boolean ok() { return code == 0; }
    }

    static Result exec(String command) {
        StringBuilder out = new StringBuilder();
        try {
            Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) out.append(line).append('\n');
            }
            int code = p.waitFor();
            return new Result(code, out.toString().trim());
        } catch (Throwable t) {
            return new Result(-1, t.toString());
        }
    }

    static boolean hasRoot() {
        Result r = exec("id -u");
        return r.ok() && r.out.trim().equals("0");
    }

    static boolean exists(String path) {
        return exec("test -e " + q(path)).ok();
    }

    static Result writeText(String path, String text) {
        String b64 = Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String parent = path.substring(0, path.lastIndexOf('/'));
        String cmd = "mkdir -p " + q(parent) +
                " && printf '%s' '" + b64 + "' | base64 -d > " + q(path) +
                " && chmod 0644 " + q(path);
        return exec(cmd);
    }

    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
