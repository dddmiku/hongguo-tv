package cn.hongguo.tv.update;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdaterTest {
    @Test public void readsPublishedTvContractAndIgnoresDesktopVersion() throws Exception {
        JSONObject tv = new JSONObject().put("version", "1.3.3").put("versionCode", 19)
                .put("size", 2766619)
                .put("url", "https://github.com/dddmiku/hongguo-tv/releases/download/v1.3.3/hongguo-tv-1.3.3.APK")
                .put("sha256", "b920c40e43e72d4f876af00990353dd4e022e1bc58ebee7a3da8f0cb360665c1");
        JSONObject manifest = new JSONObject().put("version", "1.4.8")
                .put("platforms", new JSONObject().put("android-tv", tv));
        Updater.Result result = Updater.parseFeed("1.3.2", manifest);
        assertTrue(result.ok);
        assertTrue(result.updateAvailable);
        assertEquals("1.3.3", result.latest);
        assertFalse(Updater.parseFeed("1.3.3", manifest).updateAvailable);
        tv.remove("sha256");
        assertFalse(Updater.parseFeed("1.3.2", manifest).ok);
    }

    @Test public void permissionHandoffContinuesOnceAndDirectInstallDoesNotContinue() {
        AtomicBoolean permitted = new AtomicBoolean();
        AtomicInteger installs = new AtomicInteger();
        ApkInstaller.Handoff handoff = new ApkInstaller.Handoff((context, apk, version) -> {
            assertEquals("1.3.3", version);
            if (!permitted.get()) return ApkInstaller.InstallResult.REQUESTED_PERMISSION;
            installs.incrementAndGet();
            return ApkInstaller.InstallResult.OPENED_INSTALLER;
        }, context -> permitted.get());
        File apk = new File("hongguo-tv-1.3.3.apk");
        assertEquals(ApkInstaller.InstallResult.REQUESTED_PERMISSION, handoff.start(null, apk, "1.3.3"));
        permitted.set(true);
        assertEquals(ApkInstaller.InstallResult.OPENED_INSTALLER, handoff.resume(null));
        assertNull(handoff.resume(null));
        assertEquals(1, installs.get());
        assertEquals(ApkInstaller.InstallResult.OPENED_INSTALLER, handoff.start(null, apk, "1.3.3"));
        assertNull(handoff.resume(null));
        assertEquals(2, installs.get());
    }

    @Test public void decliningPermissionDoesNotLaunchUpdateLater() {
        AtomicBoolean permitted = new AtomicBoolean();
        AtomicInteger attempts = new AtomicInteger();
        ApkInstaller.Handoff handoff = new ApkInstaller.Handoff((context, apk, version) -> {
            attempts.incrementAndGet();
            return ApkInstaller.InstallResult.REQUESTED_PERMISSION;
        }, context -> permitted.get());
        handoff.start(null, new File("hongguo-tv-1.3.3.apk"), "1.3.3");
        assertNull(handoff.resume(null));
        permitted.set(true);
        assertNull(handoff.resume(null));
        assertEquals(1, attempts.get());
    }
}
