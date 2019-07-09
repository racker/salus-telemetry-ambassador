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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.rackspace.salus.monitor_management.web.model.BoundMonitorDTO;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstruction;
import com.rackspace.salus.services.TelemetryEdge.EnvoySummary;
import com.rackspace.salus.telemetry.ambassador.config.AmbassadorProperties;
import com.rackspace.salus.telemetry.ambassador.config.GrpcConfig;
import com.rackspace.salus.telemetry.ambassador.types.ZoneNotAuthorizedException;
import com.rackspace.salus.telemetry.etcd.services.EnvoyLeaseTracking;
import com.rackspace.salus.telemetry.etcd.services.EnvoyResourceManagement;
import com.rackspace.salus.telemetry.etcd.services.ZoneStorage;
import com.rackspace.salus.telemetry.etcd.types.ResolvedZone;
import com.rackspace.salus.telemetry.messaging.AttachEvent;
import com.rackspace.salus.telemetry.messaging.OperationType;
import com.rackspace.salus.telemetry.model.AgentType;
import com.rackspace.salus.telemetry.model.ResourceInfo;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.concurrent.CompletableToListenableFutureAdapter;
import org.springframework.util.concurrent.ListenableFuture;

@RunWith(SpringRunner.class)
@JsonTest
@Import({
    EnvoyRegistry.class,
    AmbassadorProperties.class,
    GrpcConfig.class,
    SimpleMeterRegistry.class
})
public class EnvoyRegistryTest {

  @MockBean
  EnvoyLeaseTracking envoyLeaseTracking;

  @MockBean
  EnvoyResourceManagement envoyResourceManagement;

  @MockBean
  ZoneAuthorizer zoneAuthorizer;

  @MockBean
  KafkaTemplate kafkaTemplate;

  @MockBean
  ZoneStorage zoneStorage;

  @MockBean
  ResourceLabelsService resourceLabelsService;

  @Mock
  StreamObserver<EnvoyInstruction> streamObserver;

  @Autowired
  EnvoyRegistry envoyRegistry;

