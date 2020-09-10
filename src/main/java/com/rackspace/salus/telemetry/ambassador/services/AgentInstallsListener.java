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

import com.rackspace.salus.common.messaging.KafkaTopicProperties;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.telemetry.entities.AgentRelease;
import com.rackspace.salus.telemetry.entities.BoundAgentInstall;
import com.rackspace.salus.telemetry.messaging.AgentInstallChangeEvent;
import com.rackspace.salus.telemetry.messaging.OperationType;
import com.rackspace.salus.telemetry.model.AgentType;
import com.rackspace.salus.telemetry.model.NotFoundException;
import com.rackspace.salus.telemetry.repositories.BoundAgentInstallRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
  private final KafkaTopicProperties kafkaTopicProperties;
  private final String ourHostName;
  private final Counter eventsConsumed;
  private final Counter installInstructionFailed;
  private final BoundAgentInstallRepository boundAgentInstallRepository;

  @Autowired
  public AgentInstallsListener(RetryTemplate retryTemplate,
                               EnvoyRegistry envoyRegistry,
                               MonitorBindingService monitorBindingService,
                               MeterRegistry meterRegistry,
                               KafkaTopicProperties kafkaTopicProperties,
                               BoundAgentInstallRepository boundAgentInstallRepository,
                               @Value("${localhost.name}") String ourHostName) {
    this.retryTemplate = retryTemplate;
    this.envoyRegistry = envoyRegistry;
    this.monitorBindingService = monitorBindingService;
    this.kafkaTopicProperties = kafkaTopicProperties;
    this.ourHostName = ourHostName;
    this.boundAgentInstallRepository = boundAgentInstallRepository;

    eventsConsumed = meterRegistry.counter("eventsConsumed", "type", "AgentInstallChangeEvent");
    installInstructionFailed = meterRegistry.counter("installInstructionFailed");
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

    eventsConsumed.increment();

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

    final BoundAgentInstall binding = boundAgentInstallRepository
        .findAllByTenantResourceAgentType(event.getTenantId(),
            event.getResourceId(), event.getAgentType())
        .stream().findFirst()
        .orElseThrow(
            () -> new NotFoundException("Could find find agent for given resource and agent type"));

    log.debug("Retrieved agentInstallBinding={} for processing event={}", binding, event);

    final AgentRelease agentRelease = binding.getAgentInstall().getAgentRelease();

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
        final Map<AgentType, String> installedVersions = envoyRegistry
            .trackAgentInstall(envoyId, agentType, agentVersion);

        // Re-process bindings since the agent version may have been upgraded such that the
        // applicable translations might have changed.
        monitorBindingService.processEnvoy(envoyId, installedVersions);
      } else {
        log.warn("Unable to send agent install instruction to envoy={}", envoyId);
        installInstructionFailed.increment();
        // It's hard to do anything else to recover, but most likely cause is a networking
        // issues which would lead to an Envoy disconnect and reattachment anyway. At that
        // point the slate is wiped clean and repopulated via normal processing.
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
