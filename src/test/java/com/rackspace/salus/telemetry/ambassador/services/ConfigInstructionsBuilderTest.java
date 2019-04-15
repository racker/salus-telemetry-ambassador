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

import static com.rackspace.salus.telemetry.model.AgentType.FILEBEAT;
import static com.rackspace.salus.telemetry.model.AgentType.TELEGRAF;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import com.rackspace.salus.monitor_management.entities.BoundMonitor;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.services.TelemetryEdge.ConfigurationOp.Type;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstruction;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstructionConfigure;
import com.rackspace.salus.telemetry.messaging.OperationType;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public class ConfigInstructionsBuilderTest {

  @Test
  public void testTypicalScenario() {

    final ConfigInstructionsBuilder builder = new ConfigInstructionsBuilder();

    final UUID m1 = UUID.randomUUID();
    final UUID m2 = UUID.randomUUID();
    final UUID m3 = UUID.randomUUID();
    final UUID m4 = UUID.randomUUID();
    final UUID m5 = UUID.randomUUID();

    builder.add(
        new BoundMonitor().setAgentType(TELEGRAF).setRenderedContent("content1").setMonitorId(m1),
        OperationType.CREATE);
    builder.add(
        new BoundMonitor().setAgentType(TELEGRAF).setRenderedContent("content2").setMonitorId(m2),
        OperationType.UPDATE);
    builder.add(
        new BoundMonitor().setAgentType(TELEGRAF).setRenderedContent("").setMonitorId(m3),
        OperationType.DELETE);
    builder.add(
        new BoundMonitor().setAgentType(FILEBEAT).setRenderedContent("content4").setMonitorId(m4),
        OperationType.CREATE);
    builder.add(
        new BoundMonitor()
            .setAgentType(TELEGRAF)
            .setRenderedContent("content5")
            .setMonitorId(m5)
            .setTargetTenant("t-1"),
        OperationType.CREATE);

    final List<EnvoyInstruction> instructions = builder.build();
    assertThat(instructions, hasSize(2));

    final EnvoyInstructionConfigure telegrafConfig = instructions.get(0).getConfigure();
    assertThat(telegrafConfig, notNullValue());
    assertThat(telegrafConfig.getAgentType(), equalTo(TelemetryEdge.AgentType.TELEGRAF));
    assertThat(telegrafConfig.getOperationsList(), hasSize(4));
    assertThat(telegrafConfig.getOperations(0).getId(), equalTo(m1.toString()));
    assertThat(telegrafConfig.getOperations(0).getType(), equalTo(Type.CREATE));
    assertThat(telegrafConfig.getOperations(0).getContent(), equalTo("content1"));
    assertThat(telegrafConfig.getOperations(1).getId(), equalTo(m2.toString()));
    assertThat(telegrafConfig.getOperations(1).getType(), equalTo(Type.MODIFY));
    assertThat(telegrafConfig.getOperations(1).getContent(), equalTo("content2"));
    assertThat(telegrafConfig.getOperations(2).getId(), equalTo(m3.toString()));
    assertThat(telegrafConfig.getOperations(2).getType(), equalTo(Type.REMOVE));
    // content of REMOVE is not used
    assertThat(telegrafConfig.getOperations(3).getId(), equalTo(m5.toString()));
    assertThat(telegrafConfig.getOperations(3).getType(), equalTo(Type.CREATE));
    assertThat(telegrafConfig.getOperations(3).getContent(), equalTo("content5"));
    assertThat(telegrafConfig.getOperations(3).getExtraLabelsMap(), equalTo(
        Collections.singletonMap(
            ConfigInstructionsBuilder.TARGET_TENANT,
            "t-1"
        )));

    final EnvoyInstructionConfigure filebeatConfig = instructions.get(1).getConfigure();
    assertThat(filebeatConfig, notNullValue());
    assertThat(filebeatConfig.getAgentType(), equalTo(TelemetryEdge.AgentType.FILEBEAT));
    assertThat(filebeatConfig.getOperationsList(), hasSize(1));
    assertThat(filebeatConfig.getOperations(0).getId(), equalTo(m4.toString()));
    assertThat(filebeatConfig.getOperations(0).getType(), equalTo(Type.CREATE));
    assertThat(filebeatConfig.getOperations(0).getContent(), equalTo("content4"));
  }
}