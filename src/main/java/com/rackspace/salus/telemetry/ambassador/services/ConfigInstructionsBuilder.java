/*
 * Copyright 2019 Rackspace US, Inc.
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

import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.services.TelemetryEdge.ConfigurationOp.Type;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstruction;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstructionConfigure;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstructionConfigure.Builder;
import com.rackspace.salus.telemetry.messaging.OperationType;
import com.rackspace.salus.telemetry.model.AgentType;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This builder helps to organize a list of raw agent config operations and groups them
 * up into {@link EnvoyInstruction}s, one per agent type.
 */
public class ConfigInstructionsBuilder {

  private HashMap<AgentType, EnvoyInstructionConfigure.Builder> buildersByAgentType = new LinkedHashMap<>();

  public List<EnvoyInstruction> build() {
    return buildersByAgentType.values().stream()
        .map(configureBuilder ->
            EnvoyInstruction.newBuilder()
                .setConfigure(configureBuilder)
                .build())
        .collect(Collectors.toList());
  }

  public ConfigInstructionsBuilder add(AgentType agentType,
                                       String agentConfigContent,
                                       OperationType operationType,
                                       String monitorId) {
    final Builder builder = buildersByAgentType.computeIfAbsent(
        agentType,
        givenAgentType ->
            EnvoyInstructionConfigure.newBuilder()
                .setAgentType(TelemetryEdge.AgentType.valueOf(givenAgentType.name()))
    );

    builder.addOperationsBuilder()
        .setId(monitorId)
        .setType(convertOpType(operationType))
        .setContent(agentConfigContent);

    return this;
  }

  private EnvoyInstruction buildConfigInstruction(AgentType agentType,
                                                  String agentConfigContent,
                                                  OperationType operationType,
                                                  String monitorId) {
    return EnvoyInstruction.newBuilder()
        .setConfigure(
            TelemetryEdge.EnvoyInstructionConfigure.newBuilder()
                .setAgentType(TelemetryEdge.AgentType.valueOf(agentType.name()))
                .addOperations(
                    TelemetryEdge.ConfigurationOp.newBuilder()
                        .setType(convertOpType(operationType))
                        .setId(monitorId)
                        .setContent(agentConfigContent)
                )
        )
        .build();
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
