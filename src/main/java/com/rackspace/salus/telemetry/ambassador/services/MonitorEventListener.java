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
import com.rackspace.salus.telemetry.messaging.MonitorBoundEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MonitorEventListener implements ConsumerSeekAware {

  private final String topic;
  private final MonitorBindingService monitorBindingService;
  private final EnvoyRegistry envoyRegistry;
  private final String ourHostName;
  private final Counter eventsConsumed;

  @Autowired
  public MonitorEventListener(KafkaTopicProperties kafkaTopicProperties,
                              EnvoyRegistry envoyRegistry,
                              MonitorBindingService monitorBindingService,
                              MeterRegistry meterRegistry,
                              @Value("${localhost.name}") String ourHostName) {
    this.topic = kafkaTopicProperties.getMonitors();
    this.monitorBindingService = monitorBindingService;
    this.envoyRegistry = envoyRegistry;
    this.ourHostName = ourHostName;

    eventsConsumed = meterRegistry.counter("eventsConsumed", "type", "MonitorBoundEvent");
  }

  @SuppressWarnings("unused") // used in SpEL
  public String getTopic() {
    return topic;
  }

  @SuppressWarnings("unused") // used in SpEL
  public String getGroupId() {
    return "ambassador-monitors-"+ourHostName;
  }

  @KafkaListener(topics = "#{__listener.topic}", groupId = "#{__listener.groupId}")
  public void handleMessage(MonitorBoundEvent event) {
    final String envoyId = event.getEnvoyId();

    if (!envoyRegistry.contains(envoyId)) {
      log.trace("Discarded monitorEvent={} for unregistered Envoy", event);
      return;
    }

    eventsConsumed.increment();

    log.debug("Handling monitorBoundEvent={}", event);

    monitorBindingService.processEnvoy(envoyId);
  }

  @Override
  public void onPartitionsAssigned(Map<TopicPartition, Long> assignments,
                                   ConsumerSeekCallback callback) {
    assignments.forEach((tp, currentOffset) -> callback.seekToEnd(tp.topic(), tp.partition()));
  }

  @Override
  public void registerSeekCallback(ConsumerSeekCallback callback) {
    // not needed
  }

  @Override
  public void onIdleContainer(Map<TopicPartition, Long> assignments,
                              ConsumerSeekCallback callback) {
    // not needed
  }
}
