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

import static java.util.Collections.singletonMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.rackspace.salus.common.messaging.KafkaTopicProperties;
import com.rackspace.salus.resource_management.web.client.ResourceApi;
import com.rackspace.salus.resource_management.web.model.ResourceDTO;
import com.rackspace.salus.telemetry.messaging.ResourceEvent;
import java.net.UnknownHostException;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.client.ResourceAccessException;

@RunWith(SpringRunner.class)
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(classes = {
    ResourceLabelsService.class,
    KafkaTopicProperties.class,
    MeterRegistryTestConfig.class,
    ResourceLabelsServiceTest.TestConfig.class
})
public class ResourceLabelsServiceTest {

  @TestConfiguration
  static class TestConfig {
    @Bean
    RetryTemplate retryTemplate() {
      final RetryTemplate retryTemplate = new RetryTemplate();
      retryTemplate.setRetryPolicy(new SimpleRetryPolicy(2));
      return retryTemplate;
    }

    @Bean
    TaskExecutor taskExecutor() {
      return new SyncTaskExecutor();
    }
  }

  @Autowired
  ResourceLabelsService resourceLabelsService;

  @Autowired
  KafkaTopicProperties kafkaTopicProperties;

  @MockBean
  ResourceApi resourceApi;

  @Test
  public void testKafkaFields() throws UnknownHostException {
    assertThat(resourceLabelsService.getGroupId(), startsWith(ResourceLabelsService.GROUP_ID_PREFIX));
    assertThat(resourceLabelsService.getTopic(), equalTo(kafkaTopicProperties.getResources()));
  }

  @Test
  public void test_trackResource() {
    final Map<String, String> expectedLabels = singletonMap("agent_discovered_os", "linux");

    when(resourceApi.getByResourceId("t-1", "r-1"))
        .thenReturn(
            new ResourceDTO()
            .setLabels(expectedLabels)
        );

    resourceLabelsService.trackResource("t-1", "r-1");

    final Map<String, String> labels = resourceLabelsService
        .getResourceLabels("t-1", "r-1");

    assertThat(labels, equalTo(expectedLabels));

    verify(resourceApi).getByResourceId("t-1", "r-1");

    verifyNoMoreInteractions(resourceApi);
  }

  @Test
  public void test_trackResource_failFirstQuery() {
    final Map<String, String> expectedLabels = singletonMap("agent_discovered_os", "linux");

    when(resourceApi.getByResourceId("t-1", "r-1"))
        .thenThrow(ResourceAccessException.class)
        .thenReturn(
            new ResourceDTO()
                .setLabels(expectedLabels)
        );

    resourceLabelsService.trackResource("t-1", "r-1");

    final Map<String, String> labels = resourceLabelsService
        .getResourceLabels("t-1", "r-1");

    assertThat(labels, equalTo(expectedLabels));

    verify(resourceApi, times(2)).getByResourceId("t-1", "r-1");

    verifyNoMoreInteractions(resourceApi);
  }

  @Test
  public void test_trackResource_failAllQuery() {
    when(resourceApi.getByResourceId("t-1", "r-1"))
        .thenThrow(ResourceAccessException.class)
        .thenThrow(ResourceAccessException.class);

    resourceLabelsService.trackResource("t-1", "r-1");

    final Map<String, String> labels = resourceLabelsService
        .getResourceLabels("t-1", "r-1");

    assertThat(labels, notNullValue());
    assertThat(labels.size(), equalTo(0));

    verify(resourceApi, times(2)).getByResourceId("t-1", "r-1");

    verifyNoMoreInteractions(resourceApi);
  }

  @Test
  public void test_releaseResource() {
    final Map<String, String> expectedLabels = singletonMap("agent_discovered_os", "linux");

    when(resourceApi.getByResourceId("t-1", "r-1"))
        .thenReturn(
            new ResourceDTO()
                .setLabels(expectedLabels)
        );

    resourceLabelsService.trackResource("t-1", "r-1");

    final Map<String, String> beforeRelease = resourceLabelsService
        .getResourceLabels("t-1", "r-1");

    assertThat(beforeRelease, equalTo(expectedLabels));

    resourceLabelsService.releaseResource("t-1", "r-1");

    final Map<String, String> afterRelease = resourceLabelsService
        .getResourceLabels("t-1", "r-1");

    assertThat(afterRelease, nullValue());

    verify(resourceApi).getByResourceId("t-1", "r-1");

    verifyNoMoreInteractions(resourceApi);
  }

  @Test
  public void test_getResourceLabels_noTracking() {
    final Map<String, String> labels = resourceLabelsService
        .getResourceLabels("t-never", "r-none");

    assertThat(labels, nullValue());
  }

  @Test
  public void test_handResourceEvent_noTracking() {
    resourceLabelsService.handResourceEvent(
        new ResourceEvent()
        .setTenantId("t-1")
        .setResourceId("r-1")
    );

    verifyNoMoreInteractions(resourceApi);
  }

  @Test
  public void test_handResourceEvent_tracking() {

    when(resourceApi.getByResourceId("t-1", "r-1"))
        .thenReturn(new ResourceDTO().setLabels(singletonMap("env", "pre")))
        .thenReturn(new ResourceDTO().setLabels(singletonMap("env", "post")));

    resourceLabelsService.trackResource("t-1", "r-1");

    final Map<String, String> preLabels = resourceLabelsService
        .getResourceLabels("t-1", "r-1");
    assertThat(preLabels, equalTo(singletonMap("env", "pre")));

    resourceLabelsService.handResourceEvent(
        new ResourceEvent()
        .setTenantId("t-1")
        .setResourceId("r-1")
    );

    final Map<String, String> postLabels = resourceLabelsService
        .getResourceLabels("t-1", "r-1");
    assertThat(postLabels, equalTo(singletonMap("env", "post")));

    verify(resourceApi, times(2)).getByResourceId("t-1", "r-1");

    verifyNoMoreInteractions(resourceApi);
  }
}