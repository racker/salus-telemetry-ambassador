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
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import com.rackspace.salus.monitor_management.web.model.BoundMonitorDTO;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.services.TelemetryEdge.ConfigurationOp.Type;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstruction;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstructionConfigure;
import com.rackspace.salus.telemetry.messaging.OperationType;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public class ConfigInstructionsBuilderTest {

  @Test
  public void testTypicalScenario() {

    final ConfigInstructionsBuilder builder = new ConfigInstructionsBuilder();

    final UUID m1 = UUID.fromString("00000000-0000-0001-0000-000000000000");
    final UUID m2 = UUID.fromString("00000000-0000-0002-0000-000000000000");
    final UUID m3 = UUID.fromString("00000000-0000-0003-0000-000000000000");
    final UUID m4 = UUID.fromString("00000000-0000-0004-0000-000000000000");
    final UUID m5 = UUID.fromString("00000000-0000-0005-0000-000000000000");

    builder.add(
        new BoundMonitorDTO()
            .setAgentType(TELEGRAF)
            .setRenderedContent("content1")
            .setMonitorId(m1)
            .setResourceId("r-1"),
        OperationType.CREATE
    );
    builder.add(
        new BoundMonitorDTO()
            .setAgentType(TELEGRAF)
            .setRenderedContent("content2")
            .setMonitorId(m2)
            .setResourceId("r-1"),
        OperationType.UPDATE
    );
    builder.add(
        new BoundMonitorDTO()
            .setAgentType(TELEGRAF)
            .setRenderedContent("")
            .setMonitorId(m3)
            .setResourceId("r-1"),
        OperationType.DELETE
    );
    builder.add(
        new BoundMonitorDTO()
            .setAgentType(FILEBEAT)
            .setRenderedContent("content4")
            .setMonitorId(m4)
            .setResourceId("r-1"),
        OperationType.CREATE
    );
    builder.add(
        new BoundMonitorDTO()
            .setAgentType(TELEGRAF)
            .setRenderedContent("content5")
            .setMonitorId(m5)
            .setTargetTenant("t-1")
            .setResourceId("r-2")
            .setZone("z-1"),
        OperationType.CREATE
    );

    final List<EnvoyInstruction> instructions = builder.build();
    assertThat(instructions, hasSize(2));

    final EnvoyInstructionConfigure telegrafConfig = instructions.get(0).getConfigure();
    assertThat(telegrafConfig, notNullValue());
    assertThat(telegrafConfig.getAgentType(), equalTo(TelemetryEdge.AgentType.TELEGRAF));
    assertThat(telegrafConfig.getOperationsList(), hasSize(4));
    assertThat(telegrafConfig.getOperations(0).getId(), equalTo("00000000-0000-0001-0000-000000000000_r-1"));
    assertThat(telegrafConfig.getOperations(0).getType(), equalTo(Type.CREATE));
    assertThat(telegrafConfig.getOperations(0).getContent(), equalTo("content1"));

    assertThat(telegrafConfig.getOperations(1).getId(), equalTo("00000000-0000-0002-0000-000000000000_r-1"));
    assertThat(telegrafConfig.getOperations(1).getType(), equalTo(Type.MODIFY));
    assertThat(telegrafConfig.getOperations(1).getContent(), equalTo("content2"));

    assertThat(telegrafConfig.getOperations(2).getId(), equalTo("00000000-0000-0003-0000-000000000000_r-1"));
    assertThat(telegrafConfig.getOperations(2).getType(), equalTo(Type.REMOVE));
    // content of REMOVE is not used

    assertThat(telegrafConfig.getOperations(3).getId(), equalTo("00000000-0000-0005-0000-000000000000_r-2"));
    assertThat(telegrafConfig.getOperations(3).getType(), equalTo(Type.CREATE));
    assertThat(telegrafConfig.getOperations(3).getContent(), equalTo("content5"));
    assertThat(telegrafConfig.getOperations(3).getExtraLabelsMap(), allOf(
        hasEntry(BoundMonitorUtils.LABEL_TARGET_TENANT, "t-1"),
        hasEntry(BoundMonitorUtils.LABEL_RESOURCE, "r-2")
        )
    );

    final EnvoyInstructionConfigure filebeatConfig = instructions.get(1).getConfigure();
    assertThat(filebeatConfig, notNullValue());
    assertThat(filebeatConfig.getAgentType(), equalTo(TelemetryEdge.AgentType.FILEBEAT));
    assertThat(filebeatConfig.getOperationsList(), hasSize(1));
    assertThat(filebeatConfig.getOperations(0).getId(), equalTo("00000000-0000-0004-0000-000000000000_r-1"));
    assertThat(filebeatConfig.getOperations(0).getType(), equalTo(Type.CREATE));
    assertThat(filebeatConfig.getOperations(0).getContent(), equalTo("content4"));
  }
}