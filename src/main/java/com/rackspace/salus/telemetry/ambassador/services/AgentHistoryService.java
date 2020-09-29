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

import com.rackspace.salus.services.TelemetryEdge.EnvoySummary;
import com.rackspace.salus.telemetry.entities.AgentHistory;
import com.rackspace.salus.telemetry.repositories.AgentHistoryRepository;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AgentHistoryService {

  private final AgentHistoryRepository agentHistoryRepository;

  public AgentHistoryService(AgentHistoryRepository agentHistoryRepository)  {
    this.agentHistoryRepository = agentHistoryRepository;
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

  public void addAgentHistory(EnvoySummary request, Instant attachStartTime) {
    final SocketAddress remoteAddr = GrpcContextDetails.getCallerRemoteAddress();
    final String envoyId = GrpcContextDetails.getCallerEnvoyId();
    final String tenantId = GrpcContextDetails.getCallerTenantId();
    final String resourceId = request.getResourceId();
    final String zoneId = request.getZone();

    AgentHistory agentHistory = new AgentHistory()
        .setConnectedAt(attachStartTime)
        .setEnvoyId(envoyId)
        .setResourceId(resourceId)
        .setTenantId(tenantId)
        .setZoneId(zoneId)
        .setRemoteIp(remoteAddr.toString());
    agentHistoryRepository.save(agentHistory);
  }

  public void addEnvoyConnectionClosedTime(String tenantId, String envoyId)  {
    Optional<AgentHistory> agent = agentHistoryRepository.findByTenantIdAndEnvoyId(tenantId, envoyId);
    if(!agent.isEmpty()) {
      AgentHistory agentHistory = agent.get();
      final Instant connectionClosedTime = Instant.now();
      agentHistory.setDisconnectedAt(connectionClosedTime);
      agentHistoryRepository.save(agentHistory);
    }
  }
}
