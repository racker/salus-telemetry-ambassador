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
import com.rackspace.salus.monitor_management.web.client.MonitorApi;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstruction;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstructionTestMonitor;
import com.rackspace.salus.services.TelemetryEdge.TestMonitorResults;
import com.rackspace.salus.telemetry.messaging.TestMonitorRequestEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TestMonitorEventListener {

  private final EnvoyRegistry envoyRegistry;
  private final KafkaTopicProperties kafkaTopicProperties;
  private final TestMonitorResultsProducer resultsProducer;
  private final MonitorApi monitorApi;
  private final String appName;
  private final String ourHostName;

  @Autowired
  public TestMonitorEventListener(EnvoyRegistry envoyRegistry,
                                  KafkaTopicProperties kafkaTopicProperties,
                                  TestMonitorResultsProducer resultsProducer,
                                  MonitorApi monitorApi,
                                  @Value("${spring.application.name}") String appName,
                                  @Value("${localhost.name}") String ourHostName) {
    this.envoyRegistry = envoyRegistry;
    this.kafkaTopicProperties = kafkaTopicProperties;
    this.resultsProducer = resultsProducer;
    this.monitorApi = monitorApi;
    this.appName = appName;
    this.ourHostName = ourHostName;
  }

  @SuppressWarnings("unused") // in @KafkaListener SpEL
  public String getTopic() {
    return kafkaTopicProperties.getTestMonitorRequests();
  }

  @SuppressWarnings("unused") // in @KafkaListener SpEL
  public String getGroupId() {
    return String.join("-", appName, "testMonitors", ourHostName);
  }

  @KafkaListener(topics = "#{__listener.topic}", groupId = "#{__listener.groupId}")
  public void consumeTestMonitorEvent(TestMonitorRequestEvent event) {
    final String envoyId = event.getEnvoyId();

    if (!envoyRegistry.contains(envoyId)) {
      log.trace("Discarded testMonitorEvent={} for unregistered Envoy", event);
      return;
    }

    log.debug("Handling testMonitorEvent={}", event);

    final String installedAgentVersion =
        envoyRegistry.getInstalledAgentVersions(envoyId)
            .get(event.getAgentType());

    // immediately reject test monitor if no version of given agent type is installed
    if (installedAgentVersion == null) {
      resultsProducer.send(
          TestMonitorResults.newBuilder()
              .setCorrelationId(event.getCorrelationId())
              .addErrors("Agent is not installed")
              .build()
      );
      return;
    }

    final String translatedContent = monitorApi.translateMonitorContent(
        event.getAgentType(), installedAgentVersion,
        event.getRenderedContent()
    );

    EnvoyInstruction testMonitorInstruction = EnvoyInstruction.newBuilder()
        .setTestMonitor(
            EnvoyInstructionTestMonitor.newBuilder()
                .setAgentType(TelemetryEdge.AgentType.valueOf(event.getAgentType().name()))
                .setCorrelationId(event.getCorrelationId())
                .setContent(translatedContent)
                .setTimeout(event.getTimeout())
        )
        .build();
    envoyRegistry.sendInstruction(envoyId, testMonitorInstruction);
  }
}
