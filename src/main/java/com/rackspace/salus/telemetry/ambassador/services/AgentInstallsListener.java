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

import com.rackspace.salus.acm.web.client.AgentInstallApi;
import com.rackspace.salus.acm.web.model.AgentReleaseDTO;
import com.rackspace.salus.acm.web.model.BoundAgentInstallDTO;
import com.rackspace.salus.common.messaging.KafkaTopicProperties;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.telemetry.messaging.AgentInstallChangeEvent;
import com.rackspace.salus.telemetry.messaging.OperationType;
import com.rackspace.salus.telemetry.model.AgentType;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AgentInstallsListener implements ConsumerSeekAware {

  private final RetryTemplate retryTemplate;
  private final EnvoyRegistry envoyRegistry;
  private final MonitorBindingService monitorBindingService;
  private final AgentInstallApi agentInstallApi;
  private final KafkaTopicProperties kafkaTopicProperties;
  private final String ourHostName;

  @Autowired
  public AgentInstallsListener(RetryTemplate retryTemplate,
                               EnvoyRegistry envoyRegistry,
                               MonitorBindingService monitorBindingService,
                               AgentInstallApi agentInstallApi,
                               KafkaTopicProperties kafkaTopicProperties,
                               @Value("${localhost.name}") String ourHostName) {
    this.retryTemplate = retryTemplate;
    this.envoyRegistry = envoyRegistry;
    this.monitorBindingService = monitorBindingService;
    this.agentInstallApi = agentInstallApi;
    this.kafkaTopicProperties = kafkaTopicProperties;
    this.ourHostName = ourHostName;
  }

  @SuppressWarnings("unused") // used in SpEL
  public String getTopic() {
    return kafkaTopicProperties.getInstalls();
  }

  @SuppressWarnings("unused") // used in SpEL
  public String getGroupId() {
    return "ambassador-installs-" + ourHostName;
  }

  @KafkaListener(topics = "#{__listener.topic}", groupId = "#{__listener.groupId}")
  public void handleAgentInstallEvent(AgentInstallChangeEvent event) {
    if (!envoyRegistry.containsEnvoyResource(event.getResourceId())) {
      log.trace("Discarded event={} for unregistered Envoy Resource", event);
      return;
    }

    final OperationType op = event.getOp();

    switch (op) {
      case UPSERT:
        log.debug("Processing agent install upsert for event={}", event);
        processAgentInstallUpdate(event);
        break;

      case DELETE:
        log.debug("Processing agent uninstall for event={}", event);
        processAgentInstallDeletion(event);
        break;

      default:
        log.warn("Unsupported operation type in agent install event: {}", event);
        break;
    }
  }

  private void processAgentInstallUpdate(AgentInstallChangeEvent event) {

    final BoundAgentInstallDTO binding =
        retryTemplate.execute(context ->
            agentInstallApi
                .getBindingForResourceAndAgentType(event.getTenantId(), event.getResourceId(),
                    event.getAgentType()
                ));

    log.debug("Retrieved agentInstallBinding={} for processing event={}", binding, event);

    final AgentReleaseDTO agentRelease = binding.getAgentInstall().getAgentRelease();

    final AgentType agentType = agentRelease.getType();
    final String agentVersion = agentRelease.getVersion();

    final TelemetryEdge.EnvoyInstruction instruction = TelemetryEdge.EnvoyInstruction.newBuilder()
        .setInstall(
            TelemetryEdge.EnvoyInstructionInstall.newBuilder()
                .setUrl(agentRelease.getUrl())
                .setExe(agentRelease.getExe())
                .setAgent(TelemetryEdge.Agent.newBuilder()
                    .setType(TelemetryEdge.AgentType.valueOf(agentType.name()))
                    .setVersion(agentVersion)
                    .build())
        )
        .build();

    final String envoyId = envoyRegistry.getEnvoyIdByResource(event.getResourceId());
    if (envoyId != null) {
      if (envoyRegistry.sendInstruction(envoyId, instruction)) {
        final HashMap<AgentType, String> installedVersions = envoyRegistry
            .trackAgentInstall(envoyId, agentType, agentVersion);

        monitorBindingService.processEnvoy(envoyId, installedVersions);
      }
    } else {
      log.warn(
          "Unable to locate envoyId for resourceId={} when processing agent install event",
          event.getResourceId()
      );
    }
  }

  private void processAgentInstallDeletion(AgentInstallChangeEvent event) {
    log.info("Agent uninstall not yet supported by envoy, ignoring event={}", event);
  }

  @Override
  public void onPartitionsAssigned(Map<TopicPartition, Long> assignments,
                                   ConsumerSeekCallback callback) {
    assignments.forEach((tp, currentOffset) -> callback.seekToEnd(tp.topic(), tp.partition()));
  }

  @Override
  public void registerSeekCallback(ConsumerSeekCallback callback) {
    // not needed
  }

  @Override
  public void onIdleContainer(Map<TopicPartition, Long> assignments,
                              ConsumerSeekCallback callback) {
    // not needed
  }

}
