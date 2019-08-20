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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.rackspace.salus.services.TelemetryEdge.Metric;
import com.rackspace.salus.services.TelemetryEdge.NameTagValueMetric;
import com.rackspace.salus.services.TelemetryEdge.PostTestMonitorResultsResponse;
import com.rackspace.salus.services.TelemetryEdge.TestMonitorResults;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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