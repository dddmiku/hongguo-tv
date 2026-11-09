package cn.hongguo.tv.account;

import org.json.JSONObject;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One isolated, cancellable QR attempt. Its public state never contains credentials. */
public final class QrLoginManager implements AutoCloseable {
    public interface Listener { void onState(State state); }
    public static final class State {
        public final String id,status,message;
        public final long expiresAt;
        public final byte[] imageBytes;
        public State(String id,String status,String message,long expiresAt,byte[] image) {
            this.id=id; this.status=status; this.message=message; this.expiresAt=expiresAt;
            imageBytes=image==null?null:image.clone();
        }
        public boolean terminal() { return isTerminal(status); }
    }
    public static final class QrException extends IOException {
        public final String code;
        public final boolean retryable;
        QrException(String code,String message,boolean retryable) { super(message); this.code=code; this.retryable=retryable; }
    }
    interface Bridge {
        String identity();
        Account verify(Account candidate)throws IOException;
        void commit(Account candidate,String originIdentity)throws IOException;
    }
    interface Transport extends AutoCloseable {
        Ticket create()throws IOException;
        JSONObject poll(Ticket ticket)throws IOException;
        Account candidate(JSONObject confirmation)throws IOException;
        void close();
    }
    interface Factory { Transport create(); }
    interface Scheduler { void post(Runnable task,long delayMillis); void close(); }
    interface Clock { long wall(); long elapsed(); }
    static final class Ticket {
        final String token;
        final byte[] image;
        final long expiresAt;
        Ticket(String token,byte[] image,long expiresAt) { this.token=token; this.image=image; this.expiresAt=expiresAt; }
    }
    private static final class Worker implements Scheduler {
        private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(task->{
            Thread thread=new Thread(task,"hongguo-qr-login"); thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY-1); return thread;
        });
        public void post(Runnable task,long delay){ if(!executor.isShutdown())executor.schedule(task,delay,TimeUnit.MILLISECONDS); }
        public void close(){executor.shutdownNow();}
    }
    private static final class Attempt {
        final String id=UUID.randomUUID().toString().replace("-","");
        final String origin;
        final Transport transport;
        Ticket ticket;
        Account candidate;
        String status="loading";
        long deadline;
        int createRetries,verifyRetries;
        Attempt(String origin,Transport transport,long deadline){this.origin=origin;this.transport=transport;this.deadline=deadline;}
    }
    private final Object lock=new Object();
    private final Bridge bridge;
    private final Factory factory;
    private final Listener listener;
    private final Scheduler scheduler;
    private final Clock clock;
    private Attempt current;
    private boolean closed;

    public QrLoginManager(AccountApi account,Listener listener) {
        this(new Bridge(){
            public String identity(){return account.qrIdentity();}
            public Account verify(Account candidate)throws IOException{return account.verifyQrCandidate(candidate);}
            public void commit(Account candidate,String identity)throws IOException{account.commitQrCandidate(candidate,identity);}
        },OfficialQrTransport::new,listener,new Worker(),new Clock(){
            public long wall(){return System.currentTimeMillis();}
            public long elapsed(){return System.nanoTime()/1_000_000L;}
        });
    }
    QrLoginManager(Bridge bridge,Factory factory,Listener listener,Scheduler scheduler,Clock clock) {
        this.bridge=bridge; this.factory=factory; this.listener=listener; this.scheduler=scheduler; this.clock=clock;
    }

    public void start() {
        synchronized(lock) {
            if(closed)return;
            discard(current);
            current=new Attempt(bridge.identity(),factory.create(),clock.elapsed()+60_000);
            Attempt attempt=current;
            emit(attempt,"loading","正在生成红果登录二维码",null);
            scheduler.post(()->create(attempt),0);
        }
    }

    private void create(Attempt attempt) {
        try {
            Ticket ticket=attempt.transport.create();
            synchronized(lock) {
                if(!valid(attempt))return;
                long ttl=ticket.expiresAt-clock.wall();
                if(ttl<=0||ttl>600_000)throw new QrException("QR_EXPIRED","二维码已失效，请刷新二维码",false);
                attempt.ticket=ticket; attempt.deadline=clock.elapsed()+ttl;
                emit(attempt,"waiting","使用红果短剧 App 扫码登录",ticket.image);
                scheduler.post(()->poll(attempt),1500);
            }
        } catch(Exception error){failed(attempt,error,"create");}
    }

    private void poll(Attempt attempt) {
        synchronized(lock){if(!valid(attempt))return;}
        try {
            JSONObject data=attempt.transport.poll(attempt.ticket);
            String status=data.optString("status","");
            synchronized(lock) {
                if(!valid(attempt))return;
                if("new".equals(status)||"1".equals(status)) {
                    emit(attempt,"waiting","使用红果短剧 App 扫码登录",null);
                } else if("scanned".equals(status)||"2".equals(status)) {
                    emit(attempt,"scanned","已扫码，请在红果短剧 App 中确认登录",null);
                } else if("expired".equals(status)||"5".equals(status)) {
                    finish(attempt,"expired","二维码已失效，请刷新二维码");return;
                } else if("cancelled".equals(status)||"rejected".equals(status)||"6".equals(status)) {
                    finish(attempt,"cancelled","已取消扫码登录，请刷新二维码重试");return;
                } else if("confirmed".equals(status)||"3".equals(status)) {
                    emit(attempt,"verifying","手机已确认，正在连接历史与收藏",null);
                } else if("4".equals(status)) {
                    byte[] image=OfficialQrTransport.image(data.optString("qrcode",""));
                    String token=data.optString("token","");
                    if(!OfficialQrTransport.text(token,8192))throw new QrException("QR_INVALID_RESPONSE","扫码登录状态异常，请刷新二维码",false);
                    attempt.ticket=new Ticket(token,image,attempt.ticket.expiresAt);
                    emit(attempt,"waiting","使用红果短剧 App 扫码登录",image);
                } else throw new QrException("QR_INVALID_RESPONSE","扫码登录状态异常，请刷新二维码",false);
                if(!"verifying".equals(attempt.status)){scheduler.post(()->poll(attempt),1500);return;}
            }
            Account candidate=attempt.transport.candidate(data);
            synchronized(lock){if(!valid(attempt))return;attempt.candidate=candidate;}
            verify(attempt);
        }catch(Exception error){failed(attempt,error,"poll");}
    }

    private void verify(Attempt attempt) {
        synchronized(lock){if(!valid(attempt))return;}
        try {
            Account verified=bridge.verify(attempt.candidate);
            synchronized(lock) {
                if(!valid(attempt))return;
                try { bridge.commit(verified,attempt.origin); }
                catch(Exception error){failed(attempt,error,"commit");return;}
                finish(attempt,"confirmed","扫码登录成功，已连接手机历史与收藏");
            }
        }catch(Exception error){failed(attempt,error,"verify");}
    }

    private void failed(Attempt attempt,Exception error,String stage) {
        synchronized(lock) {
            if(!valid(attempt))return;
            QrException failure=error instanceof QrException?(QrException)error:
                    new QrException("QR_NETWORK","commit".equals(stage)?"扫码登录暂时无法完成，请刷新二维码重试":"网络暂时中断，正在重试",!"commit".equals(stage));
            if("ACCOUNT_CHANGED".equals(failure.code)){finish(attempt,"cancelled",failure.getMessage());return;}
            if("QR_EXPIRED".equals(failure.code)){finish(attempt,"expired","二维码已失效，请刷新二维码");return;}
            boolean canRetry=failure.retryable;
            if("create".equals(stage))canRetry=canRetry&&++attempt.createRetries<=3;
            if("verify".equals(stage))canRetry=canRetry&&++attempt.verifyRetries<=3;
            if(canRetry) {
                emit(attempt,attempt.status,"网络暂时中断，正在重试",null);
                scheduler.post(()->{if("create".equals(stage))create(attempt);else if("verify".equals(stage))verify(attempt);else poll(attempt);},2000);
            } else finish(attempt,"failed",failure.getMessage());
        }
    }

    private boolean valid(Attempt attempt) {
        if(closed||current!=attempt||isTerminal(attempt.status))return false;
        if(!attempt.origin.equals(bridge.identity())){finish(attempt,"cancelled","账号已切换，已取消之前的扫码登录");return false;}
        if(clock.elapsed()>=attempt.deadline){finish(attempt,"expired","二维码已失效，请刷新二维码");return false;}
        return true;
    }
    private static boolean isTerminal(String status) {
        return "confirmed".equals(status)||"expired".equals(status)||"cancelled".equals(status)||"failed".equals(status);
    }
    private void emit(Attempt attempt,String status,String message,byte[] image) {
        attempt.status=status;
        if(listener!=null)listener.onState(new State(attempt.id,status,message,attempt.ticket==null?0:attempt.ticket.expiresAt,image));
    }
    private void finish(Attempt attempt,String status,String message){emit(attempt,status,message,null);discard(attempt);}
    private static void discard(Attempt attempt) {
        if(attempt==null)return;
        attempt.ticket=null;attempt.candidate=null;
        try{attempt.transport.close();}catch(Exception ignored){}
    }
    public void cancel(){synchronized(lock){if(current!=null&&!isTerminal(current.status))finish(current,"cancelled","已取消扫码登录");}}
    @Override public void close(){synchronized(lock){cancel();closed=true;}scheduler.close();}
}
