package com.alysson.bcm4375lab;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

final class RootReader {
    private static final long DEFAULT_TIMEOUT_SECONDS = 8;

    static Result run(String command) {
        return run(command, DEFAULT_TIMEOUT_SECONDS);
    }

    static Result run(String command, long timeoutSeconds) {
        StringBuilder out = new StringBuilder();
        int code = -1;
        boolean timedOut = false;
        Process p = null;
        Thread reader = null;
        try {
            p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            final Process process = p;
            reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (out) {
                            out.append(line).append('\n');
                        }
                    }
                } catch (Exception e) {
                    synchronized (out) {
                        out.append("[reader: ").append(e.getClass().getSimpleName()).append("]\n");
                    }
                }
            }, "bcm4375-root-reader");
            reader.start();

            boolean finished = p.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (!finished) {
                timedOut = true;
                p.destroy();
                if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroyForcibly();
                synchronized (out) {
                    out.append("[TIMEOUT after ").append(timeoutSeconds).append("s]\n");
                }
            } else {
                code = p.exitValue();
            }

            if (reader != null) reader.join(1000);
        } catch (Exception e) {
            synchronized (out) {
                out.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
            }
            if (p != null) p.destroyForcibly();
        }
        return new Result(code, out.toString(), timedOut);
    }

    static final class Result {
        final int code;
        final String output;
        final boolean timedOut;

        Result(int code, String output, boolean timedOut) {
            this.code = code;
            this.output = output == null ? "" : output;
            this.timedOut = timedOut;
        }
    }

    private RootReader() {}
}
