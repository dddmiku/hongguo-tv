package cn.hongguo.tv.account;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import static org.junit.Assert.*;

/** Deterministic synthetic account/transport tests; no device or live login. */
public class QrLoginManagerTest {
    static class Clock implements QrLoginManager.Clock {
        long wall=1000,elapsed=10;
        public long wall(){return wall;} public long elapsed(){return elapsed;}
    }
    static class Job {final Runnable run;final long delay;Job(Runnable run,long delay){this.run=run;this.delay=delay;}}
    static class Scheduler implements QrLoginManager.Scheduler {
        final LinkedList<Job> jobs=new LinkedList<>(); final Clock clock; boolean closed;
        Scheduler(Clock clock){this.clock=clock;}
        public void post(Runnable run,long delay){jobs.add(new Job(run,delay));}
        public void close(){closed=true;jobs.clear();}
        void next(){Job job=jobs.removeFirst();clock.wall+=job.delay;clock.elapsed+=job.delay;job.run.run();}
    }
    static class Bridge implements QrLoginManager.Bridge {
        String identity="synthetic-origin";int verifications,commits;boolean verifyFailure,changeDuringVerify;
        Account saved;
        public String identity(){return identity;}
        public Account verify(Account candidate)throws IOException {
            verifications++;if(verifyFailure)throw new QrLoginManager.QrException("QR_MOBILE_SESSION_UNAVAILABLE","合成失败",false);
            if(changeDuringVerify)identity="synthetic-replacement";
            return Account.candidate(candidate.cookie,candidate.token,"synthetic-mobile-uid","测试用户");
        }
        public void commit(Account candidate,String origin)throws IOException {
            assertEquals(origin,identity);commits++;saved=candidate;identity="synthetic-saved";
        }
    }
    static class Transport implements QrLoginManager.Transport {
        final Clock clock;String state="new";int creates,polls,candidates,closes;boolean createNetwork,pollNetwork;
        Runnable onCandidate;
        Transport(Clock clock){this.clock=clock;}
        public QrLoginManager.Ticket create()throws IOException {
            creates++;if(createNetwork)throw new QrLoginManager.QrException("QR_NETWORK","合成网络中断",true);
            return new QrLoginManager.Ticket("synthetic-qr-token",new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff},clock.wall()+60_000);
        }
        public JSONObject poll(QrLoginManager.Ticket ticket)throws IOException {
            polls++;if(pollNetwork)throw new QrLoginManager.QrException("QR_NETWORK","合成网络中断",true);
            try{return new JSONObject().put("status",state);}catch(Exception error){throw new IOException();}
        }
        public Account candidate(JSONObject data){candidates++;if(onCandidate!=null)onCandidate.run();return Account.candidate("sessionid=synthetic-cookie","","","");}
        public void close(){closes++;}
    }
    Clock clock;Scheduler scheduler;Bridge bridge;Transport transport;QrLoginManager manager;
    List<QrLoginManager.State> events;
    @Before public void setup(){clock=new Clock();scheduler=new Scheduler(clock);bridge=new Bridge();transport=new Transport(clock);events=new ArrayList<>();manager=new QrLoginManager(bridge,()->transport,events::add,scheduler,clock);}
    private QrLoginManager.State last(){return events.get(events.size()-1);}
    private void create(){manager.start();scheduler.next();}

    @Test public void createReturnsRasterAndLocalIdWithoutOfficialToken(){create();assertEquals("waiting",last().status);assertEquals(32,last().id.length());assertNotNull(last().imageBytes);assertEquals(0,bridge.commits);assertFalse(last().id.contains("synthetic"));manager.close();}
    @Test public void scanningDoesNotCommit(){create();transport.state="scanned";scheduler.next();assertEquals("scanned",last().status);assertEquals(0,bridge.verifications);assertEquals(0,bridge.commits);manager.close();}
    @Test public void confirmedSessionIsVerifiedBeforeCommit(){create();transport.state="confirmed";scheduler.next();assertEquals("confirmed",last().status);assertEquals(1,bridge.verifications);assertEquals(1,bridge.commits);assertEquals("synthetic-mobile-uid",bridge.saved.uid);assertTrue(last().terminal());manager.close();}
    @Test public void webOnlyConfirmationCannotSaveAccount(){create();transport.state="confirmed";bridge.verifyFailure=true;scheduler.next();assertEquals("failed",last().status);assertEquals(0,bridge.commits);manager.close();}
    @Test public void cancellationStopsScheduledPolls(){create();manager.cancel();scheduler.next();assertEquals("cancelled",last().status);assertEquals(0,transport.polls);assertTrue(transport.closes>0);manager.close();}
    @Test public void accountChangeCancelsBeforePoll(){create();bridge.identity="synthetic-other";scheduler.next();assertEquals("cancelled",last().status);assertEquals(0,transport.polls);manager.close();}
    @Test public void accountChangeDuringVerificationCannotCommit(){create();transport.state="confirmed";bridge.changeDuringVerify=true;scheduler.next();assertEquals("cancelled",last().status);assertEquals(0,bridge.commits);manager.close();}
    @Test public void cancelledInflightCandidateCannotCommit(){create();transport.state="confirmed";transport.onCandidate=manager::cancel;scheduler.next();assertEquals("cancelled",last().status);assertEquals(0,bridge.verifications);manager.close();}
    @Test public void pollNetworkFailureKeepsStateAndRetriesAfterShortDelay(){create();transport.pollNetwork=true;scheduler.next();assertEquals("waiting",last().status);assertNull(last().imageBytes);assertEquals(2000,scheduler.jobs.getFirst().delay);transport.pollNetwork=false;scheduler.next();assertEquals("waiting",last().status);assertEquals(2,transport.polls);manager.close();}
    @Test public void createNetworkRetryIsBounded(){transport.createNetwork=true;manager.start();for(int i=0;i<4;i++)scheduler.next();assertEquals("failed",last().status);assertEquals(4,transport.creates);assertTrue(scheduler.jobs.isEmpty());manager.close();}
    @Test public void expiredAttemptNeverRequestsAgain(){create();clock.elapsed+=60_000;scheduler.next();assertEquals("expired",last().status);assertEquals(0,transport.polls);assertTrue(scheduler.jobs.isEmpty());manager.close();}
    @Test public void refreshedAttemptIgnoresPreviousScheduledWork(){create();String old=last().id;manager.start();scheduler.next();scheduler.next();assertEquals("waiting",last().status);assertNotEquals(old,last().id);assertEquals(0,transport.polls);assertEquals(0,bridge.commits);manager.close();}
    @Test public void closingCancelsAndReleasesWorker(){create();manager.close();assertEquals("cancelled",last().status);assertTrue(scheduler.closed);int count=events.size();manager.start();assertEquals(count,events.size());}
}
