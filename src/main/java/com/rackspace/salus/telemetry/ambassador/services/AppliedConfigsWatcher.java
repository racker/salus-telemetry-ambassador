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

import static com.rackspace.salus.telemetry.etcd.EtcdUtils.buildKey;
import static com.rackspace.salus.telemetry.etcd.EtcdUtils.parseValue;

import com.coreos.jetcd.Client;
import com.coreos.jetcd.Watch;
import com.coreos.jetcd.common.exception.ClosedClientException;
import com.coreos.jetcd.common.exception.ClosedWatcherException;
import com.coreos.jetcd.data.ByteSequence;
import com.coreos.jetcd.data.KeyValue;
import com.coreos.jetcd.options.WatchOption;
import com.coreos.jetcd.watch.WatchEvent;
import com.coreos.jetcd.watch.WatchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.telemetry.etcd.types.AppliedConfig;
import com.rackspace.salus.telemetry.etcd.types.Keys;
import com.rackspace.salus.telemetry.model.AgentConfig;
import java.io.IOException;
import java.util.regex.Matcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AppliedConfigsWatcher {

    private final Client etcd;
    private final EnvoyRegistry envoyRegistry;
    private final ObjectMapper objectMapper;
    private Thread watcherThread;
    private Watch.Watcher watcher;

    @Autowired
    public AppliedConfigsWatcher(Client etcd, EnvoyRegistry envoyRegistry, ObjectMapper objectMapper) {
        this.etcd = etcd;
        this.envoyRegistry = envoyRegistry;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void handleContextRefresh(ContextRefreshedEvent event) {
        stopWatcher();
        startWatcher();
    }

    private void stopWatcher() {
        if (watcher != null) {
            watcher.close();
            watcher = null;

            try {
                watcherThread.join();
            } catch (InterruptedException e) {
                log.debug("failed to join expiring watcher thread", e);
            }
        }
    }

    private void startWatcher() {
        watcherThread = new Thread(this::runWatcher, "ConfigsWatcher");
        watcherThread.start();
    }

    private void runWatcher() {
        final ByteSequence prefix = buildKey(Keys.FMT_APPLIED_CONFIGS_PREFIX);

        watcher = etcd.getWatchClient().watch(prefix, WatchOption.newBuilder()
            .withPrefix(prefix)
            .withPrevKV(true)
            .build());

        while (watcher != null) {
            try {
                final WatchResponse response = watcher.listen();
                response.getEvents().forEach(this::processEvent);

            } catch (ClosedClientException | ClosedWatcherException e) {
                log.debug("Stopping watcher");
            } catch (Exception e) {
                log.warn("Unexpected exception while watching", e);
            }
        }
    }

    private void processEvent(WatchEvent watchEvent) {
        KeyValue keyValue = watchEvent.getKeyValue();
        TelemetryEdge.ConfigurationOp.Type opType = TelemetryEdge.ConfigurationOp.Type.CREATE;

        if (watchEvent.getEventType().equals(WatchEvent.EventType.PUT)) {
            if (watchEvent.getKeyValue().getVersion() > 1) {
                opType = TelemetryEdge.ConfigurationOp.Type.MODIFY;
            }
        } else if (watchEvent.getEventType().equals(WatchEvent.EventType.DELETE)) {
            keyValue = watchEvent.getPrevKV();
            opType = TelemetryEdge.ConfigurationOp.Type.REMOVE;
        }
        final String key = keyValue.getKey().toStringUtf8();
        final AppliedConfig appliedConfig;
        try {
            appliedConfig = parseValue(objectMapper, keyValue, AppliedConfig.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse AppliedConfig", e);
        }
        final String agentConfigId = appliedConfig.getId();
        log.trace("Observed applied configs event type={}, key={}, configId={}, version={}, modRev={}",
            watchEvent.getEventType(), key,
            agentConfigId,
            keyValue.getVersion(),
            keyValue.getModRevision());

        final Matcher m = Keys.PTN_APPLIED_CONFIGS.matcher(key);
        if (m.matches()) {
            final String tenant = m.group("tenant");
            final String envoyInstanceId = m.group("envoyInstanceId");
            if (envoyRegistry.contains(envoyInstanceId)) {
                log.debug("Observed applied config={} event for our tenant={} envoyInstance={}",
                        agentConfigId, tenant, envoyInstanceId);
                if (opType == TelemetryEdge.ConfigurationOp.Type.REMOVE) {
                    sendDeleteConfigInstruction(tenant, envoyInstanceId, appliedConfig);

                } else {
                    sendConfigInstruction(tenant, envoyInstanceId, agentConfigId, opType);
                }
            }
        }
    }
    private void sendDeleteConfigInstruction(String tenant, String envoyInstanceId, AppliedConfig appliedConfig) {
    final TelemetryEdge.EnvoyInstruction instruction = TelemetryEdge.EnvoyInstruction.newBuilder()
        .setConfigure(
            TelemetryEdge.EnvoyInstructionConfigure.newBuilder()
            .setAgentType(TelemetryEdge.AgentType.valueOf(appliedConfig.getAgentType().name()))
            .addOperations(
                TelemetryEdge.ConfigurationOp.newBuilder()
                .setType(TelemetryEdge.ConfigurationOp.Type.REMOVE)
                .setId(appliedConfig.getId())
            )
        )
        .build();
    envoyRegistry.sendInstruction(envoyInstanceId, instruction);
    }
    
    private void sendConfigInstruction(String tenant, String envoyInstanceId, String agentConfigId, TelemetryEdge.ConfigurationOp.Type opType) {
        final ByteSequence key = buildKey(
            Keys.FMT_AGENT_CONFIGS,
            tenant, agentConfigId
        );

        etcd.getKVClient().get(key)
            .thenAccept(getResponse -> {

                if (getResponse.getKvs().isEmpty()) {
                    log.warn("Unable to find agent config at key={}", key.toStringUtf8());
                }
                else {
                    try {
                        final AgentConfig agentConfig =
                            parseValue(objectMapper, getResponse.getKvs().get(0), AgentConfig.class);

                        final TelemetryEdge.EnvoyInstruction instruction = TelemetryEdge.EnvoyInstruction.newBuilder()
                            .setConfigure(
                                TelemetryEdge.EnvoyInstructionConfigure.newBuilder()
                                .setAgentType(TelemetryEdge.AgentType.valueOf(agentConfig.getAgentType().name()))
                                .addOperations(
                                    TelemetryEdge.ConfigurationOp.newBuilder()
                                    .setType(opType)
                                    .setId(agentConfigId)
                                    .setContent(agentConfig.getContent())
                                )
                            )
                            .build();

                        envoyRegistry.sendInstruction(envoyInstanceId, instruction);

                    } catch (IOException e) {
                        log.warn("Failed to parse agent config at key={}", key.toStringUtf8(), e);
                    }

                }
            });
    }

}
