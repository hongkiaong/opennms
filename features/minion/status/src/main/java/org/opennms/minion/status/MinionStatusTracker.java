/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2018 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2018 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.minion.status;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.opennms.core.criteria.Criteria;
import org.opennms.core.criteria.CriteriaBuilder;
import org.opennms.core.logging.Logging;
import org.opennms.core.logging.Logging.MDCCloseable;
import org.opennms.netmgt.dao.api.MinionDao;
import org.opennms.netmgt.dao.api.NodeDao;
import org.opennms.netmgt.dao.api.OutageDao;
import org.opennms.netmgt.dao.api.ServiceTypeDao;
import org.opennms.netmgt.events.api.EventConstants;
import org.opennms.netmgt.events.api.annotations.EventHandler;
import org.opennms.netmgt.events.api.annotations.EventListener;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.model.OnmsOutage;
import org.opennms.netmgt.model.OnmsServiceType;
import org.opennms.netmgt.model.ServiceSelector;
import org.opennms.netmgt.model.minion.OnmsMinion;
import org.opennms.netmgt.xml.event.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@EventListener(name="minionStatusTracker", logPrefix=MinionStatusTracker.LOG_PREFIX)
public class MinionStatusTracker implements InitializingBean {
    private static final Logger LOG = LoggerFactory.getLogger(MinionStatusTracker.class);

    ScheduledExecutorService m_executor = Executors.newSingleThreadScheduledExecutor();

    public static final String LOG_PREFIX = "minion";

    static final String MINION_HEARTBEAT = "Minion-Heartbeat";
    static final String MINION_RPC = "Minion-RPC";

    @Autowired
    NodeDao m_nodeDao;

    @Autowired
    MinionDao m_minionDao;

    @Autowired
    ServiceTypeDao m_serviceTypeDao;

    @Autowired
    OutageDao m_outageDao;

    private boolean m_initialized = false;

    // by default, minions are updated every 30 seconds
    private long m_period = TimeUnit.SECONDS.toMillis(30);

    Map<Integer,OnmsMinion> m_minionNodes = new ConcurrentHashMap<>();
    Map<String,OnmsMinion> m_minions = new ConcurrentHashMap<>();
    Map<String,AggregateMinionStatus> m_state = new ConcurrentHashMap<>();

    @Override
    public void afterPropertiesSet() throws Exception {
        try (MDCCloseable mdc = Logging.withPrefixCloseable(LOG_PREFIX)) {
            LOG.info("Starting minion status tracker.");
            Assert.notNull(m_nodeDao);
            Assert.notNull(m_minionDao);
            Assert.notNull(m_serviceTypeDao);
            Assert.notNull(m_outageDao);
            final Runnable command = new Runnable() {
                @Override public void run() {
                    try {
                        refresh();
                    } catch (final Throwable t) {
                        LOG.warn("Failed to refresh minion status from the database.", t);
                    }
                }
            };
            //m_executor.scheduleAtFixedRate(command, 0, m_period, TimeUnit.MILLISECONDS);
            m_executor.schedule(command, m_period, TimeUnit.MILLISECONDS);
        }
    }

    public long getPeriod() {
        return m_period;
    }

    public void setPeriod(final long period) {
        m_period = period;
    }

    @EventHandler(uei=EventConstants.MONITORING_SYSTEM_ADDED_UEI)
    @Transactional
    public void onMonitoringSystemAdded(final Event e) {
        try (MDCCloseable mdc = Logging.withPrefixCloseable(LOG_PREFIX)) {
            LOG.debug("Monitoring system added: {}", e);
            final String id = e.getParm(EventConstants.PARAM_MONITORING_SYSTEM_ID).toString();
            if (id != null) {
                m_state.put(id, AggregateMinionStatus.up());
            }
        }
    }

    @EventHandler(uei=EventConstants.MONITORING_SYSTEM_DELETED_UEI)
    @Transactional
    public void onMonitoringSystemDeleted(final Event e) {
        try (MDCCloseable mdc = Logging.withPrefixCloseable(LOG_PREFIX)) {
            LOG.debug("Monitoring system removed: {}", e);
            final String id = e.getParm(EventConstants.PARAM_MONITORING_SYSTEM_ID).toString();
            if (id != null) {
                final OnmsMinion minion = m_minions.get(id);
                m_minions.remove(id);
                m_state.remove(id);
                if (minion != null) {
                    final Iterator<Entry<Integer,OnmsMinion>> it = m_minionNodes.entrySet().iterator();
                    while (it.hasNext()) {
                        final Entry<Integer,OnmsMinion> entry = it.next();
                        if (entry.getValue().getId().equals(minion.getId())) {
                            it.remove();
                            break;
                        };
                    }
                }
            }
        }
    }

