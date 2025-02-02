package io.onedev.server.job.resource;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.jetty.websocket.api.Session;
import org.hibernate.query.Query;

import io.onedev.agent.AgentData;
import io.onedev.commons.launcher.loader.Listen;
import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.server.entitymanager.AgentManager;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.event.agent.AgentConnected;
import io.onedev.server.event.agent.AgentDisconnected;
import io.onedev.server.event.entity.EntityPersisted;
import io.onedev.server.event.entity.EntityRemoved;
import io.onedev.server.event.system.SystemStarted;
import io.onedev.server.model.Agent;
import io.onedev.server.model.Setting;
import io.onedev.server.model.support.administration.GlobalBuildSetting;
import io.onedev.server.persistence.annotation.Sessional;
import io.onedev.server.persistence.annotation.Transactional;
import io.onedev.server.persistence.dao.Dao;
import io.onedev.server.search.entity.agent.AgentQuery;

@Singleton
public class DefaultResourceManager implements ResourceManager {

	private final SettingManager settingManager;
	
	private final AgentManager agentManager;
	
	private ResourceHolder serverResourceHolder;
	
	private final Map<Long, Boolean> agentPaused = new HashMap<>();
	
	private final Map<Long, ResourceHolder> agentResourceHolders = new HashMap<>();
	
	private final Map<String, QueryCache> queryCaches = new HashMap<>();
	
	private final Dao dao;
	
	@Inject
	public DefaultResourceManager(Dao dao, SettingManager settingManager, AgentManager agentManager) {
		this.dao = dao;
		this.settingManager = settingManager;
		this.agentManager = agentManager;
	}
	
	@SuppressWarnings("unchecked")
	@Transactional
	@Listen
	public synchronized void on(SystemStarted event) {
		Map<String, Integer> resources = new HashMap<>();
		resources.put(ResourceHolder.CPU, settingManager.getBuildSetting().getCpu());
		resources.put(ResourceHolder.MEMORY, settingManager.getBuildSetting().getMemory());
		serverResourceHolder = new ResourceHolder(resources);
		
		Query<?> query = dao.getSession().createQuery(String.format("select id, %s from Agent", Agent.PROP_PAUSED));
		for (Object[] fields: (List<Object[]>)query.list()) 
			agentPaused.put((Long)fields[0], (Boolean)fields[1]);
	}

	@Transactional
	@Listen
	public synchronized void on(AgentConnected event) {
		Agent agent = event.getAgent();
		agentResourceHolders.put(agent.getId(), new ResourceHolder(agent.getResources()));
		for (QueryCache cache: queryCaches.values()) {
			if (cache.query.matches(agent))
				cache.result.add(agent.getId());
		}
		notifyAll();
	}
	
	@Transactional
	@Listen
	public synchronized void on(AgentDisconnected event) {
		Long agentId = event.getAgent().getId();
		agentResourceHolders.remove(agentId);
		for (QueryCache cache: queryCaches.values())
			cache.result.remove(agentId);
	}
	
	@Transactional
	@Listen
	public void on(EntityPersisted event) {
		if (serverResourceHolder != null && event.getEntity() instanceof Setting) {
			Setting setting = (Setting) event.getEntity();
			if (setting.getKey() == Setting.Key.BUILD) {
				GlobalBuildSetting buildSetting = (GlobalBuildSetting) setting.getValue();
				synchronized (this) {
					serverResourceHolder.updateTotalResource(ResourceHolder.CPU, buildSetting.getCpu());
					serverResourceHolder.updateTotalResource(ResourceHolder.MEMORY, buildSetting.getMemory());
					notifyAll();
				}
			}
		} else if (event.getEntity() instanceof Agent) {
			synchronized (this) {
				Agent agent = (Agent) event.getEntity();
				agentPaused.put(agent.getId(), agent.isPaused());
				notifyAll();
			}
		}
	}
	
	@Transactional
	@Listen
	public void on(EntityRemoved event) {
		if (event.getEntity() instanceof Agent) { 
			synchronized (this) {
				agentPaused.remove(((Agent) event.getEntity()).getId());
			}
		}
	}
	
	@Override
	public void run(Runnable runnable, Map<String, Integer> serverResourceRequirements, TaskLogger logger) {
		logger.log("Waiting for resources...");
		synchronized(this) {
			while (serverResourceHolder.getSpareResources(serverResourceRequirements) == 0) {
				try {
					wait();
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}
			serverResourceHolder.acquireResources(serverResourceRequirements);
		}
		
		try {
			runnable.run();
		} catch (Exception e) {
			throw ExceptionUtils.unchecked(e);
		} finally {
			synchronized (this) {
				serverResourceHolder.releaseResources(serverResourceRequirements);
				notifyAll();
			}
		}	
	}
	
	@Sessional
	protected AgentData getAgentData(Long agentId) {
		return agentManager.load(agentId).getAgentData();
	}

	@Override
	public void run(AgentAwareRunnable runnable, Map<String, Integer> serverResourceRequirements, 
			AgentQuery agentQuery, Map<String, Integer> agentResourceRequirements, TaskLogger logger) {
		Set<Long> agentIds = agentManager.query(agentQuery, 0, Integer.MAX_VALUE)
				.stream().map(it->it.getId()).collect(Collectors.toSet());
		Long agentId = 0L;
		synchronized(this) {
			logger.log("Waiting for resources...");
			String uuid = UUID.randomUUID().toString();
			queryCaches.put(uuid, new QueryCache(agentQuery, agentIds));
			try {
				while (true) {
					if (serverResourceHolder.getSpareResources(serverResourceRequirements) != 0) {
						int maxSpareResources = 0;
						for (Long each: agentIds) {
							ResourceHolder agentResourceHolder = agentResourceHolders.get(each);
							Boolean paused = agentPaused.get(each);
							if (agentResourceHolder != null && paused != null && !paused) {
								int spareResources = agentResourceHolder.getSpareResources(agentResourceRequirements);
								if (spareResources > maxSpareResources) {
									agentId = each;
									maxSpareResources = spareResources;
								}
							}
						}
						if (agentId != 0)
							break;
					} 
					try {
						wait();
					} catch (InterruptedException e) {
						throw new RuntimeException(e);
					}
				}
				agentResourceHolders.get(agentId).acquireResources(agentResourceRequirements);
				serverResourceHolder.acquireResources(serverResourceRequirements);
			} finally {
				queryCaches.remove(uuid);
			}
		}

		try {
			Session agentSession = agentManager.getAgentSession(agentId);
			if (agentSession == null)
				throw new ExplicitException("Agent goes offline");
			runnable.runOn(agentId, agentSession, getAgentData(agentId));
		} catch (Exception e) {
			throw ExceptionUtils.unchecked(e);
		} finally {
			synchronized (this) {
				serverResourceHolder.releaseResources(serverResourceRequirements);
				ResourceHolder agentResourceHolder = agentResourceHolders.get(agentId);
				if(agentResourceHolder != null)
					agentResourceHolder.releaseResources(agentResourceRequirements);
				notifyAll();
			}
		}	
	}

	private static class QueryCache {
		
		AgentQuery query;
		
		Collection<Long> result;
		
		QueryCache(AgentQuery query, Collection<Long> result) {
			this.query = query;
			this.result = result;
		}
		
	}
}
