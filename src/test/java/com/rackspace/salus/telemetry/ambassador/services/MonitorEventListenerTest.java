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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.rackspace.salus.common.messaging.KafkaTopicProperties;
import com.rackspace.salus.resource_management.web.client.ResourceApi;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstruction;
import com.rackspace.salus.telemetry.messaging.MonitorBoundEvent;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(classes = {
    ResourceLabelsService.class,
    KafkaTopicProperties.class,
    ResourceLabelsServiceTest.TestConfig.class
})
public class MonitorEventListenerTest {

  @Mock
  EnvoyRegistry envoyRegistry;

  @MockBean
  ResourceApi resourceApi;

  @MockBean
  MonitorBindingService monitorBindingService;

  @Captor
  ArgumentCaptor<EnvoyInstruction> envoyInstructionArg;

  private MonitorEventListener monitorEventListener;

  @Before
  public void setUp() throws Exception {
    monitorEventListener = new MonitorEventListener(new KafkaTopicProperties(), envoyRegistry,
        monitorBindingService, "test-host");
  }

  @Test
  public void handleMessage() {

    when(envoyRegistry.contains("e-1"))
        .thenReturn(true);

    MonitorBoundEvent event = new MonitorBoundEvent()
        .setEnvoyId("e-1");

    monitorEventListener.handleMessage(event);

    verify(monitorBindingService).processEnvoy("e-1");

    verify(envoyRegistry).contains("e-1");

    verifyNoMoreInteractions(envoyRegistry, monitorBindingService);
  }
}