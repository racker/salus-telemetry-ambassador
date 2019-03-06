package com.rackspace.salus.telemetry.ambassador.services;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rackspace.salus.services.TelemetryEdge.EnvoyInstruction;
import com.rackspace.salus.services.TelemetryEdge.EnvoySummary;
import com.rackspace.salus.telemetry.ambassador.config.AmbassadorProperties;
import com.rackspace.salus.telemetry.ambassador.config.GrpcConfig;
import com.rackspace.salus.telemetry.etcd.EtcdUtils;
import com.rackspace.salus.telemetry.etcd.services.EnvoyLabelManagement;
import com.rackspace.salus.telemetry.etcd.services.EnvoyLeaseTracking;
import com.rackspace.salus.telemetry.etcd.services.EnvoyResourceManagement;
import com.rackspace.salus.telemetry.messaging.AttachEvent;
import com.rackspace.salus.telemetry.model.ResourceInfo;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@JsonTest
@Import({
    EnvoyRegistry.class,
    AmbassadorProperties.class,
    LabelRulesProcessor.class,
    GrpcConfig.class
})
public class EnvoyRegistryTest {

  @MockBean
  EnvoyLabelManagement envoyLabelManagement;

  @MockBean
  EnvoyLeaseTracking envoyLeaseTracking;

  @MockBean
  EnvoyResourceManagement envoyResourceManagement;

  @MockBean
  KafkaTemplate kafkaTemplate;

  @Mock
  StreamObserver<EnvoyInstruction> streamObserver;

  @Autowired
  EnvoyRegistry envoyRegistry;

  @Test
  public void postsAttachEventOnAttach() throws StatusException {
    final EnvoySummary envoySummary = EnvoySummary.newBuilder()
        .setResourceId("hostname:test-host")
        .putLabels("os", "linux")
        .build();

    final CompletableFuture<Long> assignedLease = CompletableFuture.completedFuture(1234L);
    when(envoyLeaseTracking.grant(any()))
        .thenReturn(assignedLease);

    // using doReturn since return type is a wildcard capture
    when(envoyLabelManagement.registerAndSpreadEnvoy(any(), any(), any(), anyLong(), any(), any()))
        .then(invocationOnMock -> EtcdUtils.completedPutResponse());

    when(envoyLabelManagement.pullAgentInstallsForEnvoy(any(), any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(0));

    when(envoyLabelManagement.pullConfigsForEnvoy(any(), any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(0));

    when(envoyResourceManagement.registerResource(any(), any(), anyLong(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(new ResourceInfo()));

    envoyRegistry.attach("t-1", "e-1", envoySummary,
        InetSocketAddress.createUnresolved("localhost", 60000), streamObserver
    ).join();

    verify(kafkaTemplate)
        .send(
            "telemetry.attaches.json",
            "t-1:hostname:test-host",
            new AttachEvent()
                .setResourceId("hostname:test-host")
                .setLabels(Collections.singletonMap("os", "LINUX"))
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
}