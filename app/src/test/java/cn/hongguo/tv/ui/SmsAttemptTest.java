package cn.hongguo.tv.ui;

import org.junit.Test;
import static org.junit.Assert.*;

/** Isolated SMS form state transitions; these tests never submit an account request. */
public class SmsAttemptTest {
    private static final String ORIGIN = "synthetic-logged-out-session";

    @Test public void ticketBelongsToTheNumberAndIsInvalidatedOnEdit() {
        SmsAttempt attempt = new SmsAttempt();
        attempt.changeNumber("13800000001");
        long send = attempt.begin();
        attempt.acceptTicket("synthetic-ticket-a");
        assertEquals("synthetic-ticket-a", attempt.ticket());
        attempt.changeNumber("13800000002");
        assertFalse(attempt.current(send, "13800000001"));
        assertTrue(attempt.ticket().isEmpty());
        long replacement = attempt.begin();
        assertTrue(attempt.current(replacement, "13800000002"));
    }

    @Test public void newerRequestMakesOldResponseStale() {
        SmsAttempt attempt = new SmsAttempt();
        attempt.changeNumber("13800000001");
        long first = attempt.begin();
        long second = attempt.begin();
        assertFalse(attempt.current(first, "13800000001"));
        assertTrue(attempt.current(second, "13800000001"));
    }

    @Test public void leavingSmsModeOrPageInvalidatesPendingLogin() {
        SmsAttempt attempt = new SmsAttempt();
        attempt.changeNumber("13800000001");
        long login = attempt.begin();
        attempt.invalidate(); // selecting QR login, leaving the page, or logging out
        assertFalse(attempt.current(login, "13800000001"));
    }

}
