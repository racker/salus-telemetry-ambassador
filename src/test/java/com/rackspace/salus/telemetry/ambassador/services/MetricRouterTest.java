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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.telemetry.ambassador.parser.FieldParser;
import com.rackspace.salus.telemetry.messaging.KafkaMessageType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.FileCopyUtils;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
    classes = {MetricRouter.class, SimpleMeterRegistry.class, FieldParser.class})
public class MetricRouterTest {

  @MockBean
  KafkaEgress kafkaEgress;

  @MockBean
  EnvoyRegistry envoyRegistry;

  @MockBean
  ResourceLabelsService resourceLabelsService;

  @Autowired
  MetricRouter metricRouter;

  @Autowired
  FieldParser fieldParser;

  private static String readContent(String resource) throws IOException {
    try (InputStream in = new ClassPathResource(resource).getInputStream()) {
      return FileCopyUtils.copyToString(new InputStreamReader(in));
    }
  }

  @Test
  public void testRouteMetric() throws IOException {

    Map<String, String> envoyLabels = new HashMap<>();
    envoyLabels.put("hostname", "host1");
    envoyLabels.put("os", "linux");

    when(resourceLabelsService.getResourceLabels(any(), any()))
        .thenReturn(envoyLabels);

    when(envoyRegistry.getResourceId(any()))
        .thenReturn("r-1");

    final TelemetryEdge.PostedMetric postedMetric = TelemetryEdge.PostedMetric.newBuilder()
        .setMetric(TelemetryEdge.Metric.newBuilder()
            .setNameTagValue(TelemetryEdge.NameTagValueMetric.newBuilder()
                .setTimestamp(1539030613123L)
                .setName("cpu")
                .putTags("cpu", "cpu1")
                .putTags(ConfigInstructionsBuilder.LABEL_MONITOR_ID, "m-1")
                .putTags(ConfigInstructionsBuilder.LABEL_MONITOR_TYPE, "my-type")
                .putTags(ConfigInstructionsBuilder.LABEL_MONITOR_SCOPE, "my-scope")
                .putFvalues("usage", 1.45)
                .putSvalues("status", "enabled")
                .build())
        )
        .build();

    metricRouter.route("t1", "envoy-1", postedMetric);

    verify(resourceLabelsService).getResourceLabels("t1", "r-1");
    verify(envoyRegistry).getResourceId("envoy-1");
    verify(kafkaEgress).send("t1,r-1,cpu", KafkaMessageType.METRIC,
        readContent("/MetricRouterTest/testRouteMetric.json"));

    verifyNoMoreInteractions(kafkaEgress, envoyRegistry);
  }

  @Test
  public void testRouteMetric_withTargetTenant() throws IOException {

    Map<String, String> envoyLabels = new HashMap<>();
    envoyLabels.put("hostname", "host1");
    envoyLabels.put("os", "linux");

    when(resourceLabelsService.getResourceLabels(any(), any()))
        .thenReturn(envoyLabels);

    when(envoyRegistry.getResourceId(any()))
        .thenReturn("r-of-envoy");

    final TelemetryEdge.PostedMetric postedMetric = TelemetryEdge.PostedMetric.newBuilder()
        .setMetric(TelemetryEdge.Metric.newBuilder()
            .setNameTagValue(TelemetryEdge.NameTagValueMetric.newBuilder()
                .setTimestamp(1539030613123L)
                .setName("cpu")
                .putTags("cpu", "cpu1")
                .putTags(ConfigInstructionsBuilder.LABEL_TARGET_TENANT, "t-some-other")
                .putTags(ConfigInstructionsBuilder.LABEL_RESOURCE, "r-other")
                .putFvalues("usage", 1.45)
                .putSvalues("status", "enabled")
                .build())
        )
        .build();

    metricRouter.route("t1", "envoy-1", postedMetric);

    verify(resourceLabelsService).getResourceLabels("t-some-other", "r-other");
    verify(kafkaEgress).send("t-some-other,r-other,cpu", KafkaMessageType.METRIC,
        readContent("/MetricRouterTest/testRouteMetric_withTargetTenant.json"));

    verifyNoMoreInteractions(kafkaEgress, envoyRegistry);
  }

  @Test
  public void testRouteMetric_invalid_noResourceId() {
    Map<String, String> envoyLabels = new HashMap<>();
    envoyLabels.put("hostname", "host1");
    envoyLabels.put("os", "linux");

    when(resourceLabelsService.getResourceLabels(any(), any()))
        .thenReturn(envoyLabels);

    when(envoyRegistry.getResourceId(any()))
        // induce the scenario
        .thenReturn(null);

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

    metricRouter.route("t-1", "e-1", postedMetric);

    verify(envoyRegistry).getResourceId("e-1");
    verifyNoMoreInteractions(kafkaEgress, envoyRegistry);
  }
}