    @EventHandler(uei=EventConstants.NODE_GAINED_SERVICE_EVENT_UEI)
    @Transactional
    public void onNodeGainedService(final Event e) {
        if (!MINION_HEARTBEAT.equals(e.getService()) && !MINION_RPC.equals(e.getService())) {
            return;
        }

        try (MDCCloseable mdc = Logging.withPrefixCloseable(LOG_PREFIX)) {
            assertHasNodeId(e);

            final Integer nodeId = e.getNodeid().intValue();
            final OnmsMinion minion = getMinionForNodeId(nodeId);
            if (minion == null) {
                LOG.debug("No minion found for node ID {}", nodeId);
                return;
            }

            LOG.debug("Node gained a Minion service: {}", e);

            final String minionId = minion.getId();
            AggregateMinionStatus state = m_state.get(minionId);
            if (state == null) {
                LOG.debug("Found new Minion node: {}", minion);
                state = "down".equals(minion.getStatus())? AggregateMinionStatus.down() : AggregateMinionStatus.up();
            }

            if (MINION_HEARTBEAT.equals(e.getService())) {
                state = state.heartbeatUp(e.getTime());
            } else if (MINION_RPC.equals(e.getService())) {
                state = state.rpcUp(e.getTime());
            }
            updateState(minion, state);
        }
    }

    @EventHandler(uei=EventConstants.NODE_LOST_SERVICE_EVENT_UEI)
    @Transactional
    public void onNodeLostService(final Event e) {
        if (!MINION_HEARTBEAT.equals(e.getService()) && !MINION_RPC.equals(e.getService())) {
            return;
        }

        try (MDCCloseable mdc = Logging.withPrefixCloseable(LOG_PREFIX)) {
            assertHasNodeId(e);

            final Integer nodeId = e.getNodeid().intValue();
            final OnmsMinion minion = getMinionForNodeId(nodeId);
            if (minion == null) {
                LOG.debug("No minion found for node ID {}", nodeId);
                return;
            }

            LOG.debug("Node lost a Minion service: {}", e);

            final String minionId = minion.getId();
            AggregateMinionStatus state = m_state.get(minionId);
            if (state == null) {
                LOG.debug("Found new Minion node: {}", minion);
                state = "down".equals(minion.getStatus())? AggregateMinionStatus.down() : AggregateMinionStatus.up();
            }

            if (MINION_HEARTBEAT.equals(e.getService())) {
                state = state.heartbeatDown(e.getTime());
            } else if (MINION_RPC.equals(e.getService())) {
                state = state.rpcDown(e.getTime());
            }
            updateState(minion, state);
        }
    }

    @EventHandler(uei=EventConstants.NODE_DELETED_EVENT_UEI)
    @Transactional
    public void onNodeDeleted(final Event e) {
        try (MDCCloseable mdc = Logging.withPrefixCloseable(LOG_PREFIX)) {
            assertHasNodeId(e);
            final Integer nodeId = e.getNodeid().intValue();
            OnmsMinion minion = getMinionForNodeId(nodeId);
            m_minionNodes.remove(nodeId);
            if (minion != null) {
                updateState(minion, AggregateMinionStatus.down());
                m_minions.remove(minion.getId());
                m_state.remove(minion.getId());
            }
        }
    }

    @EventHandler(ueis= {
            EventConstants.OUTAGE_CREATED_EVENT_UEI,
            EventConstants.OUTAGE_RESOLVED_EVENT_UEI
    })
    @Transactional
    public void onOutageEvent(final Event e) {
        try (MDCCloseable mdc = Logging.withPrefixCloseable(LOG_PREFIX)) {
            LOG.debug("Outage event received: {}", e);
            if (!MINION_HEARTBEAT.equals(e.getService()) && !MINION_RPC.equals(e.getService())) {
                return;
            }

            //LOG.debug("Minion outage event received: {}", e);

            assertHasNodeId(e);

            final OnmsMinion minion = getMinionForNodeId(e.getNodeid().intValue());
            final String minionId = minion.getId();
            final String minionLabel = minion.getLabel();

            AggregateMinionStatus status = m_state.get(minionId);
            if (status == null) {
                status = AggregateMinionStatus.down();
            }

            final String uei = e.getUei();
            if (MINION_HEARTBEAT.equalsIgnoreCase(e.getService())) {
                if (EventConstants.OUTAGE_CREATED_EVENT_UEI.equals(uei)) {
                    status = status.heartbeatDown(e.getTime());
                } else if (EventConstants.OUTAGE_RESOLVED_EVENT_UEI.equals(uei)) {
                    status = status.heartbeatUp(e.getTime());
                }
                final MinionStatus heartbeatStatus = status.getHeartbeatStatus();
                LOG.debug("{} heartbeat is {} as of {}", minionLabel, minionId, heartbeatStatus.getState(), heartbeatStatus.lastSeen());
            } else if (MINION_RPC.equalsIgnoreCase(e.getService())) {
                if (EventConstants.OUTAGE_CREATED_EVENT_UEI.equals(uei)) {
                    status = status.rpcDown(e.getTime());
                } else if (EventConstants.OUTAGE_RESOLVED_EVENT_UEI.equals(uei)) {
                    status = status.rpcUp(e.getTime());
                }
                final MinionStatus rpcStatus = status.getRpcStatus();
                LOG.debug("{} RPC is {} as of {}", minionLabel, minionId, rpcStatus.getState(), rpcStatus.lastSeen());
            }

            updateState(minion, status);
        }
    }

