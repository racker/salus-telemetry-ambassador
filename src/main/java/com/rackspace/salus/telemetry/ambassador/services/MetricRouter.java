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

import com.rackspace.salus.model.AccountType;
import com.rackspace.salus.model.ExternalMetric;
import com.rackspace.salus.model.MonitoringSystem;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.services.TelemetryEdge.PostedMetric;
import com.rackspace.salus.telemetry.messaging.KafkaMessageType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.Schema;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.JsonEncoder;
import org.apache.avro.specific.SpecificDatumWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MetricRouter {
    private final DateTimeFormatter universalTimestampFormatter;
    private final EncoderFactory avroEncoderFactory;
    private final KafkaEgress kafkaEgress;
    private final EnvoyRegistry envoyRegistry;

    @Autowired
    public MetricRouter(EncoderFactory avroEncoderFactory, KafkaEgress kafkaEgress, EnvoyRegistry envoyRegistry) {
        this.avroEncoderFactory = avroEncoderFactory;
        this.kafkaEgress = kafkaEgress;
        this.envoyRegistry = envoyRegistry;
        universalTimestampFormatter = DateTimeFormatter.ISO_INSTANT;
    }

    public void route(String tenantId, String envoyId,
        PostedMetric postedMetric) {

        final Map<String, String> envoyLabels = envoyRegistry.getEnvoyLabels(envoyId);
        if (envoyLabels == null) {
            throw new IllegalArgumentException("Unable to find Envoy in the registry");
        }

        final TelemetryEdge.NameTagValueMetric nameTagValue = postedMetric.getMetric().getNameTagValue();
        if (nameTagValue == null) {
            throw new IllegalArgumentException("Only supports metrics posted with NameTagValue variant");
        }

        final Instant timestamp = Instant.ofEpochMilli(nameTagValue.getTimestamp());

        final ExternalMetric externalMetric = ExternalMetric.newBuilder()
            .setAccountType(AccountType.RCN)
            .setAccount(tenantId)
            .setTimestamp(universalTimestampFormatter.format(timestamp))
            .setDeviceMetadata(envoyLabels)
            .setMonitoringSystem(MonitoringSystem.SALUS)
            .setSystemMetadata(Collections.singletonMap("envoyId", envoyId))
            .setCollectionMetadata(nameTagValue.getTagsMap())
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

            kafkaEgress.send(tenantId, KafkaMessageType.METRIC, out.toString(StandardCharsets.UTF_8.name()));

        } catch (IOException e) {
            log.warn("Failed to Avro encode avroMetric={} original={}", externalMetric, postedMetric, e);
            throw new RuntimeException("Failed to Avro encode metric", e);
        }
    }
}
