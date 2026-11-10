package cn.hongguo.tv.media;

import java.io.InterruptedIOException;
import java.net.HttpURLConnection;

/** Cancels a single preparation, including a blocked socket read, without blocking the UI. */
public final class PreparationCancellation {
    private volatile boolean cancelled;
    private Thread worker;
    private HttpURLConnection connection;

    public boolean cancelled() { return cancelled; }

    public void cancel() {
        Thread currentWorker;
        HttpURLConnection currentConnection;
        synchronized (this) {
            cancelled = true;
            currentWorker = worker;
            currentConnection = connection;
        }
        if (currentWorker != null) currentWorker.interrupt();
        if (currentConnection != null) {
            // Some URLConnection implementations close a stream synchronously in disconnect().
            new Thread(() -> { try { currentConnection.disconnect(); } catch (RuntimeException ignored) { } }, "episode-cancel").start();
        }
    }

    public void check() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("准备已取消");
    }

    public void bindWorker() throws InterruptedIOException {
        synchronized (this) { worker = Thread.currentThread(); }
        check();
    }

    public void bindConnection(HttpURLConnection active) throws InterruptedIOException {
        synchronized (this) { connection = active; }
        if (cancelled) active.disconnect();
        check();
    }

    public synchronized void clearConnection(HttpURLConnection active) {
        if (connection == active) connection = null;
    }

    public synchronized void finished() { worker = null; connection = null; }
}
