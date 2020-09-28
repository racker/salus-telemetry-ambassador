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

package com.rackspace.salus.telemetry.ambassador.web.controller;

import com.rackspace.salus.telemetry.ambassador.services.AgentHistoryConversionService;
import com.rackspace.salus.telemetry.ambassador.services.AgentHistoryService;
import com.rackspace.salus.telemetry.ambassador.web.model.AgentHistoryOutput;
import com.rackspace.salus.telemetry.entities.AgentHistory;
import com.rackspace.salus.telemetry.model.NotFoundException;
import io.swagger.annotations.ApiOperation;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api")
public class AgentHistoryController {

  private final AgentHistoryService agentHistoryService;
  private final AgentHistoryConversionService agentHistoryConversionService;

  public AgentHistoryController(AgentHistoryService agentHistoryService,
      AgentHistoryConversionService agentHistoryConversionService)  {
    this.agentHistoryService = agentHistoryService;
    this.agentHistoryConversionService = agentHistoryConversionService;
  }

  @GetMapping("/tenant/{tenantId}/agent-history")
  @ApiOperation(value = "Gets Agent History for Tenant")
  public AgentHistoryOutput getAgentHistoryForTenant(
      @PathVariable String tenantId,
      @RequestParam(name = "envoyId", required = false) String envoyId,
      @RequestParam(name = "resourceId", required = false) String resourceId)
      throws NotFoundException {
    Optional<AgentHistory> agentHistoryOptional = Optional.empty();
    if(!StringUtils.isEmpty(envoyId))  {
      agentHistoryOptional = agentHistoryService.getAgentHistoryForTenant(tenantId, envoyId);
    }
    if(!StringUtils.isEmpty(resourceId))  {
      agentHistoryOptional = agentHistoryService.getAgentHistoryForResource(tenantId, resourceId);
    }

    AgentHistory agentHistory = agentHistoryOptional.orElseThrow(() ->
        new NotFoundException(String.format("No Agent History found for tenant %s", tenantId)));

    return agentHistoryConversionService.convertToOutput(agentHistory);
  }
}
