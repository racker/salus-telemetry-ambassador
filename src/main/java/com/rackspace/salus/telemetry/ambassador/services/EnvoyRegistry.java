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

import static com.rackspace.salus.telemetry.ambassador.services.ConfigInstructionsBuilder.convertIntervalToSeconds;
import static com.rackspace.salus.telemetry.model.LabelNamespaces.applyNamespace;
import static java.util.Collections.emptyList;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.rackspace.salus.monitor_management.web.model.BoundMonitorDTO;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstruction;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstructionReady;
import com.rackspace.salus.services.TelemetryEdge.EnvoySummary;
import com.rackspace.salus.telemetry.ambassador.config.AmbassadorProperties;
import com.rackspace.salus.telemetry.ambassador.types.ResourceKey;
import com.rackspace.salus.telemetry.ambassador.types.ZoneNotAuthorizedException;
import com.rackspace.salus.telemetry.etcd.services.EnvoyLeaseTracking;
import com.rackspace.salus.telemetry.etcd.services.EnvoyResourceManagement;
import com.rackspace.salus.telemetry.etcd.services.ZoneStorage;
import com.rackspace.salus.telemetry.etcd.types.ResolvedZone;
import com.rackspace.salus.telemetry.messaging.AttachEvent;
import com.rackspace.salus.telemetry.messaging.OperationType;
import com.rackspace.salus.telemetry.model.AgentType;
import com.rackspace.salus.telemetry.model.LabelNamespaces;
import com.rackspace.salus.telemetry.repositories.AgentHistoryRepository;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class EnvoyRegistry {

  private final AmbassadorProperties appProperties;
  private final EventProducer eventProducer;
  private final EnvoyLeaseTracking envoyLeaseTracking;
  private final EnvoyResourceManagement envoyResourceManagement;
  private final ResourceLabelsService resourceLabelsService;
  private final ZoneAuthorizer zoneAuthorizer;
  private final ZoneStorage zoneStorage;
  private final Counter unauthorizedZoneCounter;
  private final Counter instructionsSentSuccessful;
  private final Counter instructionsSentFailed;
  private final HashFunction boundMonitorHashFunction;
  private final Counter missingInstanceDuringRemove;
  private ConcurrentHashMap<String, EnvoyEntry> envoys = new ConcurrentHashMap<>();
  private ConcurrentHashMap<String, EnvoyEntry> envoysByResourceId = new ConcurrentHashMap<>();
  private final AgentHistoryRepository agentHistoryRepository;

  static final String BAD_RESOURCE_ID_VALIDATION_MESSAGE =
      "resourceId may only contain alphanumeric's, '.', ':', or '-'";
  private static final Pattern resourceValidation = Pattern.compile("[A-Za-z0-9.:-]+");

  @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
  @Autowired
  public EnvoyRegistry(AmbassadorProperties appProperties,
      EventProducer eventProducer,
      EnvoyLeaseTracking envoyLeaseTracking,
      EnvoyResourceManagement envoyResourceManagement,
      ResourceLabelsService resourceLabelsService,
      ZoneAuthorizer zoneAuthorizer,
      ZoneStorage zoneStorage,
      MeterRegistry meterRegistry,
      AgentHistoryRepository agentHistoryRepository) {
    this.appProperties = appProperties;
    this.eventProducer = eventProducer;
    this.envoyLeaseTracking = envoyLeaseTracking;
    this.envoyResourceManagement = envoyResourceManagement;
    this.resourceLabelsService = resourceLabelsService;
    this.zoneAuthorizer = zoneAuthorizer;
    this.zoneStorage = zoneStorage;
    this.boundMonitorHashFunction = Hashing.adler32();
    this.agentHistoryRepository = agentHistoryRepository;

    unauthorizedZoneCounter = meterRegistry.counter("attachErrors", "type", "unauthorizedZone");
    instructionsSentSuccessful = meterRegistry.counter("instructionsSent", "status", "success");
    instructionsSentFailed = meterRegistry.counter("instructionsSent", "status", "failed");
    missingInstanceDuringRemove = meterRegistry.counter("registryIssues", "issue", "missingInstanceDuringRemove");
    meterRegistry.gaugeMapSize("envoyCount", emptyList(), envoys);
  }

  private static <K, V> List<V> getOrCreate(HashMap<K, List<V>> map, K key) {
    return map.computeIfAbsent(key, operationType -> new ArrayList<>());
  }

  private static ResourceKey buildResourceKey(BoundMonitorDTO boundMonitorDTO) {
    return new ResourceKey(
        boundMonitorDTO.getTenantId(), boundMonitorDTO.getResourceId());
  }

  /**
   * Executed whenever we receive a new connection from an envoy.
   * <p>
   *   This method is asynchronous to avoid any cross-corruption of the GrpcContext since
   *   both Envoy processing and etcd calls are gRPC based.
   * </p>
   *
   * @param tenantId tenant of the attached Envoy
   * @param envoyId the Envoy's UUID
   * @param envoySummary the Envoy summary
   * @param remoteAddr the remote IP+port of the Envoy
   * @param instructionStreamObserver the response stream
   * @return a {@link CompletableFuture} of the lease ID granted to the attached Envoy
   */
  public CompletableFuture<?> attach(String tenantId, String envoyId, EnvoySummary envoySummary,
      SocketAddress remoteAddr, StreamObserver<EnvoyInstruction> instructionStreamObserver)
      throws StatusException {
    final String resourceId = envoySummary.getResourceId();

    log.debug("Processing attach tenant={} envoy={} resourceId={} remoteAddr={}",
        tenantId, envoyId, resourceId, remoteAddr);

    final ResolvedZone zone;
    if (StringUtils.hasText(envoySummary.getZone())) {
      try {
        zone = zoneAuthorizer.authorize(tenantId, envoySummary.getZone());
      } catch (ZoneNotAuthorizedException e) {
        unauthorizedZoneCounter.increment();
        log.warn("Envoy attachment from remoteAddr={} is unauthorized: {}", remoteAddr,
            e.getMessage());
        throw new StatusException(Status.INVALID_ARGUMENT.withDescription(e.getMessage()));
      } catch (IllegalArgumentException e) {
        log.debug("Envoy attachment from remoteAddr={} specified invalid zone: {}", remoteAddr,
            e.getMessage());
        throw new StatusException(Status.INVALID_ARGUMENT.withDescription(e.getMessage()));
      }
    } else {
      zone = null;
    }

    final Map<String, String> envoyLabels = processEnvoyLabels(envoySummary);
    final List<String> supportedAgentTypes = convertToStrings(
        envoySummary.getSupportedAgentsList());

    if (!StringUtils.hasText(resourceId)) {
      throw new StatusException(Status.INVALID_ARGUMENT.withDescription("resourceId is required"));
    } else if (!resourceValidation.matcher(resourceId).matches()) {
      throw new StatusException(Status.INVALID_ARGUMENT.withDescription(
          BAD_RESOURCE_ID_VALIDATION_MESSAGE));
    }

    EnvoyEntry existingEntry = envoys.get(envoyId);
    if (existingEntry != null) {
      log.warn(
          "Saw re-attachment of same envoy id={}, so aborting the previous stream to solve race condition",
          envoyId);
      existingEntry.instructionStream.onError(
          new StatusException(Status.ABORTED.withDescription("Reconnect seen from same envoyId")));
      envoyLeaseTracking.revoke(envoyId);
    }

    resourceLabelsService.trackResource(tenantId, resourceId);

    return envoyLeaseTracking.grant(envoyId, appProperties.getEnvoyLeaseDuration().toSeconds())
        .thenApply(leaseId -> {
          final EnvoyEntry entry = new EnvoyEntry(
              instructionStreamObserver, tenantId, envoyId, resourceId);
          envoys.put(envoyId, entry);
          envoysByResourceId.put(resourceId, entry);
          log.debug("Tracking entry for envoyInstance={} with resourceId={}", envoyId, resourceId);
          return leaseId;
        })
        .thenCompose(leaseId ->
            // register in zone if not null or passes through otherwise
            registerInZone(envoyId, resourceId, zone, leaseId))
        .thenApply(leaseId -> {
            sendReadyInstruction(envoyId, instructionStreamObserver);
            return leaseId;
        })
        .thenCompose(leaseId ->
            postAttachEvent(tenantId, envoyId, envoySummary, envoyLabels, remoteAddr)
                .thenApply(sendResult -> {
                  log.debug(
                      "Posted attach event on partition={} for tenant={}, envoyId={}, resourceId={}",
                      sendResult.getRecordMetadata().partition(),
                      tenantId, envoyId, resourceId);
                  return leaseId;
                }))
        .thenCompose(leaseId ->
            envoyResourceManagement
                .registerResource(tenantId, envoyId, leaseId, resourceId, envoyLabels, remoteAddr)
                .thenApply(putResponse -> {
                  log.debug("Registered new envoy resource for presence monitoring for " +
                          "tenant={}, envoyId={}, resourceId={}",
                      tenantId, envoyId, resourceId);
                  return leaseId;
                })
        )
        ;

  }

  private void sendReadyInstruction(String envoyId,
                                    StreamObserver<EnvoyInstruction> instructionStreamObserver) {
    log.debug("Sending ready instruction to envoy={}", envoyId);
    synchronized (instructionStreamObserver) {
      instructionStreamObserver.onNext(
          EnvoyInstruction.newBuilder()
              .setReady(EnvoyInstructionReady.newBuilder().build())
              .build()
      );
      instructionsSentSuccessful.increment();
    }
  }

  private CompletionStage<Long> registerInZone(String envoyId, String resourceId,
      ResolvedZone zone, Long leaseId) {
    if (zone != null) {
      return zoneStorage.registerEnvoyInZone(zone, envoyId, resourceId, leaseId)
          .thenApply(result -> {
            log.debug("Registered envoyId={} in zone={}", envoyId, zone);
            return leaseId;
          });
    } else {
      return CompletableFuture.completedFuture(leaseId);
    }
  }

  private Map<String, String> processEnvoyLabels(EnvoySummary envoySummary) {

    // apply a namespace to the label names
    return envoySummary.getLabelsMap().entrySet().stream()
        .collect(Collectors.toMap(
            entry -> applyNamespace(LabelNamespaces.AGENT, entry.getKey()),
            Map.Entry::getValue
        ));
  }

  private CompletableFuture<SendResult<String, Object>> postAttachEvent(String tenantId,
      String envoyId, EnvoySummary envoySummary,
      Map<String, String> envoyLabels,
      SocketAddress remoteAddr) {

    final String resourceId = envoySummary.getResourceId();
    final AttachEvent attachEvent = new AttachEvent()
        .setTenantId(tenantId)
        .setEnvoyId(envoyId)
        .setResourceId(resourceId)
        .setEnvoyAddress(((InetSocketAddress) remoteAddr).getHostString())
        .setLabels(envoyLabels);

    return eventProducer.sendAttach(attachEvent)
        .completable();
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

  @Scheduled(fixedDelayString = "#{ambassadorProperties.envoyRefreshInterval}")
  public void refreshEnvoys() {

    envoys.forEach(appProperties.getEnvoyRefreshParallelism(), (instanceId, envoyEntry) -> {
      try {
        synchronized (envoyEntry.instructionStream) {
          envoyEntry.instructionStream
              .onNext(TelemetryEdge.EnvoyInstruction.newBuilder()
                  .setRefresh(
                      TelemetryEdge.EnvoyInstructionRefresh.newBuilder().build()
                  )
                  .build());
        }
      } catch (Exception e) {
        // Most likely exceptions are due to the gRPC connection being closed by
        // Envoy connection loss or failure to establish attachment. The later
        // gets thrown as an IllegalStateException.
        processFailedSend(instanceId, e);
      }
    });
  }

  /**
   * <p>
   * Runs async to avoid cross-interactions with the etcd operations that also use
   * grpc context, which seems to be stored in thread-local storage
   * </p>
   * @param instanceId
   */
  public void remove(String instanceId) {
    log.debug("Removing registration of envoyInstance={}", instanceId);
    final EnvoyEntry entry = envoys.remove(instanceId);
    if (entry != null) {
      resourceLabelsService.releaseResource(entry.getTenantId(), entry.getResourceId());
    } else {
      log.warn("Unable to locate envoy entry for instance={}", instanceId);
      missingInstanceDuringRemove.increment();
    }
    envoyLeaseTracking.revoke(instanceId);
  }

  private void processFailedSend(String instanceId, Exception e) {
    log.info("Removing envoy stream for id={} due to exception={}",
        instanceId, e.getMessage());
    remove(instanceId);
  }

  public boolean contains(String envoyInstanceId) {
    return envoys.containsKey(envoyInstanceId);
  }

  public String getResourceId(String envoyInstanceId) {
    final EnvoyEntry entry = envoys.get(envoyInstanceId);
    return entry != null ? entry.getResourceId() : null;
  }

  public boolean containsEnvoyResource(String resourceId) {
    return envoysByResourceId.containsKey(resourceId);
  }

  public String getEnvoyIdByResource(String resourceId) {
    final EnvoyEntry entry = envoysByResourceId.get(resourceId);
    return entry != null ? entry.getEnvoyId() : null;
  }

  /**
   * "Sends" an instruction to the requested Envoy via the response stream of the attachment call.
   * @param envoyInstanceId the recipient envoy
   * @param instruction instruction to send
   * @return <code>true</code> if the instruction was successfully sent
   */
  public boolean sendInstruction(String envoyInstanceId, EnvoyInstruction instruction) {
    final EnvoyEntry envoyEntry = envoys.get(envoyInstanceId);

    if (envoyEntry != null) {
      log.debug("Sending instruction={} to envoyInstance={}",
          instruction, envoyInstanceId);

      try {
        synchronized (envoyEntry.instructionStream) {
          envoyEntry.instructionStream.onNext(instruction);
          instructionsSentSuccessful.increment();
          return true;
        }
      } catch (StatusRuntimeException e) {
        processFailedSend(envoyInstanceId, e);
      }
    } else {
      log.warn("No observer stream for envoyInstance={}, needed for sending instruction={}",
          envoyInstanceId, instruction);
    }

    instructionsSentFailed.increment();
    return false;
  }

  void createTestingEntry(String envoyId) {
    envoys.put(envoyId, new EnvoyEntry(null, null, null, null));
  }

  /**
   * Reconciles the given bound monitors for the envoy against the existing entry for that envoy.
   *
   * @param envoyId the envoy to reconcile
   * @param boundMonitors the latest set of bound monitors provided by the monitor manager for this
   * envoy
   * @return a mapping of detected changes organized by change/operation type. For deletions, a
   * BoundMonitorDTO is fabricated to convey the details of the deleted monitor.
   */
  @SuppressWarnings("UnstableApiUsage")
  public Map<OperationType, List<BoundMonitorDTO>> applyBoundMonitors(String envoyId,
      List<BoundMonitorDTO> boundMonitors) {
    final EnvoyEntry entry = envoys.get(envoyId);
    if (entry == null) {
      return null;
    }

    final HashMap<OperationType, List<BoundMonitorDTO>> changes = new HashMap<>();

    final Set<ResourceKey> resourcesToRetain = new HashSet<>();

    synchronized (entry.getBoundMonitors()) {
      final Map<String, BoundMonitorEntry> bindings = entry.getBoundMonitors();
      // This will be used to track monitors that got removed by starting with all, but
      // incrementally removing from this set as they're seen in the incoming bound monitors
      final Set<String> staleMonitorIds = new HashSet<>(bindings.keySet());

      for (BoundMonitorDTO boundMonitor : boundMonitors) {

        final String monitorId = ConfigInstructionsBuilder.buildConfiguredMonitorId(boundMonitor);
        staleMonitorIds.remove(monitorId);
        resourcesToRetain.add(buildResourceKey(boundMonitor));

        final BoundMonitorEntry prevEntry = bindings.get(monitorId);

        if (prevEntry == null) {
          // CREATED
          getOrCreate(changes, OperationType.CREATE).add(boundMonitor);
          bindings.put(
              monitorId,
              new BoundMonitorEntry(
                  hashUpdatableFields(boundMonitor), boundMonitor.getAgentType(),
                  boundMonitor.getMonitorId(),
                  boundMonitor.getTenantId(), boundMonitor.getResourceId()
              )
          );
        } else {
          // Possibly modified

          final HashCode newHashCode = hashUpdatableFields(boundMonitor);

          if (!newHashCode.equals(prevEntry.getUpdatableFieldsHash())) {
            // UPDATED
            getOrCreate(changes, OperationType.UPDATE).add(boundMonitor);
            bindings.put(
                monitorId,
                new BoundMonitorEntry(
                    hashUpdatableFields(boundMonitor), boundMonitor.getAgentType(),
                    boundMonitor.getMonitorId(),
                    boundMonitor.getTenantId(), boundMonitor.getResourceId()
                )
            );
          }
        }
      }

      // DELETE ones left over
      for (String staleMonitorId : staleMonitorIds) {
        final BoundMonitorEntry removed = bindings.remove(staleMonitorId);
        getOrCreate(changes, OperationType.DELETE).add(
            // fabricate a bound monitor just so we can convey the minimal attributes
            new BoundMonitorDTO()
                .setAgentType(removed.agentType)
                .setMonitorId(removed.monitorId)
                .setTenantId(removed.tenantId)
                .setResourceId(removed.resourceId)
                // rendered content is not used by envoy, but needs to be non-null for gRPC
                .setRenderedContent("")
        );

      }

    } // end of synchronized block

    return changes;
  }

  /**
   * Hashes the fields of the bound monitor that need to be detected for updates. This is a
   * cpu-memory tradeoff to avoid storing fields of the bound monitor into {@link BoundMonitorEntry}
   * where only the change in value matters.
   */
  @SuppressWarnings("UnstableApiUsage")
  private HashCode hashUpdatableFields(BoundMonitorDTO boundMonitor) {
    return boundMonitorHashFunction.newHasher()
        .putString(boundMonitor.getRenderedContent(), StandardCharsets.UTF_8)
        .putLong(convertIntervalToSeconds(boundMonitor.getInterval()))
        .hash();
  }

  public Map<AgentType, String> trackAgentInstall(String envoyInstanceId, AgentType agentType, String agentVersion) {
    final EnvoyEntry envoyEntry = envoys.get(envoyInstanceId);

    if (envoyEntry != null) {
      synchronized (envoyEntry.installedAgentVersions) {
        envoyEntry.installedAgentVersions.put(agentType, agentVersion);
        return new HashMap<>(envoyEntry.installedAgentVersions);
      }
    }

    return null;
  }

  public Map<AgentType, String/*version*/> getInstalledAgentVersions(String envoyInstanceId) {
    final EnvoyEntry envoyEntry = envoys.get(envoyInstanceId);

    if (envoyEntry != null) {
      synchronized (envoyEntry.installedAgentVersions) {
        return new HashMap<>(envoyEntry.installedAgentVersions);
      }
    } else {
      return null;
    }
  }

  @Data
  static class EnvoyEntry {

    final StreamObserver<TelemetryEdge.EnvoyInstruction> instructionStream;
    final String tenantId;
    final String envoyId;
    final String resourceId;

    /**
     * Maps {@link ConfigInstructionsBuilder#buildConfiguredMonitorId(BoundMonitorDTO)} to a hash of its the
     * bound monitor's rendered content
     */
    Map<String, BoundMonitorEntry> boundMonitors = new HashMap<>();

    final Map<AgentType, String> installedAgentVersions = new HashMap<>();
  }

  @Data
  static class BoundMonitorEntry {

    /**
     * Hash of the fields that need to trigger an update operation when they change.
     */
    @SuppressWarnings("UnstableApiUsage")
    final HashCode updatableFieldsHash;
    /**
     * agentType is needed to handle deletion of entries.
     */
    final AgentType agentType;
    /**
     * monitorId is needed to handle deletion of entries.
     */
    final UUID monitorId;
    /**
     * The tenant owning the resource. Needed to handle deletion of entries.
     */
    final String tenantId;
    /**
     * resourceId is needed to handle deletion of entries.
     */
    final String resourceId;
  }

}
