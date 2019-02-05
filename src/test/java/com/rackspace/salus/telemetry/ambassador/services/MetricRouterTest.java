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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.telemetry.ambassador.config.AvroConfig;
import com.rackspace.salus.telemetry.messaging.KafkaMessageType;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public class MetricRouterTest {

    @Configuration
    @Import({MetricRouter.class, AvroConfig.class})
    static class TestConfig { }

    @MockBean
    KafkaEgress kafkaEgress;

    @MockBean
    EnvoyRegistry envoyRegistry;

    @Autowired
    MetricRouter metricRouter;

    @Test
    public void testRouteMetric() {

        Map<String, String> envoyLabels = new HashMap<>();
        envoyLabels.put("hostname", "host1");
        envoyLabels.put("os", "linux");

        when(envoyRegistry.getEnvoyLabels(any()))
            .thenReturn(envoyLabels);

        final TelemetryEdge.PostedMetric postedMetric = TelemetryEdge.PostedMetric.newBuilder()
            .setMetric(TelemetryEdge.Metric.newBuilder()
                .setNameTagValue(TelemetryEdge.NameTagValueMetric.newBuilder()
                    .setTimestamp(1539030613123L)
                    .setName("cpu")
                    .putTags("cpu", "cpu1")
                    .putFvalues("usage", 1.45)
                    .putSvalues("status", "enabled")
                    .build())
            )
            .build();

        metricRouter.route("t1", "envoy-1", postedMetric);

        verify(envoyRegistry).getEnvoyLabels("envoy-1");
        verify(kafkaEgress).send("t1", KafkaMessageType.METRIC,
            "{\"timestamp\":\"2018-10-08T20:30:13.123Z\",\"accountType\":\"RCN\",\"account\":\"t1\",\"device\":\"\",\"deviceLabel\":\"\",\"deviceMetadata\":{\"hostname\":\"host1\",\"os\":\"linux\"},\"monitoringSystem\":\"SALUS\",\"systemMetadata\":{\"envoyId\":\"envoy-1\"},\"collectionName\":\"cpu\",\"collectionLabel\":\"\",\"collectionTarget\":\"\",\"collectionMetadata\":{\"cpu\":\"cpu1\"},\"ivalues\":{},\"fvalues\":{\"usage\":1.45},\"svalues\":{\"status\":\"enabled\"},\"units\":{}}");

        verifyNoMoreInteractions(kafkaEgress, envoyRegistry);
    }
}