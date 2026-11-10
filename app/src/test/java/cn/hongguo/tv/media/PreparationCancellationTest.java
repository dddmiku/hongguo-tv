package cn.hongguo.tv.media;

import org.junit.Test;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class PreparationCancellationTest {
    @Test public void cancellationDisconnectsBlockedReadAndReleasesPlaybackLock() throws Exception {
        Object preparationLock = new Object();
        PreparationCancellation cancel = new PreparationCancellation();
        CountDownLatch entered = new CountDownLatch(1), released = new CountDownLatch(1), playback = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        HttpURLConnection blocked = new HttpURLConnection(new URL("http://localhost/fixture")) {
            @Override public void disconnect() { released.countDown(); }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() { }
        };
        Thread prefetch = new Thread(() -> {
            synchronized (preparationLock) {
                try {
                    cancel.bindWorker(); cancel.bindConnection(blocked); entered.countDown();
                    // Simulate a read that only unblocks when the underlying connection closes.
                    while (released.getCount() != 0) try { released.await(); }
                    catch (InterruptedException ignored) { }
                    cancel.check();
                    failure.set(new AssertionError("取消后继续交付预缓存"));
                } catch (InterruptedIOException expected) { }
                finally { cancel.finished(); }
            }
        }, "synthetic-prefetch");
        prefetch.start();
        assertTrue(entered.await(2,TimeUnit.SECONDS));
        Thread current = new Thread(() -> { synchronized (preparationLock) { playback.countDown(); } }, "synthetic-playback");
        current.start(); cancel.cancel();
        assertTrue("取消没有关闭阻塞下载", released.await(2,TimeUnit.SECONDS));
        assertTrue("新播放仍被旧预缓存阻塞", playback.await(2,TimeUnit.SECONDS));
        prefetch.join(2000); current.join(2000);
        assertNull(failure.get()); assertFalse(prefetch.isAlive());
    }

    @Test public void cancelBeforeConnectionBindingDoesNotStartNewDownload() throws Exception {
        PreparationCancellation cancel = new PreparationCancellation(); cancel.cancel();
        CountDownLatch closed = new CountDownLatch(1);
        HttpURLConnection connection = new HttpURLConnection(new URL("http://localhost/fixture")) {
            @Override public void disconnect() { closed.countDown(); }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() { }
        };
        try { cancel.bindConnection(connection); fail("旧准备开始了新连接"); }
        catch (InterruptedIOException expected) { }
        assertEquals(0,closed.getCount());
    }
}