    @Transactional
    public void refresh() {
        try (MDCCloseable mdc = Logging.withPrefixCloseable(LOG_PREFIX)) {
            LOG.info("Refreshing minion status from the outages database.");

            final Map<String,OnmsMinion> minions = new ConcurrentHashMap<>();
            final Map<Integer,OnmsMinion> minionNodes = new ConcurrentHashMap<>();
            final Map<String,AggregateMinionStatus> state = new ConcurrentHashMap<>();

            final List<OnmsMinion> dbMinions = m_minionDao.findAll();

            if (dbMinions.size() == 0) {
                LOG.info("No minions found in the database.  Skipping processing.");
                return;
            }

            // populate the foreignId -> minion map
            LOG.debug("Populating minion state from the database: {}", dbMinions.stream().map(OnmsMinion::getId).collect(Collectors.toList()));
            final AggregateMinionStatus upStatus = AggregateMinionStatus.up();
            final AggregateMinionStatus downStatus = AggregateMinionStatus.down();
            dbMinions.forEach(minion -> {
                final String minionId = minion.getId();
                minions.put(minionId, minion);
                if ("down".equals(minion.getStatus())) {
                    state.put(minionId, downStatus);
                } else {
                    state.put(minionId, upStatus);
                }
            });

            // populate the nodeId -> minion map
            final Criteria c = new CriteriaBuilder(OnmsNode.class)
                    .in("foreignId", minions.keySet())
                    .distinct()
                    .toCriteria();
            final List<OnmsNode> nodes = m_nodeDao.findMatching(c);
            LOG.debug("Mapping {} node IDs to minions: {}", nodes.size(), nodes.stream().map(OnmsNode::getId).collect(Collectors.toList()));
            nodes.forEach(node -> {
                final OnmsMinion m = minions.get(node.getForeignId());
                if (m.getLocation().equals(node.getLocation().getLocationName())) {
                    minionNodes.put(node.getId(), m);
                }
            });

            final OnmsServiceType heartbeatService = m_serviceTypeDao.findByName(MINION_HEARTBEAT);
            if (heartbeatService == null) {
                LOG.warn("No " + MINION_HEARTBEAT + " service found.");
                return;
            }
            final Integer heartbeatId = heartbeatService.getId();

            final OnmsServiceType rpcService = m_serviceTypeDao.findByName(MINION_RPC);
            if (rpcService == null) {
                LOG.warn("No " + MINION_RPC + " service found.");
                return;
            }
            final Integer rpcId = rpcService.getId();

            // populate the foreignId -> state map
            /* SELECT *
             * FROM  outages o
             * INNER JOIN
             * (
             *   SELECT max(outageid) AS newId, ifserviceid AS newIfserviceid
             *   FROM outages newO
             *   GROUP BY newIfserviceid
             * ) AS newest
             *   ON outageid = newId
             * WHERE newId IS NOT NULL
             */
            final Collection<OnmsOutage> outages = m_outageDao
                    .matchingOutages(new ServiceSelector("IPADDR != '0.0.0.0'", Arrays.asList(MINION_HEARTBEAT, MINION_RPC)));

            // keep state for any minions without explicit outages, if there is any
            final List<String> minionsWithOutages = outages.stream().map(OnmsOutage::getForeignId).distinct().collect(Collectors.toList());
            final List<String> minionsWithoutOutages = state.keySet().stream().filter(id -> !minionsWithOutages.contains(id)).collect(Collectors.toList());
            LOG.debug("Attempting to preserve state for minions without explicit outage records: {}", minionsWithoutOutages);
            minionsWithoutOutages.forEach(minionId -> {
                if (m_state.containsKey(minionId)) {
                    state.put(minionId, m_state.get(minionId));
                }
            });

            if (outages != null && outages.size() > 0) {
                LOG.debug("Processing {} outage records.", outages.size());
                outages.stream().sorted(Comparator.comparing(OnmsOutage::getForeignId).thenComparing(OnmsOutage::getIfLostService).thenComparing(OnmsOutage::getIfRegainedService).reversed()).forEach(outage -> {
                    final String foreignId = outage.getForeignId();
                    final String minionLabel = minions.get(foreignId).getLabel();
                    final AggregateMinionStatus status = state.get(foreignId);
                    if (outage.getIfRegainedService() != null) {
                        if (heartbeatId == outage.getServiceId()) {
                            state.put(foreignId, status.heartbeatUp(outage.getIfRegainedService()));
                        } else if (rpcId == outage.getServiceId()) {
                            state.put(foreignId, status.rpcUp(outage.getIfRegainedService()));
                        } else {
                            LOG.warn("Unhandled 'up' outage record: {}", outage);
                        }
                        final MinionStatus heartbeatStatus = status.getHeartbeatStatus();
                        if (!m_initialized) {
                            LOG.debug("{} heartbeat is {} as of {}", minionLabel, foreignId, heartbeatStatus.getState(), heartbeatStatus.lastSeen());
                        }
                    } else {
                        if (heartbeatId == outage.getServiceId()) {
                            state.put(foreignId, status.heartbeatDown(outage.getIfRegainedService()));
                        } else if (rpcId == outage.getServiceId()) {
                            state.put(foreignId, status.rpcDown(outage.getIfRegainedService()));
                        } else {
                            LOG.warn("Unhandled 'down' outage record: {}", outage);
                        }
                        final MinionStatus rpcStatus = status.getRpcStatus();
                        if (!m_initialized) {
                            LOG.debug("{} RPC is {} as of {}", minionLabel, foreignId, rpcStatus.getState(), rpcStatus.lastSeen());
                        }
                    }
                });
            } else {
                LOG.debug("No minion-related outages were found.");
            }

            LOG.debug("Persisting states to the database.");
            minions.values().forEach(minion -> updateMinion(minion, state.get(minion.getId())));

            m_state = state;
            m_minions = minions;
            m_minionNodes = minionNodes;
            m_initialized = true;

            LOG.info("Minion status updated from the outages database.  Next refresh in {} milliseconds.", m_period);
        }
    }

