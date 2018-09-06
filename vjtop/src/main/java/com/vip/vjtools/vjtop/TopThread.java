package com.vip.vjtools.vjtop;

import java.io.IOException;
import java.lang.management.ThreadInfo;

import com.vip.vjtools.vjtop.VMDetailView.DetailMode;
import com.vip.vjtools.vjtop.util.LongObjectHashMap;
import com.vip.vjtools.vjtop.util.LongObjectMap;
import com.vip.vjtools.vjtop.util.Utils;

public class TopThread {
	private VMInfo vmInfo;

	private long[] topTidArray;

	private LongObjectMap<Long> lastThreadCpuTotalTimes = new LongObjectHashMap<Long>();
	private LongObjectMap<Long> lastThreadSysCpuTotalTimes = new LongObjectHashMap<Long>();
	private LongObjectMap<Long> lastThreadMemoryTotalBytes = new LongObjectHashMap<Long>();

	public TopThread(VMInfo vmInfo) {
		this.vmInfo = vmInfo;
	}

	public TopCpuResult topCpuThreads(DetailMode mode, int threadLimit) throws IOException {

		long tids[] = vmInfo.getThreadMXBean().getAllThreadIds();

		TopCpuResult result = new TopCpuResult();

		int mapSize = tids.length * 2;
		result.threadCpuTotalTimes = new LongObjectHashMap<Long>(mapSize);
		result.threadCpuDeltaTimes = new LongObjectHashMap<>(mapSize);
		result.threadSysCpuTotalTimes = new LongObjectHashMap<>(mapSize);
		result.threadSysCpuDeltaTimes = new LongObjectHashMap<>(mapSize);

		// 批量获取CPU times，性能大幅提高。
		// 两次获取之间有间隔，在低流量下可能造成负数
		long[] threadCpuTotalTimeArray = vmInfo.getThreadMXBean().getThreadCpuTime(tids);
		long[] threadUserCpuTotalTimeArray = vmInfo.getThreadMXBean().getThreadUserTime(tids);

		// 过滤CPU占用太少的线程，每秒0.05%CPU (0.5ms cpu time)
		long minDeltaCpuTime = (vmInfo.upTimeMills.delta * Utils.NANOS_TO_MILLS / 2000);

		// 计算本次CPU Time
		// 此算法第一次不会显示任何数据，保证每次显示都只显示区间内数据
		for (int i = 0; i < tids.length; i++) {
			long tid = tids[i];
			Long threadCpuTotalTime = threadCpuTotalTimeArray[i];
			result.threadCpuTotalTimes.put(tid, threadCpuTotalTime);

			Long lastTime = lastThreadCpuTotalTimes.get(tid);
			if (lastTime != null) {
				Long deltaThreadCpuTime = threadCpuTotalTime - lastTime;
				if (deltaThreadCpuTime >= minDeltaCpuTime) {
					result.threadCpuDeltaTimes.put(tid, deltaThreadCpuTime);
					result.deltaAllThreadCpu += deltaThreadCpuTime;
				}
			}
		}

		// 计算本次SYSCPU Time
		for (int i = 0; i < tids.length; i++) {
			long tid = tids[i];
			// 因为totalTime 与 userTime 的获取时间有先后，实际sys接近0时，后取的userTime可能比前一时刻的totalTime高，计算出来的sysTime可为负数
			Long threadSysCpuTotalTime = Math.max(0, threadCpuTotalTimeArray[i] - threadUserCpuTotalTimeArray[i]);
			result.threadSysCpuTotalTimes.put(tid, threadSysCpuTotalTime);

			Long lastTime = lastThreadSysCpuTotalTimes.get(tid);
			if (lastTime != null) {
				Long deltaThreadSysCpuTime = Math.max(0, threadSysCpuTotalTime - lastTime);
				if (deltaThreadSysCpuTime >= minDeltaCpuTime) {
					result.threadSysCpuDeltaTimes.put(tid, deltaThreadSysCpuTime);
					result.deltaAllThreadSysCpu += deltaThreadSysCpuTime;
				}
			}
		}

		if (lastThreadCpuTotalTimes.isEmpty()) {
			lastThreadCpuTotalTimes = result.threadCpuTotalTimes;
			lastThreadSysCpuTotalTimes = result.threadSysCpuTotalTimes;
			result.ready = false;
			return result;
		}

		// 按不同类型排序,过滤
		if (mode == DetailMode.cpu) {
			topTidArray = Utils.sortAndFilterThreadIdsByValue(result.threadCpuDeltaTimes, threadLimit);
			result.noteableThreads = result.threadCpuDeltaTimes.size();
		} else if (mode == DetailMode.syscpu) {
			topTidArray = Utils.sortAndFilterThreadIdsByValue(result.threadSysCpuDeltaTimes, threadLimit);
			result.noteableThreads = result.threadSysCpuDeltaTimes.size();
		} else if (mode == DetailMode.totalcpu) {
			topTidArray = Utils.sortAndFilterThreadIdsByValue(result.threadCpuTotalTimes, threadLimit);
			result.noteableThreads = result.threadCpuTotalTimes.size();
		} else if (mode == DetailMode.totalsyscpu) {
			topTidArray = Utils.sortAndFilterThreadIdsByValue(result.threadSysCpuTotalTimes, threadLimit);
			result.noteableThreads = result.threadSysCpuTotalTimes.size();
		} else {
			throw new RuntimeException("unkown mode:" + mode);
		}

		if (result.noteableThreads == 0) {
			System.out.printf("%n -Every thread use cpu lower than 0.05%%-%n");
		}

		// 获得threadInfo
		result.threadInfos = vmInfo.getThreadMXBean().getThreadInfo(topTidArray);

		lastThreadCpuTotalTimes = result.threadCpuTotalTimes;
		lastThreadSysCpuTotalTimes = result.threadSysCpuTotalTimes;
		return result;
	}


