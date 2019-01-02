/*
 * Copyright 2018 Rackspace US, Inc.
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
import com.rackspace.salus.telemetry.ambassador.config.AmbassadorProperties;
import com.rackspace.salus.telemetry.model.AgentConfig;
import com.rackspace.salus.telemetry.model.ConfigEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.codehaus.jackson.map.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class CheckListener {

    private final String topic;
    private final EnvoyRegistry envoyRegistry;
    private final ObjectMapper objectMapper;

    @Autowired
    public CheckListener(AmbassadorProperties ambassadorProperties, EnvoyRegistry envoyRegistry) {
        this.topic = ambassadorProperties.getKafkaTopics().get("CHECKS");
        this.envoyRegistry = envoyRegistry;
        this.objectMapper = new ObjectMapper();
        log.info("TOpic {}", this.topic);
    }

    public String getTopic() {
        return this.topic;
    }

    //@KafkaListener(topics = "#{ambassadorProperties.kafkaTopics.CHECKS}")
    //@KafkaListener(topics = "#{__listener.topic}")
    @KafkaListener(topics = "checks.json")
    public void listen(ConsumerRecord<String, String> cr) throws Exception {
        log.info("Received new instruction from {}: {}", cr.toString(), this.topic);
        //String envoyInstanceId = cr.key();??
        String value = cr.value();
        ConfigEvent configEvent = objectMapper.readValue(value, ConfigEvent.class);

        log.info("Read value from kafka: {}", configEvent.toString());

        processEvent(configEvent);
    }

    private void processEvent(ConfigEvent configEvent) {
        TelemetryEdge.ConfigurationOp.Type opType = configEvent.getOperation();
        AgentConfig agentConfig = configEvent.getConfig();
        String envoyInstanceId = configEvent.getEnvoyId();
        String tenant = configEvent.getTenantId();

        if (envoyRegistry.contains(envoyInstanceId)) {
            log.debug("Observed applied config={} event for our tenant={} envoyInstance={}",
                    agentConfig.getId(), tenant, envoyInstanceId);
            if (opType == TelemetryEdge.ConfigurationOp.Type.REMOVE) {
                sendDeleteConfigInstruction(tenant, envoyInstanceId, agentConfig);
            } else {
                sendConfigInstruction(tenant, envoyInstanceId, agentConfig, opType);
            }
        } else {
            log.warn("Envoy not connected to this ambassador");
            // We can safely drop this as the check will be created when the envoy connects to a new ambassador
        }
    }

    private void sendDeleteConfigInstruction(String tenant, String envoyInstanceId, AgentConfig agentConfig) {
        log.debug("Removing agent config for tenant={}", tenant);
        final TelemetryEdge.EnvoyInstruction instruction = TelemetryEdge.EnvoyInstruction.newBuilder()
                .setConfigure(
                        TelemetryEdge.EnvoyInstructionConfigure.newBuilder()
                                .setAgentType(TelemetryEdge.AgentType.valueOf(agentConfig.getAgentType().name()))
                                .addOperations(
                                        TelemetryEdge.ConfigurationOp.newBuilder()
                                                .setType(TelemetryEdge.ConfigurationOp.Type.REMOVE)
                                                .setId(agentConfig.getId())
                                )
                )
                .build();
        envoyRegistry.sendInstruction(envoyInstanceId, instruction);
    }

    private void sendConfigInstruction(String tenant, String envoyInstanceId, AgentConfig agentConfig, TelemetryEdge.ConfigurationOp.Type opType) {
        log.debug("Adding/modifying agent config for tenant={}", tenant);
        final TelemetryEdge.EnvoyInstruction instruction = TelemetryEdge.EnvoyInstruction.newBuilder()
                .setConfigure(
                        TelemetryEdge.EnvoyInstructionConfigure.newBuilder()
                                .setAgentType(TelemetryEdge.AgentType.valueOf(agentConfig.getAgentType().name()))
                                .addOperations(
                                        TelemetryEdge.ConfigurationOp.newBuilder()
                                                .setType(opType)
                                                .setId(agentConfig.getId())
                                                .setContent(agentConfig.getContent())
                                )
                )
                .build();

        envoyRegistry.sendInstruction(envoyInstanceId, instruction);
    }
}
