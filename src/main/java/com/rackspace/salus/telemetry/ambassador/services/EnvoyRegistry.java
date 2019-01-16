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

import static com.rackspace.salus.common.messaging.KafkaMessageKeyBuilder.buildMessageKey;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstruction;
import com.rackspace.salus.services.TelemetryEdge.EnvoySummary;
import com.rackspace.salus.telemetry.ambassador.config.AmbassadorProperties;
import com.rackspace.salus.telemetry.etcd.services.EnvoyLabelManagement;
import com.rackspace.salus.telemetry.etcd.services.EnvoyLeaseTracking;
import com.rackspace.salus.telemetry.etcd.services.EnvoyResourceManagement;
import com.rackspace.salus.telemetry.messaging.AttachEvent;
import com.rackspace.salus.telemetry.messaging.KafkaMessageType;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class EnvoyRegistry {

    private final AmbassadorProperties appProperties;
    private final EnvoyLabelManagement envoyLabelManagement;
    private final EnvoyLeaseTracking envoyLeaseTracking;
    private final EnvoyResourceManagement envoyResourceManagement;
    private final LabelRulesProcessor labelRulesProcessor;
    private final JsonFormat.Printer jsonPrinter;
    private final KafkaTemplate<String,Object> kafkaTemplate;

    @Data
    static class EnvoyEntry {
        final StreamObserver<TelemetryEdge.EnvoyInstruction> instructionStream;
        final Map<String,String> labels;
    }

    private ConcurrentHashMap<String, EnvoyEntry> envoys = new ConcurrentHashMap<>();

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    @Autowired
    public EnvoyRegistry(AmbassadorProperties appProperties,
                         EnvoyLabelManagement envoyLabelManagement,
                         EnvoyLeaseTracking envoyLeaseTracking,
                         EnvoyResourceManagement envoyResourceManagement,
                         LabelRulesProcessor labelRulesProcessor,
                         JsonFormat.Printer jsonPrinter,
                         KafkaTemplate<String,Object> kafkaTemplate) {
        this.appProperties = appProperties;
        this.envoyLabelManagement = envoyLabelManagement;
        this.envoyLeaseTracking = envoyLeaseTracking;
        this.envoyResourceManagement = envoyResourceManagement;
        this.labelRulesProcessor = labelRulesProcessor;
        this.jsonPrinter = jsonPrinter;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Executed whenever we receive a new connection from an envoy.
     *
     * @param tenantId
     * @param envoyId
     * @param envoySummary
     * @param remoteAddr
     * @param instructionStreamObserver
     * @return a {@link CompletableFuture} of the lease ID granted to the attached Envoy
     * @throws StatusException
     */
    public CompletableFuture<?> attach(String tenantId, String envoyId, EnvoySummary envoySummary,
                                            SocketAddress remoteAddr, StreamObserver<EnvoyInstruction> instructionStreamObserver)
                throws StatusException {

        if (StringUtils.isEmpty(envoyId)) {
            log.warn("Envoy attachment from remoteAddr={} is missing tenantId", remoteAddr);
            throw new StatusException(Status.INVALID_ARGUMENT.withDescription("tenantId is missing from request"));
        }

        final Map<String, String> envoyLabels;
        try {
            envoyLabels = labelRulesProcessor.process(envoySummary.getLabelsMap());
        } catch (IllegalArgumentException e) {
            throw new StatusException(Status.INVALID_ARGUMENT.withDescription(e.getMessage()));
        }
        final List<String> supportedAgentTypes = convertToStrings(envoySummary.getSupportedAgentsList());
        final String identifierName = envoySummary.getIdentifierName();

        if (!envoyLabels.containsKey(identifierName)) {
            throw new StatusException(Status.INVALID_ARGUMENT.withDescription(
                    String.format("%s is not a valid value for the identifierName",
                    identifierName)));
        }

        EnvoyEntry existingEntry = envoys.get(envoyId);
        if (existingEntry != null) {
            log.warn("Saw re-attachment of same envoy id={}, so aborting the previous stream to solve race condition",
                    envoyId);
            existingEntry.instructionStream.onError(new StatusException(Status.ABORTED.withDescription("Reconnect seen from same envoyId")));
            envoyLeaseTracking.revoke(envoyId);
        }

        log.info("Attaching envoy tenantId={}, envoyId={} from remoteAddr={} with identifierName={}, labels={}, supports agents={}",
            tenantId, envoyId, remoteAddr, identifierName, envoyLabels, supportedAgentTypes);

        return envoyLeaseTracking.grant(envoyId)
            .thenCompose(leaseId -> {

                try {

                    final String summaryAsJson = jsonPrinter.print(envoySummary);

                    return envoyLabelManagement.registerAndSpreadEnvoy(
                        tenantId, envoyId, summaryAsJson, leaseId,
                        envoyLabels, supportedAgentTypes
                    )
                        .thenApply(o -> leaseId);

                } catch (InvalidProtocolBufferException e) {
                    throw new RuntimeException("Failed to spread envoy", e);
                }

            })
            .thenApply(leaseId -> {
                envoys.put(envoyId, new EnvoyEntry(instructionStreamObserver, envoyLabels));
                return leaseId;
            })
            .thenCompose(leaseId ->
                envoyLabelManagement.pullAgentInstallsForEnvoy(tenantId, envoyId, leaseId, supportedAgentTypes, envoyLabels)
                    .thenApply(agentInstallCount -> {
                        log.debug("Pulled agent installs count={} for tenant={}, envoy={}",
                            agentInstallCount, tenantId, envoyId);
                        return leaseId;
                    })
            )
            .thenCompose(leaseId ->
                envoyLabelManagement.pullConfigsForEnvoy(tenantId, envoyId, leaseId, supportedAgentTypes, envoyLabels)
                .thenApply(configCount -> {
                    log.debug("Pulled configs count={} for tenant={}, envoy={}",
                        configCount, tenantId, envoyId);
                    return leaseId;
                })
            )
            .thenCompose(leaseId ->
                envoyResourceManagement.registerResource(tenantId, envoyId, leaseId, identifierName, envoyLabels, remoteAddr)
                .thenApply(putResponse -> {
                    log.debug("Registered new envoy resource for presence monitoring for " +
                            "tenant={}, envoyId={}, identifierName={}:{}",
                            tenantId, envoyId, identifierName, envoyLabels.get(identifierName));
                    return leaseId;
                })
            )
            .thenApply(leaseId ->  {
                postAttachEvent(tenantId, envoyId, envoySummary, envoyLabels, remoteAddr);
                return leaseId;
            });

    }

    private void postAttachEvent(String tenantId, String envoyId, EnvoySummary envoySummary,
                                 Map<String, String> envoyLabels,
                                 SocketAddress remoteAddr) {

        final String identifierName = envoySummary.getIdentifierName();
        final AttachEvent attachEvent = new AttachEvent()
            .setTenantId(tenantId)
            .setEnvoyId(envoyId)
            .setIdentifierName(identifierName)
            .setIdentifierValue(envoyLabels.get(identifierName))
            .setEnvoyAddress(((InetSocketAddress) remoteAddr).getHostString())
            .setLabels(envoyLabels);

        kafkaTemplate.send(
            appProperties.getKafkaTopics().get(KafkaMessageType.ATTACH),
            buildMessageKey(attachEvent),
            attachEvent
        );
    }

    public boolean keepAlive(String instanceId, SocketAddress remoteAddr) {
        log.trace("Processing keep alive for instanceId={} from={}", instanceId, remoteAddr);

        return envoyLeaseTracking.keepAlive(instanceId);
    }

    private List<String> convertToStrings(List<TelemetryEdge.AgentType> agentsList) {
        return agentsList.stream()
            .map(agentType -> agentType.name())
            .collect(Collectors.toList());
    }

    @Scheduled(fixedDelayString = "${ambassador.envoyRefreshInterval:PT10S}")
    public void refreshEnvoys() {

        envoys.forEachKey(appProperties.getEnvoyRefreshParallelism(), instanceId -> {
            final EnvoyEntry envoyEntry = envoys.get(instanceId);

            if (envoyEntry != null) {
                try {
                    synchronized (envoyEntry.instructionStream) {
                        envoyEntry.instructionStream.onNext(TelemetryEdge.EnvoyInstruction.newBuilder()
                            .setRefresh(
                                TelemetryEdge.EnvoyInstructionRefresh.newBuilder().build()
                            )
                            .build());
                    }
                } catch (StatusRuntimeException e) {
                    processFailedSend(instanceId, e);
                }
            }
        });
    }

    public void remove(String instanceId) {
        envoys.remove(instanceId);
        envoyLeaseTracking.revoke(instanceId);
    }

    private void processFailedSend(String instanceId, StatusRuntimeException e) {
        log.info("Removing envoy stream for id={} due to status={}",
            instanceId, e.getStatus());
        remove(instanceId);
    }

    public boolean contains(String envoyInstanceId) {
        return envoys.containsKey(envoyInstanceId);
    }

    public Map<String, String> getEnvoyLabels(String envoyInstanceId) {
        final EnvoyEntry entry = envoys.get(envoyInstanceId);
        return entry != null ? entry.labels : Collections.emptyMap();
    }

    public void sendInstruction(String envoyInstanceId, TelemetryEdge.EnvoyInstruction instruction) {
        final EnvoyEntry envoyEntry = envoys.get(envoyInstanceId);

        if (envoyEntry != null) {
            log.debug("Sending instruction={} to envoyInstance={}",
                instruction, envoyInstanceId);

            try {
                synchronized (envoyEntry.instructionStream) {
                    envoyEntry.instructionStream.onNext(instruction);
                }
            } catch (StatusRuntimeException e) {
                processFailedSend(envoyInstanceId, e);
            }
        } else {
            log.warn("No observer stream for envoyInstance={}, needed for sending instruction={}",
                envoyInstanceId, instruction);
        }
    }
}
