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

import com.rackspace.salus.common.messaging.KafkaTopicProperties;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.services.TelemetryEdge.ConfigurationOp.Type;
import com.rackspace.salus.telemetry.messaging.MonitorEvent;
import com.rackspace.salus.telemetry.messaging.OperationType;
import com.rackspace.salus.telemetry.model.AgentConfig;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MonitorEventListener implements ConsumerSeekAware {

  private final String topic;
  private final EnvoyRegistry envoyRegistry;

  @Autowired
  public MonitorEventListener(KafkaTopicProperties kafkaTopicProperties,
                              EnvoyRegistry envoyRegistry) {
    this.topic = kafkaTopicProperties.getMonitors();
    this.envoyRegistry = envoyRegistry;
  }

  public String getTopic() {
    return topic;
  }

  public String getGroupId() throws UnknownHostException {
    return "ambassador-"+InetAddress.getLocalHost().getHostAddress();
  }

  @KafkaListener(topics = "#{__listener.topic}", groupId = "#{__listener.groupId}")
  public void handleMessage(MonitorEvent event) {
    if (!envoyRegistry.contains(event.getEnvoyId())) {
      log.trace("Discarded monitorEvent={} for unregistered Envoy", event);
      return;
    }

    log.debug("Handling monitorEvent={}", event);

    final AgentConfig agentConfig = event.getConfig();

    final TelemetryEdge.EnvoyInstruction instruction = TelemetryEdge.EnvoyInstruction.newBuilder()
        .setConfigure(
            TelemetryEdge.EnvoyInstructionConfigure.newBuilder()
                .setAgentType(TelemetryEdge.AgentType.valueOf(agentConfig.getAgentType().name()))
                .addOperations(
                    TelemetryEdge.ConfigurationOp.newBuilder()
                        .setType(convertOpType(event.getOperationType()))
                        .setId(event.getMonitorId())
                        .setContent(agentConfig.getContent())
                )
        )
        .build();

    envoyRegistry.sendInstruction(event.getEnvoyId(), instruction);

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
