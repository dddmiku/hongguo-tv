package cn.hongguo.tv.media;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class DecryptProbeTest {
    @Test public void failureSummaryDoesNotExposeExceptionDetails() {
        String syntheticKey = "synthetic-key-6f3a2d9c";
        String summary = DecryptProbe.failureSummary(
                new IllegalStateException("failed to decrypt with key=" + syntheticKey));
        assertEquals("验证失败", summary);
        assertFalse(summary.contains(syntheticKey));
    }
}
