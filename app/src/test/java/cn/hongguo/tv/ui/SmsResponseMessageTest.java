package cn.hongguo.tv.ui;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Regression for error envelopes that omit the optional SMS data object. */
public class SmsResponseMessageTest {
    @Test public void missingDataReportsTheServerErrorInsteadOfThrowing() {
        assertEquals("发送失败（错误码 401）", AccountActivity.smsSendErrorMessage(null, 401));
    }

    @Test public void knownErrorsKeepTheirExistingMessagesWithoutData() throws Exception {
        assertEquals("本机组件未通过服务端校验，暂时无法发送验证码（换号、重试都无效）。请先升级到最新版本。",
                AccountActivity.smsSendErrorMessage(null, 7));
        assertEquals("这个号码发送太频繁，请稍后再试（或换一个号码）",
                AccountActivity.smsSendErrorMessage(new JSONObject().put("description", "受限"), 1206));
    }
}