	public TopMemoryResult topMemoryThreads(DetailMode mode, int threadLimit) throws IOException {
		long tids[] = vmInfo.getThreadMXBean().getAllThreadIds();
		TopMemoryResult result = new TopMemoryResult();

		int mapSize = tids.length * 2;
		result.threadMemoryTotalBytesMap = new LongObjectHashMap<Long>(mapSize);
		result.threadMemoryDeltaBytesMap = new LongObjectHashMap<Long>(mapSize);

		// 批量获取内存分配
		long[] threadMemoryTotalBytesArray = vmInfo.getThreadMXBean().getThreadAllocatedBytes(tids);

		// 过滤太少的线程，每秒小于1k
		long minDeltaMemory = vmInfo.upTimeMills.delta * 1024 / 1000;

		// 此算法第一次不会显示任何数据，保证每次显示都只显示区间内数据
		for (int i = 0; i < tids.length; i++) {
			long tid = tids[i];
			Long threadMemoryTotalBytes = threadMemoryTotalBytesArray[i];
			result.threadMemoryTotalBytesMap.put(tid, threadMemoryTotalBytes);
			result.totalAllThreadBytes += threadMemoryTotalBytes;

			Long threadMemoryDeltaBytes = 0L;
			Long lastBytes = lastThreadMemoryTotalBytes.get(tid);

			if (lastBytes != null) {
				threadMemoryDeltaBytes = threadMemoryTotalBytes - lastBytes;
				if (threadMemoryDeltaBytes >= minDeltaMemory) {
					result.threadMemoryDeltaBytesMap.put(tid, threadMemoryDeltaBytes);
					result.deltaAllThreadBytes += threadMemoryDeltaBytes;
				}
			}
		}

		if (lastThreadMemoryTotalBytes.isEmpty()) {
			lastThreadMemoryTotalBytes = result.threadMemoryTotalBytesMap;
			result.ready = false;
			return result;
		}

		// 线程排序
		long[] topTidArray;
		if (mode == DetailMode.memory) {
			topTidArray = Utils.sortAndFilterThreadIdsByValue(result.threadMemoryDeltaBytesMap, threadLimit);
			result.noteableThreads = result.threadMemoryDeltaBytesMap.size();
		} else {
			topTidArray = Utils.sortAndFilterThreadIdsByValue(result.threadMemoryTotalBytesMap, threadLimit);
			result.noteableThreads = result.threadMemoryTotalBytesMap.size();
		}

		if (result.noteableThreads == 0) {
			System.out.printf("%n -Every thread allocate memory slower than 1k/s-%n");
		}

		result.threadInfos = vmInfo.getThreadMXBean().getThreadInfo(topTidArray);

		lastThreadMemoryTotalBytes = result.threadMemoryTotalBytesMap;

		return result;
	}

	public ThreadInfo[] getTopThreadInfo() throws IOException {
		return vmInfo.getThreadMXBean().getThreadInfo(topTidArray, 20);
	}

	public void cleanupThreadsHistory() {
		this.lastThreadCpuTotalTimes.clear();
		this.lastThreadSysCpuTotalTimes.clear();
		this.lastThreadMemoryTotalBytes.clear();
	}

	public static class TopCpuResult {
		public ThreadInfo[] threadInfos;
		public long noteableThreads = 0;

		public long deltaAllThreadCpu = 0;
		public long deltaAllThreadSysCpu = 0;

		public LongObjectMap<Long> threadCpuTotalTimes;
		public LongObjectMap<Long> threadCpuDeltaTimes;
		public LongObjectMap<Long> threadSysCpuTotalTimes;
		public LongObjectMap<Long> threadSysCpuDeltaTimes;

		public boolean ready = true;
	}

	public static class TopMemoryResult {
		public ThreadInfo[] threadInfos;
		public long noteableThreads = 0;

		public long deltaAllThreadBytes = 0;
		public long totalAllThreadBytes = 0;

		public LongObjectMap<Long> threadMemoryTotalBytesMap;
		public LongObjectMap<Long> threadMemoryDeltaBytesMap;

		public boolean ready = true;
	}
}
