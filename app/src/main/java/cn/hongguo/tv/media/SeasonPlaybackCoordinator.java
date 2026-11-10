package cn.hongguo.tv.media;

/** Main-thread state for delayed season work; a user action revokes pending continuation. */
public final class SeasonPlaybackCoordinator {
    public static final class Ticket {
        public final long generation;
        public final String seriesId, identity;
        private Ticket(long generation, String seriesId, String identity) {
            this.generation = generation;
            this.seriesId = seriesId;
            this.identity = identity;
        }
    }

    private volatile long generation;
    private String seriesId = "", identity = "";
    private boolean ended, completionArmed;

    public void enter(String seriesId, String identity) {
        this.seriesId = seriesId;
        this.identity = identity;
        cancel();
    }

    public void cancel() { generation++; ended = false; completionArmed = false; }
    public Ticket ticket() { return new Ticket(generation, seriesId, identity); }
    public void armCompletion() { completionArmed = true; }
    public boolean completionArmed() { return completionArmed; }
    public void completed() { if (completionArmed) ended = true; }
    public void consumed() { ended = false; }
    public boolean ended() { return ended; }
    public boolean isCurrent(Ticket ticket) { return ticket != null && ticket.generation == generation; }

    public boolean accepts(Ticket ticket, String currentSeries, String currentIdentity,
                           boolean foreground) {
        return foreground && ticket != null && ticket.generation == generation
                && ticket.seriesId.equals(seriesId) && ticket.seriesId.equals(currentSeries)
                && ticket.identity.equals(identity) && ticket.identity.equals(currentIdentity);
    }

    public boolean canContinue(Ticket ticket, String currentSeries, String currentIdentity,
                               boolean foreground, boolean autoplay) {
        return autoplay && ended && accepts(ticket, currentSeries, currentIdentity, foreground);
    }
}
