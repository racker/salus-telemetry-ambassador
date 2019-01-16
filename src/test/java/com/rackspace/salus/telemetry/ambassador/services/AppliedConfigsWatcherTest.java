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

import static com.rackspace.salus.telemetry.etcd.EtcdUtils.buildGetResponse;
import static com.rackspace.salus.telemetry.etcd.EtcdUtils.buildValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coreos.jetcd.Client;
import com.coreos.jetcd.KV;
import com.coreos.jetcd.Watch;
import com.coreos.jetcd.data.ByteSequence;
import com.coreos.jetcd.data.KeyValue;
import com.coreos.jetcd.watch.WatchEvent;
import com.coreos.jetcd.watch.WatchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.telemetry.etcd.EtcdUtils;
import com.rackspace.salus.telemetry.etcd.types.AppliedConfig;
import com.rackspace.salus.telemetry.etcd.types.Keys;
import com.rackspace.salus.telemetry.model.AgentConfig;
import com.rackspace.salus.telemetry.model.AgentType;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.junit4.SpringRunner;


@RunWith(SpringRunner.class)
@JsonTest
public class AppliedConfigsWatcherTest {
    @MockBean
    Client etcd;

    @Mock
    KV kv;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EnvoyRegistry envoyRegistry;

    @Mock
    private Watch.Watcher watcher;

    @Mock
    private Watch watch;

    @Mock
    private WatchResponse watchResponse;

    @Mock
    private WatchEvent watchEvent;

    @Mock
    private KeyValue keyValue;

    List<WatchEvent> events;
    
    @Before
    public void setUp() throws Exception {
        when(etcd.getKVClient()).thenReturn(kv);
        when(etcd.getWatchClient()).thenReturn(watch);
    }

    @Test
    public void testHandleContextRefresh() throws Exception {
        
        final String configKey = "/tenants/t1/agentConfigs/byId/ac1";
        AgentConfig agentConfig = new AgentConfig()
                .setAgentType(AgentType.FILEBEAT)
                .setContent("config goes here")
                .setLabels(Collections.singletonMap("os", "LINUX"));

        Semaphore testSem = new Semaphore(0);
        Semaphore watcherSem = new Semaphore(0);
        AppliedConfig appliedConfig = new AppliedConfig();
        appliedConfig.setId("ac1");
        appliedConfig.setAgentType(AgentType.FILEBEAT);
        ByteSequence appliedConfigBytes = buildValue(objectMapper, appliedConfig);
        events = Arrays.asList(watchEvent);

        when(watch.watch(eq(EtcdUtils.buildKey(Keys.FMT_APPLIED_CONFIGS_PREFIX)),
            any())).thenReturn(watcher);
        when(watcher.listen()).thenReturn(watchResponse);
        when(watchResponse.getEvents()).thenReturn(events);
        when(watchEvent.getKeyValue()).thenReturn(keyValue);
        when(watchEvent.getPrevKV()).thenReturn(keyValue);
        when(keyValue.getKey())
            .thenReturn(EtcdUtils.buildKey("/appliedConfigs/ALL_OF/t1/ac1/en1"));
        when(keyValue.getValue()).thenReturn(appliedConfigBytes);
        when(envoyRegistry.contains("en1")).thenReturn(Boolean.TRUE);
        when(kv.get(EtcdUtils.buildKey(Keys.FMT_AGENT_CONFIGS, "t1", "ac1")))
            .thenReturn(buildGetResponse(objectMapper, configKey, agentConfig));

        // Have the watcher let the test know each time it calls sendInstruction
        doAnswer(invocationOnMock -> {
            testSem.release();
            watcherSem.acquire();
            return null;
        }).when(envoyRegistry).sendInstruction(any(),any());

        // Test Create
        when(watchEvent.getEventType()).thenReturn(WatchEvent.EventType.PUT);
        when(keyValue.getVersion()).thenReturn(1L);
        
        // Start the watcher
        AppliedConfigsWatcher appliedConfigsWatcher =
            new AppliedConfigsWatcher(etcd, envoyRegistry, objectMapper);
        appliedConfigsWatcher.handleContextRefresh(null);

        // Wait for watcher to respond
        testSem.acquire();
        verify(envoyRegistry).sendInstruction("en1", buildInstruction(TelemetryEdge.ConfigurationOp.Type.CREATE));
        verify(envoyRegistry, atMost(1)).sendInstruction(any(), any());

        // Now test MODIFY
        when(keyValue.getVersion()).thenReturn(2L);
        watcherSem.release();
        testSem.acquire();
        verify(envoyRegistry).sendInstruction("en1", buildInstruction(TelemetryEdge.ConfigurationOp.Type.MODIFY));
        verify(envoyRegistry, atMost(2)).sendInstruction(any(), any());

        // Now test REMOVE
        when(watchEvent.getEventType()).thenReturn(WatchEvent.EventType.DELETE);
        watcherSem.release();
        testSem.acquire();
        verify(envoyRegistry).sendInstruction("en1", buildInstruction(TelemetryEdge.ConfigurationOp.Type.REMOVE));
        verify(envoyRegistry, atMost(3)).sendInstruction(any(), any());
    }

    private TelemetryEdge.EnvoyInstruction buildInstruction(TelemetryEdge.ConfigurationOp.Type opType) {
        TelemetryEdge.ConfigurationOp.Builder c = TelemetryEdge.ConfigurationOp.newBuilder()
                .setType(opType)
                .setId("ac1");
        if (opType != TelemetryEdge.ConfigurationOp.Type.REMOVE) {
            c.setContent("config goes here");
        }

        return TelemetryEdge.EnvoyInstruction.newBuilder()
        .setConfigure(
            TelemetryEdge.EnvoyInstructionConfigure.newBuilder()
            .setAgentType(TelemetryEdge.AgentType.valueOf("FILEBEAT"))
            .addOperations(c)
        ).build();
    }

}
