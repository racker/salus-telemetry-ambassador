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

import com.rackspace.monplat.protocol.AccountType;
import com.rackspace.monplat.protocol.ExternalMetric;
import com.rackspace.monplat.protocol.MonitoringSystem;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.services.TelemetryEdge.PostedMetric;
import com.rackspace.salus.telemetry.messaging.KafkaMessageType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.Schema;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.JsonEncoder;
import org.apache.avro.specific.SpecificDatumWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * This service routes to Kafka the metric objects that currently originate from Telegraf
 * running as an agent of a tenant's Envoy or as an Envoy running as a public poller.
 */
@Service
@Slf4j
public class MetricRouter {
    private final DateTimeFormatter universalTimestampFormatter;
    private final EncoderFactory avroEncoderFactory;
    private final KafkaEgress kafkaEgress;
    private final EnvoyRegistry envoyRegistry;
    private final ResourceLabelsService resourceLabelsService;
    private final Counter metricsRouted;
    private final Counter missingResourceLabelTracking;

    @Autowired
    public MetricRouter(EncoderFactory avroEncoderFactory, KafkaEgress kafkaEgress,
                        EnvoyRegistry envoyRegistry, ResourceLabelsService resourceLabelsService,
                        MeterRegistry meterRegistry) {
        this.avroEncoderFactory = avroEncoderFactory;
        this.kafkaEgress = kafkaEgress;
        this.envoyRegistry = envoyRegistry;
        this.resourceLabelsService = resourceLabelsService;
        universalTimestampFormatter = DateTimeFormatter.ISO_INSTANT;

        metricsRouted = meterRegistry.counter("routed", "type", "metrics");
        missingResourceLabelTracking = meterRegistry.counter("error", "cause", "missingResourceLabelTracking");
    }

    public void route(String tenantId, String envoyId,
        PostedMetric postedMetric) {

        final TelemetryEdge.NameTagValueMetric nameTagValue = postedMetric.getMetric().getNameTagValue();
        if (nameTagValue == null) {
            throw new IllegalArgumentException("Only supports metrics posted with NameTagValue variant");
        }

        final Instant timestamp = Instant.ofEpochMilli(nameTagValue.getTimestamp());

        final Map<String, String> tagsMap = new HashMap<>(nameTagValue.getTagsMap());

        // Resolve any tags injected for remote monitors where the envoy originating the
        // measurement is not necessarily owned by the tenant of the monitor nor running on the
        // resource of the monitor.

        String resourceId = tagsMap.remove(BoundMonitorUtils.LABEL_RESOURCE);
        if (resourceId == null) {
            resourceId = envoyRegistry.getResourceId(envoyId);

            if (resourceId == null) {
                log.warn("Unable to locate resourceId while routing"
                        + " measurement={} for tenant={} envoy={} tags={}",
                    nameTagValue.getName(), tenantId, envoyId, nameTagValue.getTagsMap());
                return;
            }
        }

        final String taggedTargetTenant = tagsMap.remove(BoundMonitorUtils.LABEL_TARGET_TENANT);
        if (taggedTargetTenant != null) {
            tenantId = taggedTargetTenant;
        }

        Map<String, String> labels = resourceLabelsService.getResourceLabels(tenantId, resourceId);
        if (labels == null) {
            log.warn(
                "No resource labels are being tracked for tenant={} resource={}",
                tenantId, resourceId
            );
            missingResourceLabelTracking.increment();
            labels = Collections.emptyMap();
        }

        final ExternalMetric externalMetric = ExternalMetric.newBuilder()
            .setAccountType(AccountType.RCN)
            .setAccount(tenantId)
            .setTimestamp(universalTimestampFormatter.format(timestamp))
            .setDevice(resourceId)
            .setDeviceMetadata(labels)
            .setMonitoringSystem(MonitoringSystem.SALUS)
            .setSystemMetadata(Collections.singletonMap("envoyId", envoyId))
            .setCollectionMetadata(tagsMap)
            .setCollectionName(nameTagValue.getName())
            .setFvalues(nameTagValue.getFvaluesMap())
            .setSvalues(nameTagValue.getSvaluesMap())
            .setIvalues(Collections.emptyMap())
            .setUnits(Collections.emptyMap())
            .build();

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            final Schema schema = externalMetric.getSchema();
            final JsonEncoder jsonEncoder = avroEncoderFactory.jsonEncoder(schema, out);

            final SpecificDatumWriter<Object> datumWriter = new SpecificDatumWriter<>(schema);
            datumWriter.write(externalMetric, jsonEncoder);
            jsonEncoder.flush();

            metricsRouted.increment();
            kafkaEgress.send(tenantId, KafkaMessageType.METRIC, out.toString(StandardCharsets.UTF_8.name()));

        } catch (IOException|NullPointerException e) {
            log.warn("Failed to Avro encode avroMetric={} original={}", externalMetric, postedMetric, e);
            throw new RuntimeException("Failed to Avro encode metric", e);
        }
    }
}
