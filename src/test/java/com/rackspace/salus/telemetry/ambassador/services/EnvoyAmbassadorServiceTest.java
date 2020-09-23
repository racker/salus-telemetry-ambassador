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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstruction;
import com.rackspace.salus.services.TelemetryEdge.EnvoySummary;
import com.rackspace.salus.services.TelemetryEdge.Metric;
import com.rackspace.salus.services.TelemetryEdge.NameTagValueMetric;
import com.rackspace.salus.services.TelemetryEdge.PostTestMonitorResultsResponse;
import com.rackspace.salus.services.TelemetryEdge.TestMonitorResults;
import com.rackspace.salus.telemetry.repositories.AgentHistoryRepository;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest
public class EnvoyAmbassadorServiceTest {

  @Configuration
  @Import({EnvoyAmbassadorService.class})
  public static class TestConfig {

    @Bean
    public TaskExecutor taskExecutor() {
      return new SyncTaskExecutor();
    }

    @Bean
    public MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  @Autowired
  EnvoyAmbassadorService envoyAmbassadorService;

  @MockBean
  EnvoyRegistry envoyRegistry;

  @MockBean
  LogEventRouter logEventRouter;

  @MockBean
  MetricRouter metricRouter;

  @MockBean
  TestMonitorResultsProducer testMonitorResultsProducer;

  @MockBean
  AgentHistoryRepository agentHistoryRepository;

  @Test
  public void testAttach_success() throws StatusException {
    @SuppressWarnings("unchecked") final StreamObserver<EnvoyInstruction> respStreamObserver =
        mock(StreamObserver.class);

    when(envoyRegistry.attach(any(), any(), any(), any(), any()))
        // complete normally
        .thenReturn(CompletableFuture.completedFuture(null));

    EnvoySummary envoySummary = TelemetryEdge.EnvoySummary.newBuilder()
        .setResourceId("r-1")
        .addSupportedAgents(TelemetryEdge.AgentType.TELEGRAF)
        .putLabels("os", "linux")
        .build();

    final InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 33333);
    GrpcContextDetails.contextForTesting("t-1", "e-1", remoteAddress)
        .run(() -> {
          // EXECUTE
          envoyAmbassadorService.attachEnvoy(envoySummary, respStreamObserver);
        });

    verify(envoyRegistry).attach("t-1", "e-1", envoySummary, remoteAddress, respStreamObserver);

    verifyNoMoreInteractions(envoyRegistry, respStreamObserver);
  }

  @Test
  public void testAttach_statusException() throws StatusException {
    @SuppressWarnings("unchecked") final StreamObserver<EnvoyInstruction> respStreamObserver =
        mock(StreamObserver.class);

    when(envoyRegistry.attach(any(), any(), any(), any(), any()))
        // throw a StatusException
        .thenThrow(new StatusException(Status.NOT_FOUND));

    EnvoySummary envoySummary = TelemetryEdge.EnvoySummary.newBuilder()
        .setResourceId("r-1")
        .addSupportedAgents(TelemetryEdge.AgentType.TELEGRAF)
        .putLabels("os", "linux")
        .build();

    final InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 33333);
    GrpcContextDetails.contextForTesting("t-1", "e-1", remoteAddress)
        .run(() -> {
          // EXECUTE
          envoyAmbassadorService.attachEnvoy(envoySummary, respStreamObserver);
        });

    verify(envoyRegistry).attach("t-1", "e-1", envoySummary, remoteAddress, respStreamObserver);

    verify(respStreamObserver).onError(argThat(throwable -> {
      assertThat(throwable).isInstanceOf(StatusException.class);
      assertThat(((StatusException) throwable).getStatus()).isEqualTo(Status.NOT_FOUND);
      return true;
    }));

    verifyNoMoreInteractions(envoyRegistry, respStreamObserver);
  }

  @Test
  public void testAttach_nonStatusException() throws StatusException {
    @SuppressWarnings("unchecked") final StreamObserver<EnvoyInstruction> respStreamObserver =
        mock(StreamObserver.class);

    when(envoyRegistry.attach(any(), any(), any(), any(), any()))
        // throw an "unexpected" exception
        .thenThrow(new IllegalStateException("just a test"));

    EnvoySummary envoySummary = TelemetryEdge.EnvoySummary.newBuilder()
        .setResourceId("r-1")
        .addSupportedAgents(TelemetryEdge.AgentType.TELEGRAF)
        .putLabels("os", "linux")
        .build();

    final InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 33333);
    GrpcContextDetails.contextForTesting("t-1", "e-1", remoteAddress)
        .run(() -> {
          // EXECUTE
          envoyAmbassadorService.attachEnvoy(envoySummary, respStreamObserver);
        });

    verify(envoyRegistry).attach("t-1", "e-1", envoySummary, remoteAddress, respStreamObserver);

    verify(respStreamObserver).onError(argThat(throwable -> {
      assertThat(throwable).isInstanceOf(StatusException.class);
      assertThat(((StatusException) throwable).getStatus().getCode()).isEqualTo(Code.UNKNOWN);
      assertThat(((StatusException) throwable).getStatus().getDescription()).isEqualTo("Unknown error occurred: just a test");
      return true;
    }));

    verifyNoMoreInteractions(envoyRegistry, respStreamObserver);
  }

  @Test
  public void testPostTestMonitorResults_success() {
    @SuppressWarnings("unchecked") final StreamObserver<PostTestMonitorResultsResponse> respStreamObserver = mock(
        StreamObserver.class);

    final TestMonitorResults expectedResults = TestMonitorResults.newBuilder()
        .setCorrelationId("id-1")
        .addErrors("error-1")
        .addMetrics(Metric.newBuilder()
            .setNameTagValue(
                NameTagValueMetric.newBuilder()
                    .setName("metric name")
                    .putIvalues("count", 4)
                    .build()
            )
            .build())
        .build();

    envoyAmbassadorService.postTestMonitorResults(
        expectedResults,
        respStreamObserver
    );

    verify(testMonitorResultsProducer).send(expectedResults);

    verify(respStreamObserver).onNext(notNull());
    verify(respStreamObserver).onCompleted();

    verifyNoMoreInteractions(testMonitorResultsProducer, respStreamObserver);
  }

  @Test
  public void testPostTestMonitorResults_exceptionDuringHandler() {

    doThrow(new IllegalStateException("fake failure"))
        .when(testMonitorResultsProducer)
        .send(any());

    @SuppressWarnings("unchecked")
    final StreamObserver<PostTestMonitorResultsResponse> respStreamObserver = mock(
        StreamObserver.class);

    final TestMonitorResults expectedResults = TestMonitorResults.newBuilder()
        .setCorrelationId("id-1")
        .addErrors("error-1")
        .addMetrics(Metric.newBuilder()
            .setNameTagValue(
                NameTagValueMetric.newBuilder()
                    .setName("metric name")
                    .putIvalues("count", 4)
                    .build()
            )
            .build())
        .build();

    envoyAmbassadorService.postTestMonitorResults(
        expectedResults,
        respStreamObserver
    );

    verify(testMonitorResultsProducer).send(expectedResults);

    // still does the same onNext/complete without an error to avoid circular feedback
    verify(respStreamObserver).onNext(notNull());
    verify(respStreamObserver).onCompleted();

    verifyNoMoreInteractions(testMonitorResultsProducer, respStreamObserver);
  }
}