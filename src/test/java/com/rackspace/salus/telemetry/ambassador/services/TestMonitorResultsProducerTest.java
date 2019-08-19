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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.rackspace.salus.common.messaging.KafkaTopicProperties;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.services.TelemetryEdge.TestMonitorResults;
import com.rackspace.salus.telemetry.messaging.TestMonitorResultsEvent;
import com.rackspace.salus.telemetry.model.SimpleNameTagValueMetric;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest
public class TestMonitorResultsProducerTest {

  @Configuration
  @Import({TestMonitorResultsProducer.class})
  public static class TestConfig {

    @Bean
    public KafkaTopicProperties kafkaTopicProperties() {
      final KafkaTopicProperties properties = new KafkaTopicProperties();
      properties.setTestMonitorResults("results-topic");
      return properties;
    }
  }

  @MockBean
  KafkaTemplate kafkaTemplate;

  @Autowired
  TestMonitorResultsProducer testMonitorResultsProducer;

  @Test
  public void testSend() {
    testMonitorResultsProducer.send(
        TestMonitorResults.newBuilder()
            .setCorrelationId("id-1")
            .addErrors("an error")
            .addMetrics(
                TelemetryEdge.Metric.newBuilder()
                    .setNameTagValue(TelemetryEdge.NameTagValueMetric.newBuilder()
                        .setName("cpu")
                        .putTags("tag-1", "value-1")
                        .putIvalues("count", 3)
                        .putFvalues("usage", 5.67)
                        .putSvalues("available", "true")
                        .build())
                    .build()
            )
            .build()
    );

    //noinspection unchecked
    verify(kafkaTemplate).send(eq("results-topic"), argThat(o -> {
      assertThat(o).isInstanceOf(TestMonitorResultsEvent.class);

      final TestMonitorResultsEvent event = (TestMonitorResultsEvent) o;
      assertThat(event.getCorrelationId()).isEqualTo("id-1");
      assertThat(event.getErrors()).containsExactly("an error");
      assertThat(event.getMetrics()).containsExactly(
          new SimpleNameTagValueMetric()
          .setName("cpu")
          .setTags(Map.of("tag-1", "value-1"))
          .setFvalues(Map.of("usage", 5.67))
          .setIvalues(Map.of("count", 3L))
          .setSvalues(Map.of("available", "true"))
      );

      return true;
    }));
  }
}