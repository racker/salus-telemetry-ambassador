/*
 *    Copyright 2018 Rackspace US, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *
 */

package com.rackspace.salus.telemetry.ambassador.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rackspace.salus.services.TelemetryEdge.LogEvent;
import com.rackspace.salus.telemetry.ambassador.types.KafkaMessageType;
import java.io.IOException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class LogEventRouter {

    private final EnvoyRegistry envoyRegistry;
    private final ObjectMapper objectMapper;
    private final JsonNodeFactory nodeFactory;
    private final KafkaEgress kafkaEgress;

    @Autowired
    public LogEventRouter(EnvoyRegistry envoyRegistry, ObjectMapper objectMapper, KafkaEgress kafkaEgress) {
        this.envoyRegistry = envoyRegistry;
        this.objectMapper = objectMapper;
        nodeFactory = objectMapper.getNodeFactory();
        this.kafkaEgress = kafkaEgress;
    }

    public void route(String tenantId, String envoyId, LogEvent request) {
        final Map<String, String> labels = envoyRegistry.getEnvoyLabels(envoyId);

        try {
            final JsonNode event = objectMapper.readTree(request.getJsonContent());
            if (event instanceof ObjectNode) {
                final ObjectNode eventObj = (ObjectNode) event;
                ObjectNode metadataNode = (ObjectNode) event.get("@metadata");
                if (metadataNode == null) {
                    log.warn("@metadata node missing, so injecting into top-level");
                    metadataNode = eventObj;
                }

                metadataNode.set("@tenant", nodeFactory.textNode(tenantId));

                final ObjectNode envoyLabels = nodeFactory.objectNode();
                labels.forEach((name, value) -> {
                    envoyLabels.set(name, nodeFactory.textNode(value));
                });
                metadataNode.set("@envoyLabels", envoyLabels);

                final String enrichedJson = objectMapper.writeValueAsString(eventObj);

                log.trace("Sending: {}", enrichedJson);
                kafkaEgress.send(tenantId, KafkaMessageType.LOG, enrichedJson);
            }
            else {
                log.warn("Unexpected json tree type: {}", event);
            }

        } catch (IOException e) {
            log.warn("Failed to decode/encode event for tenant={} from {}", tenantId, request);
        }
    }
}
