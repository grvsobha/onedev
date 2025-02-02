package io.onedev.server.buildspec.job;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.eclipse.jgit.lib.ObjectId;

import io.onedev.commons.utils.TaskLogger;
import io.onedev.k8shelper.Action;
import io.onedev.server.buildspec.Service;
import io.onedev.server.job.resource.ResourceHolder;

public abstract class JobContext {
	
	private final String projectName;
	
	private final Long buildNumber;
	
	private final File projectGitDir;
	
	private final List<Action> actions;
	
	private final int cpuRequirement;
	
	private final int memoryRequirement;
	
	private final ObjectId commitId;
	
	private final Collection<CacheSpec> cacheSpecs; 
	
	private final List<Service> services;
	
	private final int cacheTTL;
	
	private final int retried;
	
	private final TaskLogger logger;	
	
	private final Collection<String> allocatedCaches = new HashSet<>();
	
	private final Map<String, Integer> cacheCounts = new ConcurrentHashMap<>();
	
	protected final Collection<Thread> serverStepThreads = new ArrayList<>();
	
	public JobContext(String projectName, Long buildNumber, File projectGitDir, 
			List<Action> actions, int cpuRequirement, int memoryRequirement, ObjectId commitId, 
			Collection<CacheSpec> caches, int cacheTTL, int retried, List<Service> services, 
			TaskLogger logger) {
		this.projectName = projectName;
		this.buildNumber = buildNumber;
		this.projectGitDir = projectGitDir;
		this.actions = actions;
		this.cpuRequirement = cpuRequirement;
		this.memoryRequirement = memoryRequirement;
		this.commitId = commitId;
		this.cacheSpecs = caches;
		this.cacheTTL = cacheTTL;
		this.retried = retried;
		this.services = services;
		this.logger = logger;
	}
	
	public String getProjectName() {
		return projectName;
	}

	public Long getBuildNumber() {
		return buildNumber;
	}

	public File getProjectGitDir() {
		return projectGitDir;
	}

	public List<Action> getActions() {
		return actions;
	}

	public ObjectId getCommitId() {
		return commitId;
	}

	public int getCpuRequirement() {
		return cpuRequirement;
	}

	public int getMemoryRequirement() {
		return memoryRequirement;
	}

	public Collection<CacheSpec> getCacheSpecs() {
		return cacheSpecs;
	}

	public TaskLogger getLogger() {
		return logger;
	}
	
	public int getCacheTTL() {
		return cacheTTL;
	}

	public int getRetried() {
		return retried;
	}

	public Collection<String> getAllocatedCaches() {
		return allocatedCaches;
	}

	public List<Service> getServices() {
		return services;
	}

	public Map<String, Integer> getCacheCounts() {
		return cacheCounts;
	}
	
	public abstract void notifyJobRunning(@Nullable Long agentId);
	
	public abstract void reportJobWorkspace(String jobWorkspace);
	
	public Map<String, byte[]> runServerStep(List<Integer> stepPosition, 
			File filesDir, Map<String, String> placeholderValues, TaskLogger logger) {
		Thread thread = Thread.currentThread();
		synchronized (serverStepThreads) {
			serverStepThreads.add(thread);
		}
		try {
			return doRunServerStep(stepPosition, filesDir, placeholderValues, logger);
		} finally {
			synchronized (serverStepThreads) {
				serverStepThreads.remove(thread);
			}
		}
	}
	
	protected abstract Map<String, byte[]> doRunServerStep(List<Integer> stepPosition, 
			File filesDir, Map<String, String> placeholderValues, TaskLogger logger);
	
	public abstract void copyDependencies(File targetDir);
	
	public void onJobFinished() {
		synchronized (serverStepThreads) {
			for (Thread thread: serverStepThreads)
				thread.interrupt();
		}
	}
	
	public Map<String, Integer> getResourceRequirements() {
		int cpu = getCpuRequirement();
		int memory = getMemoryRequirement();
		for (Service service: getServices()) {
			cpu += service.getCpuRequirement();
			memory += service.getMemoryRequirement();
		}
		
		Map<String, Integer> resourceRequirements = new HashMap<>();
		resourceRequirements.put(ResourceHolder.CPU, cpu);
		resourceRequirements.put(ResourceHolder.MEMORY, memory);
		
		return resourceRequirements;
	}
	
}
