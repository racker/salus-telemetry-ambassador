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

import com.rackspace.salus.telemetry.entities.AgentHistory;
import com.rackspace.salus.telemetry.repositories.AgentHistoryRepository;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AgentHistoryService {

  private final AgentHistoryRepository agentHistoryRepository;

  public AgentHistoryService(AgentHistoryRepository agentHistoryRepository)  {
    this.agentHistoryRepository = agentHistoryRepository;
  }

  public Optional<AgentHistory> getAgentHistoryForTenant(String tenantId, String envoyId) {
       return agentHistoryRepository.findByTenantIdAndEnvoyId(tenantId, envoyId);
  }

  public Optional<AgentHistory> getAgentHistoryForResource(String tenantId, String resourceId) {
      return agentHistoryRepository.findByTenantIdAndResourceIdAndDisconnectedAtIsNull(tenantId, resourceId);
  }
}