  @Test
  public void postsAttachEventOnAttach() throws StatusException {
    final EnvoySummary envoySummary = EnvoySummary.newBuilder()
        .setResourceId("hostname:test-host")
        .putLabels("discovered_os", "linux")
        .build();

    final CompletableFuture<Long> assignedLease = CompletableFuture.completedFuture(1234L);
    when(envoyLeaseTracking.grant(any()))
        .thenReturn(assignedLease);

    when(envoyResourceManagement.registerResource(any(), any(), anyLong(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(new ResourceInfo()));

    RecordMetadata recordMetadata = new RecordMetadata(
        new TopicPartition("telemetry.attaches.json", 0),
        0, 0, 0, null, 0, 0
    );
    SendResult sendResult = new SendResult(null, recordMetadata);
    ListenableFuture lf = new CompletableToListenableFutureAdapter(
        CompletableFuture.completedFuture(sendResult));
    when(kafkaTemplate.send(anyString(), anyString(), any()))
        .thenReturn(lf);

    envoyRegistry.attach("t-1", "e-1", envoySummary,
        InetSocketAddress.createUnresolved("localhost", 60000), streamObserver
    ).join();

    verify(kafkaTemplate)
        .send(
            "telemetry.attaches.json",
            "t-1:hostname:test-host",
            new AttachEvent()
                .setResourceId("hostname:test-host")
                .setLabels(Collections.singletonMap("agent_discovered_os", "linux"))
                .setEnvoyId("e-1")
                .setTenantId("t-1")
                .setEnvoyAddress("localhost")
        );
  }

  @Test
  public void storesZoneOnAttach() throws StatusException, ZoneNotAuthorizedException {
    final EnvoySummary envoySummary = EnvoySummary.newBuilder()
        .setResourceId("hostname:test-host")
        .putLabels("discovered_os", "linux")
        .setZone("z-1")
        .build();

    final CompletableFuture<Long> assignedLease = CompletableFuture.completedFuture(1234L);
    when(envoyLeaseTracking.grant(any()))
        .thenReturn(assignedLease);

    when(envoyResourceManagement.registerResource(any(), any(), anyLong(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(new ResourceInfo()));

    final ResolvedZone resolvedZone = ResolvedZone.createPrivateZone("t-1", "z-1");
    when(zoneAuthorizer.authorize("t-1", "z-1"))
        .thenReturn(resolvedZone);

    when(zoneStorage.registerEnvoyInZone(any(ResolvedZone.class), anyString(), anyString(), anyLong()))
        .then(invocationOnMock -> CompletableFuture.completedFuture(null));

    RecordMetadata recordMetadata = new RecordMetadata(
        new TopicPartition("telemetry.attaches.json", 0),
        0, 0, 0, null, 0, 0
    );
    SendResult sendResult = new SendResult(null, recordMetadata);
    ListenableFuture lf = new CompletableToListenableFutureAdapter(
        CompletableFuture.completedFuture(sendResult));
    when(kafkaTemplate.send(anyString(), anyString(), any()))
        .thenReturn(lf);


    envoyRegistry.attach("t-1", "e-1", envoySummary,
        InetSocketAddress.createUnresolved("localhost", 60000), streamObserver
    ).join();

    verify(zoneAuthorizer).authorize("t-1", "z-1");

    verify(zoneStorage).registerEnvoyInZone(resolvedZone, "e-1", "hostname:test-host", 1234L);

    verify(kafkaTemplate)
        .send(
            "telemetry.attaches.json",
            "t-1:hostname:test-host",
            new AttachEvent()
                .setResourceId("hostname:test-host")
                .setLabels(Collections.singletonMap("agent_discovered_os", "linux"))
                .setEnvoyId("e-1")
                .setTenantId("t-1")
                .setEnvoyAddress("localhost")
        );

    verifyNoMoreInteractions(kafkaTemplate, zoneAuthorizer, zoneStorage, resourceLabelsService);
  }

  @Test
  public void storesEnvoyResourceOnAttach() throws StatusException, ZoneNotAuthorizedException {
    final EnvoySummary envoySummary = EnvoySummary.newBuilder()
        .setResourceId("hostname:test-host")
        .putLabels("discovered_os", "linux")
        .build();

    final CompletableFuture<Long> assignedLease = CompletableFuture.completedFuture(1234L);
    when(envoyLeaseTracking.grant(any()))
        .thenReturn(assignedLease);

    when(envoyResourceManagement.registerResource(any(), any(), anyLong(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(new ResourceInfo()));

    RecordMetadata recordMetadata = new RecordMetadata(
        new TopicPartition("telemetry.attaches.json", 0),
        0, 0, 0, null, 0, 0
    );
    SendResult sendResult = new SendResult(null, recordMetadata);
    ListenableFuture lf = new CompletableToListenableFutureAdapter(
        CompletableFuture.completedFuture(sendResult));
    when(kafkaTemplate.send(anyString(), anyString(), any()))
        .thenReturn(lf);

    // EXECUTE

    envoyRegistry.attach("t-1", "e-1", envoySummary,
        InetSocketAddress.createUnresolved("localhost", 60000), streamObserver
    ).join();

    // VERIFY

    assertThat(envoyRegistry.contains("e-1"), equalTo(true));
    assertThat(envoyRegistry.getResourceId("e-1"), equalTo("hostname:test-host"));
    assertThat(envoyRegistry.containsEnvoyResource("hostname:test-host"), equalTo(true));
    assertThat(envoyRegistry.getEnvoyIdByResource("hostname:test-host"), equalTo("e-1"));

    verify(kafkaTemplate)
        .send(
            "telemetry.attaches.json",
            "t-1:hostname:test-host",
            new AttachEvent()
                .setResourceId("hostname:test-host")
                .setLabels(Collections.singletonMap("agent_discovered_os", "linux"))
                .setEnvoyId("e-1")
                .setTenantId("t-1")
                .setEnvoyAddress("localhost")
        );

    verifyNoMoreInteractions(kafkaTemplate, zoneAuthorizer, zoneStorage, resourceLabelsService);
  }

  @Test(expected = StatusException.class)
  public void failsAttachWhenAbsentResourceId() throws StatusException {
    final EnvoySummary envoySummary = EnvoySummary.newBuilder()
        .putLabels("os", "linux")
        .build();

    envoyRegistry.attach("t-1", "e-1", envoySummary,
        InetSocketAddress.createUnresolved("localhost", 60000), streamObserver
    ).join();
  }

  @Test(expected = StatusException.class)
  public void failsAttachWhenEmptyResourceId() throws StatusException {
    final EnvoySummary envoySummary = EnvoySummary.newBuilder()
        .setResourceId("    ")
        .putLabels("os", "linux")
        .build();

    envoyRegistry.attach("t-1", "e-1", envoySummary,
        InetSocketAddress.createUnresolved("localhost", 60000), streamObserver
    ).join();
  }

  @Test
  public void testApplyBoundMonitors() {
    envoyRegistry.createTestingEntry("e-1");

    final UUID id1 = UUID.fromString("00000000-0000-0000-0001-000000000000");
    final UUID id2 = UUID.fromString("00000000-0000-0000-0002-000000000000");
    final UUID id3 = UUID.fromString("00000000-0000-0000-0003-000000000000");
    final UUID id4 = UUID.fromString("00000000-0000-0000-0004-000000000000");
    final UUID id5 = UUID.fromString("00000000-0000-0000-0005-000000000000");

    // baseline bound monitors
    {
      final List<BoundMonitorDTO> boundMonitors = Arrays.asList(
          new BoundMonitorDTO()
              .setMonitorId(id1)
              .setResourceTenant("t-1")
              .setResourceId("r-1")
              .setRenderedContent("{\"instance\":1, \"state\":1}"),
          new BoundMonitorDTO()
              .setMonitorId(id2)
              .setResourceTenant("t-1")
              .setResourceId("r-2")
              .setAgentType(AgentType.TELEGRAF)
              .setRenderedContent("{\"instance\":2, \"state\":1}"),
          new BoundMonitorDTO()
              .setMonitorId(id3)
              .setResourceTenant("t-1")
              .setZoneName("z-1")
              .setResourceId("r-3")
              .setRenderedContent("{\"instance\":3, \"state\":1}"),
          // monitor binding for another resource for the same tenant
          new BoundMonitorDTO()
              .setMonitorId(id3)
              .setZoneName("z-1")
              .setResourceTenant("t-1")
              .setResourceId("r-4")
              .setRenderedContent("{\"instance\":3, \"state\":1}")
      );

      final Map<OperationType, List<BoundMonitorDTO>> changes = envoyRegistry
          .applyBoundMonitors("e-1", boundMonitors);

      assertThat(changes, notNullValue());
      assertThat(changes.get(OperationType.CREATE), hasSize(4));
      assertThat(changes.get(OperationType.UPDATE), nullValue());
      assertThat(changes.get(OperationType.DELETE), nullValue());
    }

    // Exercise some changes
    {
      final List<BoundMonitorDTO> boundMonitors = Arrays.asList(
          // id1 MODIFIED
          new BoundMonitorDTO()
              .setMonitorId(id1)
              .setResourceTenant("t-1")
              .setResourceId("r-1")
              .setRenderedContent("{\"instance\":1, \"state\":2}"),
          // id2 REMOVED
          // id3, r-3 UNCHANGED
          new BoundMonitorDTO()
              .setMonitorId(id3)
              .setResourceTenant("t-1")
              .setZoneName("z-1")
              .setResourceId("r-3")
              .setRenderedContent("{\"instance\":3, \"state\":1}"),
          // id3, r-4 UNCHANGED
          new BoundMonitorDTO()
              .setMonitorId(id3)
              .setResourceTenant("t-1")
              .setZoneName("z-1")
              .setResourceId("r-4")
              .setRenderedContent("{\"instance\":3, \"state\":1}"),
          // id4, r-5 CREATED
          new BoundMonitorDTO()
              .setMonitorId(id4)
              .setResourceTenant("t-1")
              .setResourceId("r-5")
              .setRenderedContent("{\"instance\":4, \"state\":1}"),
          // id5, r-5 CREATED to confirm resource label tracked only once per binding event
          new BoundMonitorDTO()
              .setMonitorId(id5)
              .setResourceTenant("t-1")
              .setResourceId("r-5")
              .setRenderedContent("{\"instance\":5, \"state\":1}"),
          // id5, r-1 CREATED to confirm resource label re-tracked on this binding event
          new BoundMonitorDTO()
              .setMonitorId(id5)
              .setResourceTenant("t-1")
              .setResourceId("r-1")
              .setRenderedContent("{\"instance\":6, \"state\":1}")
  );

      final Map<OperationType, List<BoundMonitorDTO>> changes = envoyRegistry
          .applyBoundMonitors("e-1", boundMonitors);

      assertThat(changes, notNullValue());
      assertThat(changes.get(OperationType.CREATE), containsInAnyOrder(
          new BoundMonitorDTO()
              .setMonitorId(id4)
              .setResourceTenant("t-1")
              .setResourceId("r-5")
              .setRenderedContent("{\"instance\":4, \"state\":1}"),
          new BoundMonitorDTO()
              .setMonitorId(id5)
              .setResourceTenant("t-1")
              .setResourceId("r-5")
              .setRenderedContent("{\"instance\":5, \"state\":1}"),
          new BoundMonitorDTO()
              .setMonitorId(id5)
              .setResourceTenant("t-1")
              .setResourceId("r-1")
              .setRenderedContent("{\"instance\":6, \"state\":1}")
      ));
      assertThat(changes.get(OperationType.UPDATE), contains(
          new BoundMonitorDTO()
              .setMonitorId(id1)
              .setResourceTenant("t-1")
              .setResourceId("r-1")
              .setRenderedContent("{\"instance\":1, \"state\":2}")
      ));
      assertThat(changes.get(OperationType.DELETE), contains(
          new BoundMonitorDTO()
              .setMonitorId(id2)
              .setResourceTenant("t-1")
              .setResourceId("r-2")
              .setAgentType(AgentType.TELEGRAF)
              // rendered content is not used by envoy, but needs to be non-null for gRPC
              .setRenderedContent("")
      ));
    }

    verify(resourceLabelsService, times(2)).trackResource("t-1", "r-1");
    verify(resourceLabelsService).trackResource("t-1", "r-2");
    verify(resourceLabelsService).trackResource("t-1", "r-3");
    verify(resourceLabelsService).trackResource("t-1", "r-4");
    verify(resourceLabelsService).trackResource("t-1", "r-5");
    verify(resourceLabelsService).releaseResource("t-1", "r-2");

    verifyNoMoreInteractions(resourceLabelsService);
  }
}