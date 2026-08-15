package com.alysson.applecontrol;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * Thin package-local wrapper used to make java.lang.Process unambiguous next
 * to android.os.* in the Activity. It does not alter command behavior.
 */
final class Process extends java.lang.Process {
    private final java.lang.Process delegate;

    Process(java.lang.Process delegate) {
        this.delegate = delegate;
    }

    @Override public OutputStream getOutputStream() { return delegate.getOutputStream(); }
    @Override public InputStream getInputStream() { return delegate.getInputStream(); }
    @Override public InputStream getErrorStream() { return delegate.getErrorStream(); }
    @Override public int waitFor() throws InterruptedException { return delegate.waitFor(); }
    @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.waitFor(timeout, unit);
    }
    @Override public int exitValue() { return delegate.exitValue(); }
    @Override public void destroy() { delegate.destroy(); }
    @Override public Process destroyForcibly() { delegate.destroyForcibly(); return this; }
    @Override public boolean isAlive() { return delegate.isAlive(); }
}

final class ProcessBuilder {
    private final java.lang.ProcessBuilder delegate;

    ProcessBuilder(String... command) {
        delegate = new java.lang.ProcessBuilder(command);
    }

    ProcessBuilder redirectErrorStream(boolean redirect) {
        delegate.redirectErrorStream(redirect);
        return this;
    }

    Process start() throws java.io.IOException {
        return new Process(delegate.start());
    }
}
