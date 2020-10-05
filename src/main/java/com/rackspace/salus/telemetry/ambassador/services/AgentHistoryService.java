/*
 * Copyright 2020 Rackspace US, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rackspace.salus.telemetry.ambassador.services;

import com.rackspace.salus.common.config.MetricNames;
import com.rackspace.salus.common.config.MetricTags;
import com.rackspace.salus.services.TelemetryEdge.EnvoySummary;
import com.rackspace.salus.telemetry.entities.AgentHistory;
import com.rackspace.salus.telemetry.repositories.AgentHistoryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AgentHistoryService {

  MeterRegistry meterRegistry;

  // metrics counters
  private final Counter.Builder agentHistoryError;
  private final AgentHistoryRepository agentHistoryRepository;

  public AgentHistoryService(AgentHistoryRepository agentHistoryRepository,
      MeterRegistry meterRegistry)  {
    this.agentHistoryRepository = agentHistoryRepository;
    this.meterRegistry = meterRegistry;
    agentHistoryError = Counter.builder(MetricNames.SERVICE_OPERATION_FAILED).tag(MetricTags.SERVICE_METRIC_TAG,"AgentHistoryService");
  }

  public Optional<AgentHistory> getAgentHistoryForTenantAndEnvoyId(String tenantId, String envoyId) {
       return agentHistoryRepository.findByTenantIdAndEnvoyId(tenantId, envoyId);
  }

  public Page<AgentHistory> getAgentHistoryForTenantAndResource(String tenantId, String resourceId, Pageable pageable) {
      return agentHistoryRepository.findByTenantIdAndResourceId(tenantId, resourceId, pageable);
  }

  public Page<AgentHistory> getAgentHistoryForTenant(String tenantId, Pageable pageable) {
    return agentHistoryRepository.findByTenantId(tenantId, pageable);
  }

  public Optional<AgentHistory> getAgentHistoryForIdAndTenantId(UUID agentHistoryId, String tenantId) {
    return agentHistoryRepository.findByIdAndTenantId(agentHistoryId, tenantId);
  }

  public AgentHistory addAgentHistory(EnvoySummary request,
      SocketAddress remoteAddr, String envoyId, String tenantId,
      Instant attachStartTime) {
    final String resourceId = request.getResourceId();
    final String zoneId = request.getZone();

    AgentHistory agentHistory = new AgentHistory()
        .setConnectedAt(attachStartTime)
        .setEnvoyId(envoyId)
        .setResourceId(resourceId)
        .setTenantId(tenantId)
        .setZoneId(zoneId)
        .setRemoteIp(remoteAddr.toString());
    return agentHistoryRepository.save(agentHistory);
  }

  public Optional<AgentHistory> addEnvoyConnectionClosedTime(String tenantId, String envoyId)  {
    Optional<AgentHistory> agent = agentHistoryRepository.findByTenantIdAndEnvoyId(tenantId, envoyId);
    if(agent.isPresent()) {
      AgentHistory agentHistory = agent.get();
      final Instant connectionClosedTime = Instant.now();
      agentHistory.setDisconnectedAt(connectionClosedTime);
      return Optional.of(agentHistoryRepository.save(agentHistory));
    } else  {
      log.warn("Unable to find connection history with tenantId={} and envoyId={} ",tenantId, envoyId);
      agentHistoryError.tags(MetricTags.OPERATION_METRIC_TAG, "addAgentHistory")
          .register(meterRegistry).increment();
      return Optional.empty();
    }
  }
}
