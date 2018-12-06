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

import com.coreos.jetcd.Client;
import com.coreos.jetcd.Watch;
import com.coreos.jetcd.data.ByteSequence;
import com.coreos.jetcd.data.KeyValue;
import com.coreos.jetcd.options.WatchOption;
import com.coreos.jetcd.watch.WatchEvent;
import com.coreos.jetcd.watch.WatchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.telemetry.etcd.EtcdUtils;
import com.rackspace.salus.telemetry.etcd.services.EnvoyLeaseTracking;
import com.rackspace.salus.telemetry.etcd.types.Keys;
import com.rackspace.salus.telemetry.model.AgentRelease;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AgentInstallsWatcher {
    private final Client etcd;
    private final EnvoyRegistry envoyRegistry;
    private final EnvoyLeaseTracking envoyLeaseTracking;
    private final ObjectMapper objectMapper;
    private Watch.Watcher watcher;
    private Thread watcherThread;

    @Autowired
    public AgentInstallsWatcher(Client etcd,
                                EnvoyRegistry envoyRegistry,
                                EnvoyLeaseTracking envoyLeaseTracking,
                                ObjectMapper objectMapper) {
        this.etcd = etcd;
        this.envoyRegistry = envoyRegistry;
        this.envoyLeaseTracking = envoyLeaseTracking;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void handleContextRefresh(ContextRefreshedEvent event) {
        stopWatcher();
        startWatcher();
    }

    private void startWatcher() {
        watcherThread = new Thread(this::runWatcher, "agentInstallsWatcher");
        watcherThread.start();
    }

    private void runWatcher() {
        log.debug("Starting agent installs watcher");

        final ByteSequence prefix = EtcdUtils.buildKey("/agentInstalls/");
        watcher = etcd.getWatchClient().watch(
            prefix,
            WatchOption.newBuilder()
                .withPrefix(prefix)
                .withNoDelete(true)
                .build()
        );

        while (watcher != null) {
            try {
                final WatchResponse response = watcher.listen();
                response.getEvents().forEach(this::processEvent);
            } catch (Exception e) {
                log.debug("Stopping watcher", e);
                return;
            }
        }
    }

    private void stopWatcher() {
        if (watcher != null) {
            watcher.close();
            watcher = null;
            try {
                watcherThread.join(1000);
            } catch (InterruptedException e) {
                log.debug("failed to join expiring watcher thread", e);
            }
        }
    }

    private void processEvent(WatchEvent watchEvent) {
        final KeyValue keyValue = watchEvent.getKeyValue();
        log.trace("Observed agent install event type={}, key={}, value={}, version={}, modRev={}",
            watchEvent.getEventType(), keyValue,
            keyValue.getKey().toStringUtf8(),
            keyValue.getValue().toStringUtf8(),
            keyValue.getVersion(),
            keyValue.getModRevision());

        if (watchEvent.getEventType().equals(WatchEvent.EventType.PUT)) {
            final String key = keyValue.getKey().toStringUtf8();

            final Matcher m = Keys.PTN_AGENT_INSTALLS.matcher(key);
            if (m.matches()) {
                final String tenantId = m.group(1);
                final String envoyInstanceId = m.group(2);
                final String agentType = m.group(3);
                final String agentInfoId = keyValue.getValue().toStringUtf8();


                log.debug("Saw new agent install for tenant={}, envoyInstance={}, agentType={}",
                    tenantId, envoyInstanceId, agentType);

                if (envoyRegistry.contains(envoyInstanceId)) {
                    log.debug("Agent install is for one of our envoy instances={}", envoyInstanceId);

                    sendInstallInstruction(agentInfoId, tenantId, envoyInstanceId);
                }
            } else {
                log.warn("Unexpected agent install key={}", key);
            }
        }
    }

    private CompletableFuture<Void> sendInstallInstruction(String agentInfoId, String tenantId, String envoyInstanceId) {
        return etcd.getKVClient().get(
            EtcdUtils.buildKey(
                Keys.FMT_AGENTS_BY_ID, agentInfoId
            )
        )
            .thenAccept(getResponse -> {

                if (getResponse.getCount() == 0) {
                    log.warn("Unable to find agentInfo={} before sending install instruction", agentInfoId);
                    return;
                }

                try {
                    final AgentRelease agentRelease =
                        EtcdUtils.parseValue(objectMapper, getResponse.getKvs().get(0), AgentRelease.class);

                    final TelemetryEdge.EnvoyInstruction instruction = TelemetryEdge.EnvoyInstruction.newBuilder()
                        .setInstall(
                            TelemetryEdge.EnvoyInstructionInstall.newBuilder()
                                .setUrl(agentRelease.getUrl())
                                .setExe(agentRelease.getExe())
                                .setAgent(TelemetryEdge.Agent.newBuilder()
                                    .setType(TelemetryEdge.AgentType.valueOf(agentRelease.getType().name()))
                                    .setVersion(agentRelease.getVersion())
                                    .build())
                        )
                        .build();

                    envoyRegistry.sendInstruction(envoyInstanceId, instruction);

                } catch (IOException e) {
                    log.warn("Failed to parse AgentInfo with id={}", agentInfoId);
                }
            });
    }

}
