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

import com.rackspace.salus.common.messaging.KafkaTopicProperties;
import com.rackspace.salus.services.TelemetryEdge.Metric;
import com.rackspace.salus.services.TelemetryEdge.NameTagValueMetric;
import com.rackspace.salus.services.TelemetryEdge.TestMonitorResults;
import com.rackspace.salus.telemetry.messaging.TestMonitorResultsEvent;
import com.rackspace.salus.telemetry.model.SimpleNameTagValueMetric;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class TestMonitorResultsProducer {

  private final KafkaTopicProperties kafkaTopicProperties;
  private final KafkaTemplate kafkaTemplate;

  @Autowired
  public TestMonitorResultsProducer(KafkaTopicProperties kafkaTopicProperties,
                                    KafkaTemplate kafkaTemplate) {
    this.kafkaTopicProperties = kafkaTopicProperties;
    this.kafkaTemplate = kafkaTemplate;
  }

  public void send(TestMonitorResults results) {
    final TestMonitorResultsEvent event = new TestMonitorResultsEvent()
        .setCorrelationId(results.getCorrelationId())
        .setErrors(results.getErrorsList())
        .setMetrics(convertMetrics(results.getMetricsList()));

    //noinspection unchecked
    kafkaTemplate.send(kafkaTopicProperties.getTestMonitorResults(), event);
  }

  private List<SimpleNameTagValueMetric> convertMetrics(List<Metric> envoyMetrics) {
    return envoyMetrics.stream()
        .map(metric -> {
          final NameTagValueMetric ntv = metric.getNameTagValue();
          return new SimpleNameTagValueMetric()
                  .setName(ntv.getName())
                  .setTags(ntv.getTagsMap())
                  .setIvalues(ntv.getIvaluesMap())
                  .setFvalues(ntv.getFvaluesMap())
                  .setSvalues(ntv.getSvaluesMap());
            }
        )
        .collect(Collectors.toList());
  }
}
