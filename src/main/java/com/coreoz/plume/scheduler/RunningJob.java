package com.coreoz.plume.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.coreoz.plume.scheduler.time.TimeProvider;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.experimental.Accessors;

@Setter
@Getter
@Accessors(fluent = true)
class RunningJob implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(RunningJob.class);

	private final Job job;
	private final Scheduler scheduler;
	private final TimeProvider timeProvider;

	private volatile boolean shouldExecuteJob = true;

	RunningJob(Job job, Scheduler scheduler, TimeProvider timeProvider) {
		this.job = job;
		this.scheduler = scheduler;
		this.timeProvider = timeProvider;
	}

	@Override
	public void run() {
		job.status().set(JobStatus.READY);
		if(waitAndNotifySchedulerBeforeExecution()) {
			long startExecutionTime = timeProvider.currentTime();
			logger.debug("Starting job {} execution...", job.name());

			try {
				job.runnable().run();
			} catch(Throwable t) {
				logger.error("Error during job {} execution", job.name(), t);
			}
			job.executionsCount().incrementAndGet();
			job.lastExecutionTimeInMillis(timeProvider.currentTime());

			if(logger.isDebugEnabled()) {
				logger.debug(
					"Job {} executed in {}ms", job.name(),
					timeProvider.currentTime() - startExecutionTime
				);
			}
		} else {
			logger.trace("Cancelling job {} execution", job.name());
		}
		scheduler.parkInPool(job, true);
	}

	private boolean waitAndNotifySchedulerBeforeExecution() {
		if(waitUntilExecution()) {
			job.status().set(JobStatus.RUNNING);
			scheduler.checkNextJobToRun(false);
			return true;
		}
		return false;
	}

	/**
	 * Returns true if the execution should take place, false if the execution should be suspended
	 */
	@SneakyThrows
	private boolean waitUntilExecution() {
		long timeBeforeNextExecution;
		do {
			timeBeforeNextExecution = timeBeforeNextExecution();
			if(timeBeforeNextExecution > 0) {
				synchronized (job) {
					job.wait(timeBeforeNextExecution);
				}
			}
		} while (timeBeforeNextExecution > 0 && shouldExecuteJob);

		if(timeBeforeNextExecution < 0) {
			logger.debug("Job {} execution is {}ms late", job.name(), -timeBeforeNextExecution);
		}

		return shouldExecuteJob;
	}

	private long timeBeforeNextExecution() {
		return job.nextExecutionTimeInMillis().get() - timeProvider.currentTime();
	}

	@Override
	public String toString() {
		return "*" + job.name() + "*";
	}
}
