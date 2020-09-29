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

import com.rackspace.salus.services.TelemetryAmbassadorGrpc.TelemetryAmbassadorImplBase;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstruction;
import com.rackspace.salus.services.TelemetryEdge.EnvoySummary;
import com.rackspace.salus.services.TelemetryEdge.KeepAliveRequest;
import com.rackspace.salus.services.TelemetryEdge.KeepAliveResponse;
import com.rackspace.salus.services.TelemetryEdge.LogEvent;
import com.rackspace.salus.services.TelemetryEdge.PostLogEventResponse;
import com.rackspace.salus.services.TelemetryEdge.PostMetricResponse;
import com.rackspace.salus.services.TelemetryEdge.PostTestMonitorResultsResponse;
import com.rackspace.salus.services.TelemetryEdge.PostedMetric;
import com.rackspace.salus.services.TelemetryEdge.TestMonitorResults;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.lognet.springboot.grpc.GRpcService;
import org.springframework.beans.factory.annotation.Autowired;

@GRpcService
@Slf4j
public class EnvoyAmbassadorService extends TelemetryAmbassadorImplBase {
    private final EnvoyRegistry envoyRegistry;
    private final LogEventRouter logEventRouter;
    private final MetricRouter metricRouter;
    private final TestMonitorResultsProducer testMonitorResultsProducer;
    private final AgentHistoryService agentHistoryService;

    // metrics counters
    private final Counter envoyAttach;
    private final Counter messagesPost;
    private final Counter postLog;
    private final Counter keepAlive;
    private final Counter exceptions;
    private final Timer attachDuration;


    @Autowired
    public EnvoyAmbassadorService(EnvoyRegistry envoyRegistry,
                                  LogEventRouter logEventRouter,
                                  MetricRouter metricRouter,
                                  TestMonitorResultsProducer testMonitorResultsProducer,
                                  MeterRegistry meterRegistry,
                                  AgentHistoryService agentHistoryService) {
        this.envoyRegistry = envoyRegistry;
        this.logEventRouter = logEventRouter;
        this.metricRouter = metricRouter;
        this.testMonitorResultsProducer = testMonitorResultsProducer;
        this.agentHistoryService = agentHistoryService;

        envoyAttach = meterRegistry.counter("messages","operation", "attach");
        attachDuration = meterRegistry.timer("attachDuration");
        postLog = meterRegistry.counter("messages","operation", "postLog");
        messagesPost = meterRegistry.counter("messages","operation", "postMetric");
        keepAlive = meterRegistry.counter("messages","operation", "keepAlive");
        exceptions = meterRegistry.counter("errors", "cause", "unhandledException");
    }

    @Override
    public void attachEnvoy(EnvoySummary request, StreamObserver<EnvoyInstruction> responseObserver) {
        final SocketAddress remoteAddr = GrpcContextDetails.getCallerRemoteAddress();
        final String envoyId = GrpcContextDetails.getCallerEnvoyId();
        final String tenantId = GrpcContextDetails.getCallerTenantId();
        final String resourceId = request.getResourceId();
        final String zoneId = request.getZone();

        final Instant attachStartTime = Instant.now();

        registerCancelHandler(tenantId, resourceId, envoyId, remoteAddr, responseObserver);
        envoyAttach.increment();
        try {
            envoyRegistry.attach(
                tenantId, envoyId, request, remoteAddr, responseObserver
            )
                .thenApply(o -> {
                    attachDuration.record(Duration.between(attachStartTime, Instant.now()));
                    return o;
                })
                .join();
            agentHistoryService.addAgentHistory(request, attachStartTime);
        } catch (StatusException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            log.error("Unhandled exception occurred in resourceId={} envoy={} attach for tenant={}",
                resourceId, envoyId, tenantId, e);
            exceptions.increment();
            responseObserver.onError(
                new StatusException(Status.UNKNOWN
                    .withDescription("Unknown error occurred: "+e.getMessage()))
            );
        }
    }

    private void registerCancelHandler(String tenantId, String resourceId, String instanceId,
                                       SocketAddress remoteAddr,
                                       StreamObserver<EnvoyInstruction> responseObserver) {
        if (responseObserver instanceof ServerCallStreamObserver) {
            ((ServerCallStreamObserver<EnvoyInstruction>) responseObserver).setOnCancelHandler(() -> {
                log.info("Removing cancelled resourceId={} envoy={} for tenant={} at address={}",
                    resourceId, instanceId, tenantId, remoteAddr);
                try {
                    envoyRegistry.remove(instanceId);
                    agentHistoryService.addEnvoyConnectionClosedTime(tenantId, instanceId);
                } catch (Exception e) {
                    log.warn("Trying to remove resourceId={} envoy={} for tenant={} from registry",
                        resourceId, instanceId, tenantId, e);
                }
            });
        }
    }


    @Override
    public void postLogEvent(LogEvent request,
                             StreamObserver<PostLogEventResponse> responseObserver) {
        final String envoyId = GrpcContextDetails.getCallerEnvoyId();

        postLog.increment();
        try {
            logEventRouter.route(GrpcContextDetails.getCallerTenantId(), envoyId, request);
            responseObserver.onNext(PostLogEventResponse.newBuilder().build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.warn("Failed to route log event, notifying Envoy", e);
            responseObserver.onError(new StatusException(Status.fromThrowable(e)));
        }
    }

    @Override
    public void postMetric(PostedMetric request,
                           StreamObserver<PostMetricResponse> responseObserver) {
        final String envoyId = GrpcContextDetails.getCallerEnvoyId();

        messagesPost.increment();
        try {
            metricRouter.route(GrpcContextDetails.getCallerTenantId(), envoyId, request);
            responseObserver.onNext(PostMetricResponse.newBuilder().build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.warn("Failed to route metric, notifying Envoy", e);
            responseObserver.onError(new StatusException(Status.fromThrowable(e)));
        }
    }

    @Override
    public void postTestMonitorResults(TestMonitorResults results,
                                       StreamObserver<PostTestMonitorResultsResponse> responseObserver) {

        try {
            testMonitorResultsProducer.send(results);
        } catch (Exception e) {
            log.warn("Failed to send test-monitor results", e);
            // This one purposely doesn't notify the envoy since it was actually the ambassador
            // that initiated the whole operation and the feedback loop would be strange.
        } finally {
            responseObserver.onNext(PostTestMonitorResultsResponse.newBuilder().build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void keepAlive(KeepAliveRequest request, StreamObserver<KeepAliveResponse> responseObserver) {
        final SocketAddress remoteAddr = GrpcContextDetails.getCallerRemoteAddress();
        final String envoyId = GrpcContextDetails.getCallerEnvoyId();

        keepAlive.increment();
        log.trace("Processing keep alive for envoyId={}", envoyId);

        if (envoyRegistry.keepAlive(envoyId, remoteAddr)) {
            responseObserver.onNext(KeepAliveResponse.newBuilder().build());
            responseObserver.onCompleted();
        } else {
            // Can happen just after ambassador restart when the envoy TCP connection is held
            // open by the load balancer. The onError will trigger the Envoy to tear down
            // connection and re-connect.
            log.warn("Failed to process keep alive due to unknown envoyId={}", envoyId);
            responseObserver.onError(new StatusException(Status.INVALID_ARGUMENT));
        }
    }
}
