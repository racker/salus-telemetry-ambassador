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

import com.rackspace.salus.monitor_management.web.client.MonitorApi;
import com.rackspace.salus.monitor_management.web.model.BoundMonitorDTO;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstruction;
import com.rackspace.salus.telemetry.messaging.OperationType;
import com.rackspace.salus.telemetry.model.AgentType;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

@Service
@Slf4j
public class MonitorBindingService {

  private final MonitorApi monitorApi;
  private final EnvoyRegistry envoyRegistry;

  @Autowired
  public MonitorBindingService(MonitorApi monitorApi, EnvoyRegistry envoyRegistry) {
    this.monitorApi = monitorApi;
    this.envoyRegistry = envoyRegistry;
  }

  public void processEnvoy(String envoyId) {
    processEnvoy(envoyId, envoyRegistry.getInstalledAgentVersions(envoyId));
  }

  public void processEnvoy(String envoyId,
                           Map<AgentType, String> installedAgentVersions) {

    if (CollectionUtils.isEmpty(installedAgentVersions)) {
      log.info("Skipping monitoring bindings process before agent installs for envoyId={}", envoyId);
      // ...the envoy will get re-processed during agent install event. which gets produced by
      // by ACM due to the Envoy (re)attachment
      return;
    }

    final List<BoundMonitorDTO> boundMonitors = monitorApi.getBoundMonitors(envoyId,
        installedAgentVersions
    );

    // reconcile all bound monitors for this envoy and determine what operation types to send

    final Map<OperationType, List<BoundMonitorDTO>> changes = envoyRegistry.applyBoundMonitors(envoyId, boundMonitors);
    log.debug("Applied boundMonitors and computed changes={}", changes);

    // transform bound monitor changes into instructions

    final ConfigInstructionsBuilder instructionsBuilder = new ConfigInstructionsBuilder();
    for (Entry<OperationType, List<BoundMonitorDTO>> entry : changes.entrySet()) {
      for (BoundMonitorDTO boundMonitor : entry.getValue()) {
        instructionsBuilder.add(
            boundMonitor,
            entry.getKey()
        );
      }
    }

    final List<EnvoyInstruction> instructions = instructionsBuilder.build();

    // ...and send them down to the envoy

    for (EnvoyInstruction instruction : instructions) {
      envoyRegistry.sendInstruction(envoyId, instruction);
    }

  }
}
