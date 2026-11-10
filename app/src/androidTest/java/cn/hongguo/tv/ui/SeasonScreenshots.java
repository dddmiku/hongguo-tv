package cn.hongguo.tv.ui;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Bounded visual evidence from the isolated, logged-out QA application. */
final class SeasonScreenshots {
    static void capture(Instrumentation instrumentation, String name) throws IOException {
        if (!instrumentation.getTargetContext().getPackageName().endsWith(".qa"))
            throw new IllegalStateException("Season screenshots require the QA package");
        instrumentation.waitForIdleSync();
        SystemClock.sleep(350);
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        if (bitmap == null) return;
        File output = new File(instrumentation.getTargetContext().getExternalFilesDir(null), name + ".png");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                throw new IOException("Unable to save season screenshot");
        } finally {
            bitmap.recycle();
        }
    }
}
