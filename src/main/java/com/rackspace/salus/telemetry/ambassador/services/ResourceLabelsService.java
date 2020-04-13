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

import com.rackspace.salus.common.messaging.KafkaTopicProperties;
import com.rackspace.salus.resource_management.web.client.ResourceApi;
import com.rackspace.salus.resource_management.web.model.ResourceDTO;
import com.rackspace.salus.telemetry.ambassador.types.ResourceKey;
import com.rackspace.salus.telemetry.messaging.ResourceEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

/**
 * This service keeps track of the labels of resources by allowing for explicitly pulling the
 * labels during envoy attachment and by listening for resource change events.
 * <p>
 *   It is expected that resource tracking and releasing will be driven by the creation and removal
 *   of bound monitors since a resource's labels are not important until metrics are flowing due
 *   to a monitor sent down to the Envoy.
 * </p>
 */
@Slf4j
@Service
public class ResourceLabelsService implements ConsumerSeekAware {

  static final String GROUP_ID_PREFIX = "ambassador-resources-";
  private final KafkaTopicProperties kafkaTopicProperties;
  private final ResourceApi resourceApi;
  private final RetryTemplate retryTemplate;
  private final TaskExecutor taskExecutor;

  private final ConcurrentHashMap<ResourceKey, Map<String, String>/*labels*/> resources =
      new ConcurrentHashMap<>();
  private final Counter releasingUntracked;
  private final Counter failedLabelsPull;

  @Autowired
  public ResourceLabelsService(KafkaTopicProperties kafkaTopicProperties, ResourceApi resourceApi,
                               RetryTemplate retryTemplate, TaskExecutor taskExecutor,
                               MeterRegistry meterRegistry) {
    this.kafkaTopicProperties = kafkaTopicProperties;
    this.resourceApi = resourceApi;
    this.retryTemplate = retryTemplate;
    this.taskExecutor = taskExecutor;

    releasingUntracked = meterRegistry.counter("errors", "cause", "releasingUntrackedResource");
    failedLabelsPull = meterRegistry.counter("errors", "cause", "failedResourceLabelPull");
  }

  @SuppressWarnings({"unused", "WeakerAccess"}) // used by SpEL
  public String getTopic() {
    return kafkaTopicProperties.getResources();
  }

  @SuppressWarnings({"unused", "WeakerAccess"}) // used by SpEL
  public String getGroupId() throws UnknownHostException {
    return GROUP_ID_PREFIX + InetAddress.getLocalHost().getHostAddress();
  }

  /**
   * Indicate that a resource should be tracked for label changes
   * @param tenantId the tenant owning the resource
   * @param resourceId the resourceId of the resource
   */
  void trackResource(String tenantId, String resourceId) {
    final ResourceKey key = new ResourceKey(tenantId, resourceId);

    // initialize entry since presence of the key indicates tracking
    resources.putIfAbsent(key, Collections.emptyMap());

    pullResource(key);
  }

  /**
   * Indicate that the resource no longer needs to be tracked, such as due to envoy detachment
   * @param tenantId the tenant owning the resource
   * @param resourceId the resourceId of the resource
   */
  void releaseResource(String tenantId, String resourceId) {
    final Map<String, String> removed = resources.remove(new ResourceKey(tenantId, resourceId));
    if (removed == null) {
      log.warn("Released tenantId={} resourceId={} that wasn't being tracked", tenantId, resourceId);
      releasingUntracked.increment();
    }
  }

  /**
   * Asynchronously query the labels of the resource from resource management microservice and
   * retry if the service throws an error
   */
  private void pullResource(ResourceKey key) {

    taskExecutor.execute(() -> {
      final String tenantId = key.getTenantId();
      final String resourceId = key.getResourceId();

      log.debug("Pulling labels for tenantId={} resourceId={}", tenantId, resourceId);

      final Map<String, String> resourceLabels = retryTemplate.execute(
          retryContext -> {

            log.trace("Trying to query for tenantId={} resourceId={} try={}",
                tenantId, resourceId, retryContext.getRetryCount());

            final ResourceDTO resource = resourceApi.getByResourceId(tenantId, resourceId);

            log.debug("Retrieved labels for tenantId={} resourceId={} try={}",
                tenantId, resourceId, retryContext.getRetryCount());
            return resource.getLabels();
          },
          retryContext -> {
            log.warn(
                "Failed to pull resource labels for tenant={} resourceId={}", tenantId, resourceId);
            failedLabelsPull.increment();
            return null;
          }
      );

      if (resourceLabels != null) {
        resources.put(key, resourceLabels);
      }
    });
  }

  /**
   * Gets the latest tracked labels for the given resource
   * @param tenantId the tenant owning the resource
   * @param resourceId the resourceId of the resource
   * @return the latest tracked labels or null if the resource is not being tracked
   */
  Map<String, String> getResourceLabels(String tenantId, String resourceId) {
    return resources.get(new ResourceKey(tenantId, resourceId));
  }

  @KafkaListener(topics = "#{__listener.topic}", groupId = "#{__listener.groupId}")
  public void handResourceEvent(ResourceEvent event) {
    log.debug("Handling resource event={}", event);
    final ResourceKey key = new ResourceKey(event.getTenantId(), event.getResourceId());
    if (resources.containsKey(key)) {
      pullResource(key);
    }
  }

  @Override
  public void onPartitionsAssigned(Map<TopicPartition, Long> assignments,
                                   ConsumerSeekCallback callback) {
    // Seek to newest offset since the baseline resource labels are pulled during the first
    // bound monitor processing per resource. These resource events are used to ensure the
    // labels remain up to date after monitor binding and until the resource tracking is released
    // when last monitor binding for the resource is processed.
    assignments.forEach((tp, currentOffset) -> callback.seekToEnd(tp.topic(), tp.partition()));
  }

  @Override
  public void registerSeekCallback(ConsumerSeekCallback callback) { }

  @Override
  public void onIdleContainer(Map<TopicPartition, Long> assignments,
                              ConsumerSeekCallback callback) { }
}
