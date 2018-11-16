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

package com.rackspace.salus.telemetry.ambassador;

import com.rackspace.salus.services.TelemetryAmbassadorGrpc;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.telemetry.ambassador.services.GrpcContextDetails;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.lognet.springboot.grpc.GRpcService;
import org.springframework.context.annotation.Profile;

@Profile("test")
@GRpcService
@Slf4j
public class MockAmbassadorService extends TelemetryAmbassadorGrpc.TelemetryAmbassadorImplBase {
    @Data
    public static class AttachCall {
        final String tenantId;
        final TelemetryEdge.EnvoySummary request;
    }

    // needs to be synchronized since the server could invoke attaches concurrently
    private List<AttachCall> attachCalls = Collections.synchronizedList(new ArrayList<>());
    private List<TelemetryEdge.EnvoyInstruction> instructionsToProvide = new ArrayList<>();

    public List<AttachCall> getAttachCalls() {
        return attachCalls;
    }

    public void addInstructionToProvide(TelemetryEdge.EnvoyInstruction instruction) {
        instructionsToProvide.add(instruction);
    }

    @Override
    public void attachEnvoy(TelemetryEdge.EnvoySummary request, StreamObserver<TelemetryEdge.EnvoyInstruction> responseObserver) {
        attachCalls.add(new AttachCall(GrpcContextDetails.getCallerTenantId(), request));

        for (TelemetryEdge.EnvoyInstruction instruction : instructionsToProvide) {
            responseObserver.onNext(instruction);
        }
        responseObserver.onCompleted();
    }
}
