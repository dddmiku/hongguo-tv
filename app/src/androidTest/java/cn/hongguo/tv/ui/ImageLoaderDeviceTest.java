package cn.hongguo.tv.ui;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.test.InstrumentationTestCase;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Real bitmap and HTTP regressions, isolated from upstream services and account data. */
public class ImageLoaderDeviceTest extends InstrumentationTestCase {
    private ImageServer server;

    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue(getInstrumentation().getTargetContext().getPackageName().endsWith(".qa"));
        server = new ImageServer();
    }

    @Override protected void tearDown() throws Exception {
        try { if (server != null) server.close(); } finally { super.tearDown(); }
    }

    private <T> T main(Supplier<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> value.set(action.get()));
        return value.get();
    }

    private void waitFor(String label, Supplier<Boolean> check) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            if (main(check)) return;
            Thread.sleep(20);
        }
        fail(label);
    }

    private ImageView image() {
        return new ImageView(getInstrumentation().getTargetContext());
    }

    private static boolean hasColor(ImageView view, int color) {
        if (!(view.getDrawable() instanceof BitmapDrawable)) return false;
        Bitmap bitmap = ((BitmapDrawable) view.getDrawable()).getBitmap();
        return bitmap != null && bitmap.getWidth() == 4 && bitmap.getHeight() == 4
                && bitmap.getPixel(1, 1) == color;
    }

    /** Wait until network/decode tasks and their UI deliveries finish, not a guessed sleep. */
    private void awaitLoaderIdle() throws Exception {
        Field field = ImageLoader.class.getDeclaredField("POOL");
        field.setAccessible(true);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) field.get(null);
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            if (pool.getActiveCount() == 0 && pool.getQueue().isEmpty()) {
                getInstrumentation().waitForIdleSync();
                return;
            }
            Thread.sleep(20);
        }
        fail("封面下载和解码未结束");
    }

    public void testConcurrentSameUrlDownloadsOnceAndFillsEveryView() throws Exception {
        Route route = server.image("/shared.png", Color.RED, true, 0);
        List<ImageView> views = main(() -> {
            List<ImageView> value = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                ImageView view = image();
                ImageLoader.load(view, route.url);
                value.add(view);
            }
            return value;
        });
        assertTrue("本地图片请求未进入真实下载路径", route.started.await(5, TimeUnit.SECONDS));
        route.release.countDown();
        waitFor("同一封面的所有视图未收到位图", () -> {
            for (ImageView view : views) if (!hasColor(view, Color.RED)) return false;
            return true;
        });
        awaitLoaderIdle();
        assertEquals("同时显示同一封面应合并为一次下载", 1, route.requests.get());
    }

    public void testCompletedBitmapIsReusedWithoutAnotherRequest() throws Exception {
        Route route = server.image("/cached.png", Color.BLUE, false, 0);
        ImageView first = main(() -> {
            ImageView view = image();
            ImageLoader.load(view, route.url);
            return view;
        });
        waitFor("首张封面未完成真实解码", () -> hasColor(first, Color.BLUE));
        awaitLoaderIdle();
        main(() -> {
            ImageView second = image();
            ImageLoader.load(second, route.url);
            assertTrue("缓存命中应立即显示位图", hasColor(second, Color.BLUE));
            return null;
        });
        awaitLoaderIdle();
        assertEquals("缓存命中不得再次下载", 1, route.requests.get());
    }

    public void testRepeatedBindingOfSameViewDeliversOnlyOnce() throws Exception {
        Route route = server.image("/same-view.png", Color.RED, true, 0);
        CountingImageView view = main(() -> {
            CountingImageView value = new CountingImageView(getInstrumentation().getTargetContext());
            for (int i = 0; i < 20; i++) ImageLoader.load(value, route.url);
            return value;
        });
        assertTrue("重复绑定的图片请求未开始", route.started.await(5, TimeUnit.SECONDS));
        route.release.countDown();
        waitFor("重复绑定后没有显示封面", () -> hasColor(view, Color.RED));
        awaitLoaderIdle();
        assertEquals("同一视图重复绑定不能重复下载", 1, route.requests.get());
        assertEquals("同一视图不能积累重复订阅和重复位图交付", 1, main(() -> view.deliveries).intValue());
    }

    public void testReboundViewRejectsLateOldImage() throws Exception {
        Route old = server.image("/old.png", Color.RED, true, 0);
        Route replacement = server.image("/new.png", Color.BLUE, false, 0);
        ImageView view = main(() -> {
            ImageView value = image();
            ImageLoader.load(value, old.url);
            return value;
        });
        assertTrue("旧封面请求未开始", old.started.await(5, TimeUnit.SECONDS));
        main(() -> { ImageLoader.load(view, replacement.url); return null; });
        waitFor("换绑后的新封面没有先显示", () -> hasColor(view, Color.BLUE));
        old.release.countDown();
        awaitLoaderIdle();
        main(() -> {
            assertTrue("旧封面晚到后覆盖了新封面", hasColor(view, Color.BLUE));
            assertEquals(replacement.url, view.getTag());
            return null;
        });
        assertEquals(1, old.requests.get());
        assertEquals(1, replacement.requests.get());
    }

    public void testFailedRequestCanRetrySameUrl() throws Exception {
        Route route = server.image("/retry.png", Color.RED, false, 1);
        ImageView view = main(() -> {
            ImageView value = image();
            ImageLoader.load(value, route.url);
            return value;
        });
        assertTrue("受控失败请求未开始", route.started.await(5, TimeUnit.SECONDS));
        awaitLoaderIdle();
        assertEquals(1, route.requests.get());
        main(() -> {
            assertNull("失败请求不能生成成功位图", view.getDrawable());
            ImageLoader.load(view, route.url);
            return null;
        });
        waitFor("失败后同地址无法重新下载", () -> hasColor(view, Color.RED));
        awaitLoaderIdle();
        assertEquals("失败任务必须清理，允许一次明确重试", 2, route.requests.get());
    }

    private static final class CountingImageView extends ImageView {
        int deliveries;
        CountingImageView(android.content.Context context) { super(context); }
        @Override public void setImageBitmap(Bitmap bitmap) {
            super.setImageBitmap(bitmap);
            if (bitmap != null) deliveries++;
        }
    }

    private static final class Route {
        final String url;
        final byte[] bytes;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger failures;
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release;
        Route(String url, byte[] bytes, boolean delayed, int failures) {
            this.url = url; this.bytes = bytes;
            this.release = new CountDownLatch(delayed ? 1 : 0);
            this.failures = new AtomicInteger(failures);
        }
    }

    /** A bounded local HTTP fixture serves generated solid-color PNGs. */
    private static final class ImageServer {
        private final ServerSocket listener;
        private final Map<String, Route> routes = new ConcurrentHashMap<>();
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private volatile boolean closed;
        ImageServer() throws Exception {
            listener = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
            Thread accept = new Thread(() -> {
                while (!closed) {
                    try {
                        Socket socket = listener.accept();
                        sockets.add(socket);
                        try { workers.execute(() -> serve(socket)); }
                        catch (RejectedExecutionException error) {
                            sockets.remove(socket); socket.close();
                            if (!closed) throw error;
                        }
                    } catch (IOException error) { if (!closed) throw new AssertionError(error); }
                }
            }, "image-fixture-listener");
            accept.setDaemon(true);
            accept.start();
        }
        Route image(String path, int color, boolean delayed, int failures) {
            Bitmap bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(color);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
            bitmap.recycle();
            Route route = new Route("http://127.0.0.1:" + listener.getLocalPort() + path,
                    bytes.toByteArray(), delayed, failures);
            routes.put(path, route);
            return route;
        }
        private static String line(BufferedInputStream input) throws IOException {
            ByteArrayOutputStream value = new ByteArrayOutputStream();
            for (int b; (b = input.read()) != -1 && b != '\n'; ) {
                if (b != '\r') value.write(b);
                if (value.size() > 8192) throw new IOException("Oversized fixture header");
            }
            return new String(value.toByteArray(), StandardCharsets.US_ASCII);
        }
        private void serve(Socket socket) {
            try (Socket connection = socket) {
                connection.setSoTimeout(10000);
                BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                String[] request = line(input).split(" ", 3);
                for (int i = 0; i < 32 && !line(input).isEmpty(); i++) { }
                Route route = request.length > 1 ? routes.get(request[1]) : null;
                if (route == null) { respond(connection, 404, new byte[0]); return; }
                route.requests.incrementAndGet();
                route.started.countDown();
                if (!route.release.await(8, TimeUnit.SECONDS)) return;
                boolean failed = route.failures.getAndUpdate(value -> Math.max(0, value - 1)) > 0;
                respond(connection, failed ? 503 : 200, failed ? new byte[0] : route.bytes);
            } catch (Exception ignored) {
                // Closing a fixture in tearDown deliberately interrupts any outstanding request.
            } finally { sockets.remove(socket); }
        }
        private static void respond(Socket socket, int code, byte[] bytes) throws IOException {
            OutputStream out = socket.getOutputStream();
            String status = code == 200 ? "200 OK" : code == 503 ? "503 Service Unavailable" : "404 Not Found";
            String headers = "HTTP/1.1 " + status + "\r\nContent-Type: image/png\r\nContent-Length: "
                    + bytes.length + "\r\nConnection: close\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.US_ASCII)); out.write(bytes); out.flush();
        }
        void close() throws Exception {
            closed = true;
            for (Route route : routes.values()) route.release.countDown();
            listener.close();
            for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
