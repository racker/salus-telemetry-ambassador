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

import com.rackspace.salus.telemetry.ambassador.services.AgentHistoryService;
import com.rackspace.salus.telemetry.ambassador.web.model.AgentHistoryDTO;
import com.rackspace.salus.telemetry.model.NotFoundException;
import com.rackspace.salus.telemetry.model.PagedContent;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
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

  public AgentHistoryController(AgentHistoryService agentHistoryService)  {
    this.agentHistoryService = agentHistoryService;
  }

  @GetMapping("/tenant/{tenantId}/agent-history")
  @ApiOperation(value = "Gets Agent History for Tenant")
  public PagedContent<AgentHistoryDTO> getAgentHistoryForTenant(
      @PathVariable String tenantId,
      @RequestParam(name = "envoyId", required = false) String envoyId,
      @RequestParam(name = "resourceId", required = false) String resourceId,
      Pageable pageable)
      throws NotFoundException {
    if(!StringUtils.isEmpty(envoyId) && !StringUtils.isEmpty(resourceId)) {
      throw new IllegalArgumentException("EnvoyId and ResourceId both cannot be not-null");
    } else if(!StringUtils.isEmpty(envoyId))  {
      return PagedContent.ofSingleton(
          new AgentHistoryDTO(agentHistoryService.getAgentHistoryForTenantAndEnvoyId(tenantId, envoyId)
          .orElseThrow(() ->
              new NotFoundException(
                  String.format("No Agent History found for tenant %s and envoy %s ", tenantId, envoyId)))));
    } else if(!StringUtils.isEmpty(resourceId))  {
      return PagedContent.fromPage(
          agentHistoryService.getAgentHistoryForTenantAndResource(tenantId, resourceId, pageable))
          .map(AgentHistoryDTO::new);
    } else  {
      return PagedContent.fromPage(
          agentHistoryService.getAgentHistoryForTenant(tenantId, pageable))
          .map(AgentHistoryDTO::new);
    }
  }
}