    public Collection<OnmsMinion> getMinions() {
        return m_minions.values();
    }

    public MinionStatus getStatus(final String foreignId) {
        return m_state.get(foreignId);
    }

    public MinionStatus getStatus(final OnmsMinion minion) {
        return m_state.get(minion.getId());
    }

    private void updateState(final OnmsMinion minion, final AggregateMinionStatus status) {
        final AggregateMinionStatus previousStatus = m_state.get(minion.getId());
        m_state.put(minion.getId(), status);
        updateMinion(minion, status);
        if (previousStatus == null || previousStatus.compareTo(status) != 0) {
            LOG.debug("Minion status has changed: Heartbeat: {} -> {}, RPC: {} -> {}", (previousStatus == null? "Unknown" : previousStatus.getHeartbeatStatus()), status.getHeartbeatStatus(), (previousStatus == null? "Unknown" : previousStatus.getRpcStatus()), status.getRpcStatus());
        }
    }

    private void updateMinion(final OnmsMinion minion, final AggregateMinionStatus status) {
        minion.setStatus(status.isUp(2 * m_period)? "up":"down");
        m_minionDao.saveOrUpdate(minion);
    }

    private OnmsMinion getMinionForNodeId(final Integer nodeId) {
        if (m_minionNodes.containsKey(nodeId)) {
            return m_minionNodes.get(nodeId);
        }
        final OnmsNode node = m_nodeDao.get(nodeId);
        if (node == null) {
            final IllegalStateException ex = new IllegalStateException("Unable to retrieve minion. The node (ID: " + nodeId + ") does not exist!");
            LOG.warn(ex.getMessage());
            throw ex;
        }
        final String minionId = node.getForeignId();
        final OnmsMinion minion = m_minionDao.findById(minionId);
        m_minionNodes.put(nodeId, minion);
        m_minions.put(minionId, minion);
        return minion;
    }

    private void assertHasNodeId(final Event e) {
        if (e.getNodeid() == null || e.getNodeid() == 0) {
            final IllegalStateException ex = new IllegalStateException("Received a nodeGainedService event, but there is no node ID!");
            LOG.warn(ex.getMessage() + " {}", e, ex);
            throw ex;
        }
    }
}
