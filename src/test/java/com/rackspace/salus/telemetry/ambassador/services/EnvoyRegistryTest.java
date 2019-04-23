package com.rackspace.salus.telemetry.ambassador.services;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rackspace.salus.monitor_management.web.model.BoundMonitorDTO;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstruction;
import com.rackspace.salus.services.TelemetryEdge.EnvoySummary;
import com.rackspace.salus.telemetry.ambassador.config.AmbassadorProperties;
import com.rackspace.salus.telemetry.ambassador.config.GrpcConfig;
import com.rackspace.salus.telemetry.ambassador.types.ZoneNotAuthorizedException;
import com.rackspace.salus.telemetry.etcd.EtcdUtils;
import com.rackspace.salus.telemetry.etcd.services.EnvoyLabelManagement;
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
  EnvoyLabelManagement envoyLabelManagement;

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

  @Mock
  StreamObserver<EnvoyInstruction> streamObserver;

  @Autowired
  EnvoyRegistry envoyRegistry;

  @Test
  public void postsAttachEventOnAttach() throws StatusException {
    final EnvoySummary envoySummary = EnvoySummary.newBuilder()
        .setResourceId("hostname:test-host")
        .putLabels("discovered.os", "linux")
        .build();

    final CompletableFuture<Long> assignedLease = CompletableFuture.completedFuture(1234L);
    when(envoyLeaseTracking.grant(any()))
        .thenReturn(assignedLease);

    when(envoyLabelManagement.registerAndSpreadEnvoy(any(), any(), any(), anyLong(), any(), any()))
        .then(invocationOnMock -> EtcdUtils.completedPutResponse());

    when(envoyLabelManagement.pullAgentInstallsForEnvoy(any(), any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(0));

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
                .setLabels(Collections.singletonMap("agent.discovered.os", "linux"))
                .setEnvoyId("e-1")
                .setTenantId("t-1")
                .setEnvoyAddress("localhost")
        );
  }

  @Test
  public void storesZoneOnAttach() throws StatusException, ZoneNotAuthorizedException {
    final EnvoySummary envoySummary = EnvoySummary.newBuilder()
        .setResourceId("hostname:test-host")
        .putLabels("discovered.os", "linux")
        .setZone("z-1")
        .build();

    final CompletableFuture<Long> assignedLease = CompletableFuture.completedFuture(1234L);
    when(envoyLeaseTracking.grant(any()))
        .thenReturn(assignedLease);

    when(envoyLabelManagement.registerAndSpreadEnvoy(any(), any(), any(), anyLong(), any(), any()))
        .then(invocationOnMock -> EtcdUtils.completedPutResponse());

    when(envoyLabelManagement.pullAgentInstallsForEnvoy(any(), any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(0));

    when(envoyResourceManagement.registerResource(any(), any(), anyLong(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(new ResourceInfo()));

    final ResolvedZone resolvedZone = new ResolvedZone()
        .setId("z-1")
        .setTenantId("t-1");
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
                .setLabels(Collections.singletonMap("agent.discovered.os", "linux"))
                .setEnvoyId("e-1")
                .setTenantId("t-1")
                .setEnvoyAddress("localhost")
        );
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

    final UUID id1 = UUID.fromString("16caf730-48e8-47ba-0001-aa9babba8953");
    final UUID id2 = UUID.fromString("16caf730-48e8-47ba-0002-aa9babba8953");
    final UUID id3 = UUID.fromString("16caf730-48e8-47ba-0003-aa9babba8953");
    final UUID id4 = UUID.fromString("16caf730-48e8-47ba-0004-aa9babba8953");

    {
      final List<BoundMonitorDTO> boundMonitors = Arrays.asList(
          new BoundMonitorDTO()
              .setMonitorId(id1)
              .setRenderedContent("{\"instance\":1, \"state\":1}"),
          new BoundMonitorDTO()
              .setMonitorId(id2)
              .setResourceId("r-2")
              .setAgentType(AgentType.TELEGRAF)
              .setRenderedContent("{\"instance\":2, \"state\":1}"),
          new BoundMonitorDTO()
              .setMonitorId(id3)
              .setTargetTenant("t-1")
              .setZoneId("z-1")
              .setResourceId("r-3")
              .setRenderedContent("{\"instance\":3, \"state\":1}"),
          // monitor binding for another resource for the same tenant
          new BoundMonitorDTO()
              .setMonitorId(id3)
              .setTargetTenant("t-1")
              .setZoneId("z-1")
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

    {
      // Exercise some changes
      final List<BoundMonitorDTO> boundMonitors = Arrays.asList(
          // #1 MODIFIED
          new BoundMonitorDTO()
              .setMonitorId(id1)
              .setRenderedContent("{\"instance\":1, \"state\":2}"),
          // #2 REMOVED
          // #3 UNCHANGED for both resources
          new BoundMonitorDTO()
              .setMonitorId(id3)
              .setTargetTenant("t-1")
              .setZoneId("z-1")
              .setResourceId("r-3")
              .setRenderedContent("{\"instance\":3, \"state\":1}"),
          // monitor binding for another resource for the same tenant
          new BoundMonitorDTO()
              .setMonitorId(id3)
              .setTargetTenant("t-1")
              .setZoneId("z-1")
              .setResourceId("r-4")
              .setRenderedContent("{\"instance\":3, \"state\":1}"),
          // #4 CREATED
          new BoundMonitorDTO()
              .setMonitorId(id4)
              .setRenderedContent("{\"instance\":4, \"state\":1}")
      );

      final Map<OperationType, List<BoundMonitorDTO>> changes = envoyRegistry
          .applyBoundMonitors("e-1", boundMonitors);

      assertThat(changes, notNullValue());
      assertThat(changes.get(OperationType.CREATE), hasSize(1));
      assertThat(changes.get(OperationType.CREATE), hasItem(hasProperty("monitorId", equalTo(id4))));
      assertThat(changes.get(OperationType.UPDATE), hasSize(1));
      assertThat(changes.get(OperationType.UPDATE), hasItem(hasProperty("monitorId", equalTo(id1))));
      assertThat(changes.get(OperationType.DELETE), hasSize(1));
      final List<BoundMonitorDTO> deleted = changes.get(OperationType.DELETE);
      assertThat(deleted.get(0).getAgentType(), equalTo(AgentType.TELEGRAF));
      assertThat(deleted.get(0).getMonitorId(), equalTo(id2));
      assertThat(deleted.get(0).getResourceId(), equalTo("r-2"));
    }


  }
}