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

import com.rackspace.salus.monitor_management.web.model.BoundMonitorDTO;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.services.TelemetryEdge.ConfigurationOp;
import com.rackspace.salus.services.TelemetryEdge.ConfigurationOp.Type;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstruction;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstructionConfigure;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstructionConfigure.Builder;
import com.rackspace.salus.telemetry.messaging.OperationType;
import com.rackspace.salus.telemetry.model.AgentType;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.springframework.util.StringUtils;

/**
 * This builder helps to organize a list of raw agent config operations and groups them
 * up into {@link EnvoyInstruction}s, one per agent type.
 */
public class ConfigInstructionsBuilder {

  public static final String LABEL_TARGET_TENANT = "target_tenant";
  public static final String LABEL_RESOURCE = "resource_id";
  public static final String LABEL_MONITOR_ID = "monitor_id";
  public static final String LABEL_MONITOR_TYPE = "monitor_type";
  public static final String LABEL_MONITOR_SCOPE = "monitor_selector_scope";
  public static final String LABEL_ZONE = "monitoring_zone_id";
  public static final String LABEL_ENVOY_ID = "envoy_id";

  public static final List<String> SYSTEM_METADATA_KEYS = List.of(
      LABEL_TARGET_TENANT,
      LABEL_RESOURCE,
      LABEL_MONITOR_ID,
      LABEL_MONITOR_TYPE,
      LABEL_MONITOR_SCOPE,
      LABEL_ZONE,
      LABEL_ENVOY_ID);

  private HashMap<AgentType, EnvoyInstructionConfigure.Builder> buildersByAgentType = new LinkedHashMap<>();

  public static String buildConfiguredMonitorId(BoundMonitorDTO boundMonitor) {
    return String.join("_",
        boundMonitor.getTenantId(),
        boundMonitor.getMonitorId().toString(),
        boundMonitor.getResourceId());
  }

  public List<EnvoyInstruction> build() {
    return buildersByAgentType.values().stream()
        .map(configureBuilder ->
            EnvoyInstruction.newBuilder()
                .setConfigure(configureBuilder)
                .build())
        .collect(Collectors.toList());
  }

  public ConfigInstructionsBuilder add(
      BoundMonitorDTO boundMonitor,
      OperationType operationType) {
    final Builder builder = buildersByAgentType.computeIfAbsent(
        boundMonitor.getAgentType(),
        givenAgentType ->
            EnvoyInstructionConfigure.newBuilder()
                .setAgentType(TelemetryEdge.AgentType.valueOf(givenAgentType.name()))
    );

    final ConfigurationOp.Builder opBuilder = builder.addOperationsBuilder()
        .setId(buildConfiguredMonitorId(boundMonitor))
        .setType(convertOpType(operationType))
        .setContent(boundMonitor.getRenderedContent())
        .setInterval(convertIntervalToSeconds(boundMonitor.getInterval()));

    if (!operationType.equals(OperationType.DELETE)) {
      opBuilder.putExtraLabels(LABEL_MONITOR_ID, boundMonitor.getMonitorId().toString());
      opBuilder.putExtraLabels(LABEL_MONITOR_TYPE, boundMonitor.getMonitorType().toString());
      opBuilder.putExtraLabels(LABEL_MONITOR_SCOPE, boundMonitor.getSelectorScope().toString());

      if (isRemoteMonitor(boundMonitor)) {
        opBuilder.putExtraLabels(LABEL_TARGET_TENANT, boundMonitor.getTenantId());
        opBuilder.putExtraLabels(LABEL_RESOURCE, boundMonitor.getResourceId());
        opBuilder.putExtraLabels(LABEL_ZONE, boundMonitor.getZoneName());
      }
    }

    return this;
  }

  static long convertIntervalToSeconds(@Nullable Duration interval) {
    return interval != null ? interval.toSeconds() : 0;
  }

  private boolean isRemoteMonitor(BoundMonitorDTO boundMonitor) {
    return StringUtils.hasText(boundMonitor.getZoneName());
  }


  private Type convertOpType(OperationType operationType) {
    switch (operationType) {
      case CREATE:
        return Type.CREATE;
      case UPDATE:
        return Type.MODIFY;
      case DELETE:
        return Type.REMOVE;
      default:
        throw new IllegalArgumentException("Unknown operationType: " + operationType);
    }
  }

}
