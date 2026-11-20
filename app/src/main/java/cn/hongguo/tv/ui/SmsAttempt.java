package cn.hongguo.tv.ui;

/** State for the number being edited, confined to the Activity's main thread. */
final class SmsAttempt {
    private long generation;
    private String number = "", ticket = "";

    void changeNumber(String next) {
        if (number.equals(next)) return;
        number = next;
        ticket = "";
        invalidate();
    }

    void invalidate() { generation++; }

    long begin() { return ++generation; }

    boolean current(long request, String requestedNumber) {
        return generation == request && number.equals(requestedNumber);
    }

    void acceptTicket(String value) { ticket = value; }

    String ticket() { return ticket; }
}
