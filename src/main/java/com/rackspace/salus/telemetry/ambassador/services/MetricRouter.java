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

import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import com.rackspace.monplat.protocol.Metric;
import com.rackspace.monplat.protocol.UniversalMetricFrame;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.services.TelemetryEdge.PostedMetric;
import com.rackspace.salus.telemetry.messaging.KafkaMessageType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.io.EncoderFactory;
import org.apache.logging.log4j.util.Strings;
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
        missingResourceLabelTracking = meterRegistry.counter("errors", "cause", "missingResourceLabelTracking");
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

        String resourceId = tagsMap.remove(ConfigInstructionsBuilder.LABEL_RESOURCE);
        final String measurementName = nameTagValue.getName();
        if (resourceId == null) {
            resourceId = envoyRegistry.getResourceId(envoyId);

            if (resourceId == null) {
                log.warn("Unable to locate resourceId while routing"
                        + " measurement={} for tenant={} envoy={} tags={}",
                    measurementName, tenantId, envoyId, nameTagValue.getTagsMap());
                return;
            }
        }

        final String taggedTargetTenant = tagsMap.remove(ConfigInstructionsBuilder.LABEL_TARGET_TENANT);
        if (taggedTargetTenant != null) {
            tenantId = taggedTargetTenant;
        }

        Map<String, String> resourceLabels = resourceLabelsService.getResourceLabels(tenantId, resourceId);
        if (resourceLabels == null) {
            log.warn(
                "No resource labels are being tracked for tenant={} resource={}",
                tenantId, resourceId
            );
            missingResourceLabelTracking.increment();
            resourceLabels = Collections.emptyMap();
        }

        List<Metric> metrics = nameTagValue.getIvaluesMap().entrySet().stream()
            .map(entry -> Metric.newBuilder()
                .setGroup(measurementName)
                .setTimestamp(getProtoBufTimestamp(timestamp))
                .setName(entry.getKey())
                .setInt(entry.getValue())
                .putAllMetadata(tagsMap)
                .build()).collect(Collectors.toList());
        metrics.addAll(nameTagValue.getFvaluesMap().entrySet().stream()
            .map(entry -> Metric.newBuilder()
                .setGroup(measurementName)
                .setTimestamp(getProtoBufTimestamp(timestamp))
                .setName(entry.getKey())
                .setFloat(entry.getValue())
                .putAllMetadata(tagsMap)
                .build()).collect(Collectors.toList()));
        metrics.addAll(nameTagValue.getSvaluesMap().entrySet().stream()
            .map(entry -> Metric.newBuilder()
                .setGroup(measurementName)
                .setTimestamp(getProtoBufTimestamp(timestamp))
                .setName(entry.getKey())
                .setString(entry.getValue())
                .putAllMetadata(tagsMap)
                .build()).collect(Collectors.toList()));

        final UniversalMetricFrame universalMetricFrame = UniversalMetricFrame.newBuilder()
            .setAccountType(UniversalMetricFrame.AccountType.MANAGED_HOSTING)
            .setTenantId(tenantId)
            .setDevice(resourceId)
            .putAllDeviceMetadata(resourceLabels)
            .putAllSystemMetadata(Collections.singletonMap("envoy_id", envoyId))
            .setMonitoringSystem(UniversalMetricFrame.MonitoringSystem.SALUS)
            .addAllMetrics(metrics)
            .build();

        try {

            metricsRouted.increment();
            kafkaEgress.send(Strings.join(List.of(tenantId, resourceId, measurementName), ','),
                KafkaMessageType.METRIC, JsonFormat.printer().print(universalMetricFrame));

        } catch (IOException e) {
            log.warn("Failed to encode metricFrame={} from={}", universalMetricFrame,
                postedMetric, e);
            throw new RuntimeException("Failed to encode metric", e);
        }
    }

    private Timestamp getProtoBufTimestamp(Instant timestamp) {
        return Timestamp.newBuilder().setSeconds(timestamp.getEpochSecond())
            .setNanos(timestamp.getNano()).build();
    }
}
