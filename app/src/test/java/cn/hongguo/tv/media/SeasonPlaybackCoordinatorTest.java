package cn.hongguo.tv.media;

import org.junit.Test;
import static org.junit.Assert.*;

public class SeasonPlaybackCoordinatorTest {
    @Test public void onlyForegroundSameSeasonAndAccountMayReceiveWork() {
        SeasonPlaybackCoordinator state = new SeasonPlaybackCoordinator();
        state.enter("season-one", "account-one");
        SeasonPlaybackCoordinator.Ticket ticket = state.ticket();
        assertTrue(state.accepts(ticket, "season-one", "account-one", true));
        assertFalse(state.accepts(ticket, "season-two", "account-one", true));
        assertFalse(state.accepts(ticket, "season-one", "account-two", true));
        assertFalse(state.accepts(ticket, "season-one", "account-one", false));
    }

    @Test public void lastSelectionWinsEvenWhenOlderTargetFinishesLater() {
        SeasonPlaybackCoordinator state = new SeasonPlaybackCoordinator();
        state.enter("source", "account");
        SeasonPlaybackCoordinator.Ticket firstSelection = state.ticket();
        state.cancel();
        SeasonPlaybackCoordinator.Ticket finalSelection = state.ticket();
        assertFalse(state.accepts(firstSelection, "source", "account", true));
        assertTrue(state.accepts(finalSelection, "source", "account", true));
        state.enter("target", "account");
        assertFalse(state.accepts(finalSelection, "target", "account", true));
    }

    @Test public void lateNextFileRequiresCompletionAndAutoplay() {
        SeasonPlaybackCoordinator state = new SeasonPlaybackCoordinator();
        state.enter("source", "account");
        SeasonPlaybackCoordinator.Ticket prefetch = state.ticket();
        assertFalse(state.canContinue(prefetch, "source", "account", true, true));
        state.armCompletion(); state.completed();
        assertTrue(state.canContinue(prefetch, "source", "account", true, true));
        assertFalse(state.canContinue(prefetch, "source", "account", true, false));
        state.consumed();
        assertFalse(state.canContinue(prefetch, "source", "account", true, true));
    }

    @Test public void pauseSeekHiddenAndAccountChangeRevokeLateContinuation() {
        for (int action = 0; action < 4; action++) {
            SeasonPlaybackCoordinator state = new SeasonPlaybackCoordinator();
            state.enter("source", "account");
            SeasonPlaybackCoordinator.Ticket prefetch = state.ticket();
            state.armCompletion(); state.completed();
            if (action == 3) state.enter("source", "other-account");
            else state.cancel();
            state.completed();
            assertFalse(state.ended());
            assertFalse(state.canContinue(prefetch, "source", "account", true, true));
        }
    }

    @Test public void completionQueuedBeforePauseCannotRearmAnAutomaticJump() {
        SeasonPlaybackCoordinator state = new SeasonPlaybackCoordinator();
        state.enter("source", "account");
        state.armCompletion();
        state.cancel();
        state.completed();
        assertFalse(state.ended());
        assertFalse(state.completionArmed());
        state.armCompletion();
        state.completed();
        assertTrue(state.ended());
    }
}
