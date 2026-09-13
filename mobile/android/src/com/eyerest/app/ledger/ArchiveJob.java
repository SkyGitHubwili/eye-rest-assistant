package com.eyerest.app.ledger;
import android.app.job.*;
import android.content.*;
import java.util.concurrent.*;
public class ArchiveJob extends JobService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Future<?> task;
    public static void schedule(Context c) {
        JobScheduler s=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(s!=null && UsageRepository.allowed(c) && s.getPendingJob(701)==null)
            s.schedule(new JobInfo.Builder(701,new ComponentName(c,ArchiveJob.class)).setPeriodic(6*60*60*1000L).setPersisted(true).build());
    }
    @Override public boolean onStartJob(JobParameters p) {
        task=worker.submit(()->{ boolean retry=false; try(UsageRepository r=new UsageRepository(this)) { if(UsageRepository.allowed(this))r.archiveRecent(); } catch(Exception e) { retry=true; } if(!Thread.currentThread().isInterrupted())jobFinished(p,retry); }); return true;
    }
    @Override public boolean onStopJob(JobParameters p) { if(task!=null)task.cancel(true); return true; }
    @Override public void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
