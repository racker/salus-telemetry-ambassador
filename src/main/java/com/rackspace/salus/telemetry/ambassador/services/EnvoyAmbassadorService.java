/*
 *    Copyright 2018 Rackspace US, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *
 */

package com.rackspace.salus.telemetry.ambassador.services;

import com.rackspace.salus.services.TelemetryAmbassadorGrpc;
import com.rackspace.salus.services.TelemetryEdge;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.SocketAddress;
import lombok.extern.slf4j.Slf4j;
import org.lognet.springboot.grpc.GRpcService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;

@GRpcService
@Slf4j
public class EnvoyAmbassadorService extends TelemetryAmbassadorGrpc.TelemetryAmbassadorImplBase {
    private final EnvoyRegistry envoyRegistry;
    private final LogEventRouter logEventRouter;
    private final MetricRouter metricRouter;
    private final TaskExecutor taskExecutor;

    // metrics counters
    private final Counter envoyAttach;
    private final Counter messagesPost;
    private final Counter postLog;
    private final Counter keepAlive;


    @Autowired
    public EnvoyAmbassadorService(EnvoyRegistry envoyRegistry,
                                  LogEventRouter logEventRouter,
                                  MetricRouter metricRouter,
                                  TaskExecutor taskExecutor,
                                  MeterRegistry meterRegistry) {
        this.envoyRegistry = envoyRegistry;
        this.logEventRouter = logEventRouter;
        this.metricRouter = metricRouter;
        this.taskExecutor = taskExecutor;

        envoyAttach = meterRegistry.counter("messages","operation", "attach");
        postLog = meterRegistry.counter("messages","operation", "postLog");
        messagesPost = meterRegistry.counter("messages","operation", "postMetric");
        keepAlive = meterRegistry.counter("messages","operation", "keepAlive");
    }

    @Override
    public void attachEnvoy(TelemetryEdge.EnvoySummary request, StreamObserver<TelemetryEdge.EnvoyInstruction> responseObserver) {
        final SocketAddress remoteAddr = GrpcContextDetails.getCallerRemoteAddress();
        final String envoyId = GrpcContextDetails.getCallerEnvoyId();

        registerCancelHandler(envoyId, remoteAddr, responseObserver);
        envoyAttach.increment();
        try {
            envoyRegistry.attach(GrpcContextDetails.getCallerTenantId(), envoyId, request, remoteAddr, responseObserver);
        } catch (StatusException e) {
            responseObserver.onError(e);
        }
    }

    private void registerCancelHandler(String instanceId, SocketAddress remoteAddr, StreamObserver<TelemetryEdge.EnvoyInstruction> responseObserver) {
        if (responseObserver instanceof ServerCallStreamObserver) {
            ((ServerCallStreamObserver<TelemetryEdge.EnvoyInstruction>) responseObserver).setOnCancelHandler(() -> {
                // run async to avoid cross-interactions with the etcd operations that also use grpc
                taskExecutor.execute(() -> {
                    log.info("Removing cancelled envoy={} address={}", instanceId, remoteAddr);
                    try {
                        envoyRegistry.remove(instanceId);
                    } catch (Exception e) {
                        log.warn("Trying to remove envoy={} from registry", instanceId, e);
                    }
                });
            });
        }
    }

    @Override
    public void postLogEvent(TelemetryEdge.LogEvent request,
                             StreamObserver<TelemetryEdge.PostLogEventResponse> responseObserver) {
        final String envoyId = GrpcContextDetails.getCallerEnvoyId();

        postLog.increment();
        try {
            logEventRouter.route(GrpcContextDetails.getCallerTenantId(), envoyId, request);
            responseObserver.onNext(TelemetryEdge.PostLogEventResponse.newBuilder().build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.warn("Failed to route log event, notifying Envoy", e);
            responseObserver.onError(new StatusException(Status.fromThrowable(e)));
        }
    }

    @Override
    public void postMetric(TelemetryEdge.PostedMetric request,
                           StreamObserver<TelemetryEdge.PostMetricResponse> responseObserver) {
        final String envoyId = GrpcContextDetails.getCallerEnvoyId();

        messagesPost.increment();
        try {
            metricRouter.route(GrpcContextDetails.getCallerTenantId(), envoyId, request);
            responseObserver.onNext(TelemetryEdge.PostMetricResponse.newBuilder().build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.warn("Failed to route metric, notifying Envoy", e);
            responseObserver.onError(new StatusException(Status.fromThrowable(e)));
        }
    }

    @Override
    public void keepAlive(TelemetryEdge.KeepAliveRequest request, StreamObserver<TelemetryEdge.KeepAliveResponse> responseObserver) {
        final SocketAddress remoteAddr = GrpcContextDetails.getCallerRemoteAddress();
        final String envoyId = GrpcContextDetails.getCallerEnvoyId();

        keepAlive.increment();
        log.trace("Processing keep alive for envoyId={}", envoyId);

        if (envoyRegistry.keepAlive(envoyId, remoteAddr)) {
            responseObserver.onNext(TelemetryEdge.KeepAliveResponse.newBuilder().build());
            responseObserver.onCompleted();
        } else {
            log.warn("Failed keep alive due to unknown envoyId={}", envoyId);
            responseObserver.onError(new StatusException(Status.INVALID_ARGUMENT));
        }
    }
}
