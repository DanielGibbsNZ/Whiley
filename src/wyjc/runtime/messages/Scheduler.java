package wyjc.runtime.messages;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Scheduler {
	
	private final ExecutorService pool;
	
	public Scheduler() {
		pool = Executors.newCachedThreadPool();
	}
	
	public Scheduler(int threadCount) {
		pool = Executors.newFixedThreadPool(threadCount);
	}
	
	public void scheduleResume(Resumable process) {
		pool.execute(new Resumer(process));
	}
	
	public static interface Resumable {
		public void resume();
	}
	
	private static class Resumer implements Runnable {
		
		private final Resumable process;
		
		public Resumer(Resumable process) {
			this.process = process;
		}
		
		@Override
		public void run() {
			process.resume();
		}
		
	}
	
}
