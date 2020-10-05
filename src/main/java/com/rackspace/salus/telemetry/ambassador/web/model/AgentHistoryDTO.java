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

package com.rackspace.salus.telemetry.ambassador.web.model;

import com.fasterxml.jackson.annotation.JsonView;
import com.rackspace.salus.common.web.View;
import com.rackspace.salus.telemetry.entities.AgentHistory;

import java.time.format.DateTimeFormatter;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AgentHistoryDTO {

  private UUID id;
  private String connectedAt;
  private String disconnectedAt;

  @JsonView(View.Admin.class)
  private String tenantId;

  private String resourceId;
  private String envoyId;
  private String remoteIp;
  private String zoneId;

  public AgentHistoryDTO(AgentHistory agentHistory) {
    this.id = agentHistory.getId();
    this.tenantId = agentHistory.getTenantId();
    this.resourceId = agentHistory.getResourceId();
    this.envoyId = agentHistory.getEnvoyId();
    this.remoteIp = agentHistory.getRemoteIp();
    this.zoneId = agentHistory.getZoneId();
    this.connectedAt = agentHistory.getConnectedAt() == null ? null : DateTimeFormatter.ISO_INSTANT.format(agentHistory.getConnectedAt());
    this.disconnectedAt = agentHistory.getDisconnectedAt() == null ? null : DateTimeFormatter.ISO_INSTANT.format(agentHistory.getDisconnectedAt());
  }
}
