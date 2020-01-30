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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rackspace.salus.services.TelemetryEdge.LogEvent;
import com.rackspace.salus.telemetry.messaging.KafkaMessageType;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * This service routes to Kafka the "log events" that originate from Filebeat running as an agent
 * of a tenant's Envoy.
 */
@Service
@Slf4j
public class LogEventRouter {

    private final EnvoyRegistry envoyRegistry;
    private final ResourceLabelsService resourceLabelsService;
    private final ObjectMapper objectMapper;
    private final JsonNodeFactory nodeFactory;
    private final KafkaEgress kafkaEgress;

    @Autowired
    public LogEventRouter(EnvoyRegistry envoyRegistry, ResourceLabelsService resourceLabelsService,
                          ObjectMapper objectMapper, KafkaEgress kafkaEgress) {
        this.envoyRegistry = envoyRegistry;
        this.resourceLabelsService = resourceLabelsService;
        this.objectMapper = objectMapper;
        nodeFactory = objectMapper.getNodeFactory();
        this.kafkaEgress = kafkaEgress;
    }

    public void route(String tenantId, String envoyId, LogEvent request) {

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

                final String resourceId = envoyRegistry.getResourceId(envoyId);

                Map<String, String> labels = resourceLabelsService.getResourceLabels(tenantId, resourceId);
                if (labels == null) {
                    log.warn(
                        "No resource labels are being tracked for tenant={} resource={}",
                        tenantId, resourceId
                    );
                    labels = Collections.emptyMap();
                }

                final ObjectNode resourceLabelsNode = nodeFactory.objectNode();
                labels.forEach((name, value) -> {
                    resourceLabelsNode.set(name, nodeFactory.textNode(value));
                });
                metadataNode.set("@resourceLabels", resourceLabelsNode);

                final String enrichedJson = objectMapper.writeValueAsString(eventObj);

                log.trace("Sending: {}", enrichedJson);
                kafkaEgress.send(Strings.join(List.of(tenantId, resourceId), ','),
                    KafkaMessageType.LOG, enrichedJson);
            }
            else {
                log.warn("Unexpected json tree type: {}", event);
            }

        } catch (IOException e) {
            log.warn("Failed to decode/encode event for tenant={} from {}", tenantId, request);
        }
    }
}
