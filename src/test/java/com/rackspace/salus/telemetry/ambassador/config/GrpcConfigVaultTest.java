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

package com.rackspace.salus.telemetry.ambassador.config;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rackspace.salus.services.TelemetryAmbassadorGrpc;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.telemetry.ambassador.MockAmbassadorService;
import com.rackspace.salus.telemetry.ambassador.services.GrpcContextDetails;
import com.rackspace.salus.telemetry.ambassador.services.TestAsyncConfig;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.lognet.springboot.grpc.autoconfigure.GRpcAutoConfiguration;
import org.lognet.springboot.grpc.context.LocalRunningGrpcPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.StringUtils;
import org.springframework.vault.core.VaultPkiOperations;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.CertificateBundle;
import org.springframework.vault.support.VaultCertificateResponse;

/**
 * Tests the gRPC setup when the Vault components are available for certificate allocation.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(
    classes = {
        TestAsyncConfig.class,
        GrpcConfig.class,
        AmbassadorProperties.class,
        GrpcContextDetails.class,
        MockAmbassadorService.class,
        GrpcConfigVaultTest.TestConfig.class,
        GRpcAutoConfiguration.class
    },
    properties = {
        "grpc.port=0",
        "salus.ambassador.public-zone-tenants=aaaaaa"
    }
)
@ActiveProfiles("test")
public class GrpcConfigVaultTest {

    @TestConfiguration
    public static class TestConfig {

        @Bean
        public VaultTemplate vaultTemplate() throws IOException {
            final VaultTemplate vaultTemplate = mock(VaultTemplate.class);

            final VaultPkiOperations pkiOperations = mock(VaultPkiOperations.class);

            when(vaultTemplate.opsForPki())
                .thenReturn(pkiOperations);

            final CertificateBundle certBundle = CertificateBundle.of("1",
                loadDerFromPemFile("certs/ambassador.pem"),
                loadDerFromPemFile("certs/ca.pem"),
                loadDerFromPemFile("certs/ambassador-key.pem")
            );
            VaultCertificateResponse response = new VaultCertificateResponse();
            response.setData(certBundle);

            when(pkiOperations.issueCertificate(any(), any()))
                .thenReturn(response);

            return vaultTemplate;
        }

        private String loadDerFromPemFile(String path) throws IOException {
            final ClassPathResource pemResource = new ClassPathResource(path);

            return Files.readAllLines(pemResource.getFile().toPath()).stream()
                .map(line -> line.replaceAll("-----.*-----", ""))
                .filter(StringUtils::hasText)
                .collect(Collectors.joining());
        }

    }

    @LocalRunningGrpcPort
    int port;

    @Autowired
    MockAmbassadorService mockAmbassadorService;

    @Test
    public void testBasic() throws IOException {
        final ClassPathResource caResource = new ClassPathResource("certs/ca.pem");
        final ClassPathResource clientCertResource = new ClassPathResource("certs/tenantA.pem");
        final ClassPathResource clientKeyResource = new ClassPathResource("certs/tenantA-pkcs8-key.pem");

        final ManagedChannel channel = NettyChannelBuilder.forAddress("localhost", port)
            .sslContext(
                GrpcSslContexts.forClient()
                    .keyManager(clientCertResource.getFile(), clientKeyResource.getFile())
                    .trustManager(caResource.getFile())
                    .build()
            )
            .build();

        try {
            Metadata headers = new Metadata();
            headers.put(
                Metadata.Key.of(GrpcContextDetails.ENVOY_ID_HEADER, Metadata.ASCII_STRING_MARSHALLER),
                "envoy-1");
            final TelemetryAmbassadorGrpc.TelemetryAmbassadorBlockingStub ambassadorClient =
                MetadataUtils.attachHeaders(
                    TelemetryAmbassadorGrpc.newBlockingStub(channel),
                    headers
                );

            mockAmbassadorService.addInstructionToProvide(
                TelemetryEdge.EnvoyInstruction.newBuilder().build()
            );

            final Iterator<TelemetryEdge.EnvoyInstruction> instructions = ambassadorClient.attachEnvoy(
                TelemetryEdge.EnvoySummary.newBuilder()
                    .addSupportedAgents(TelemetryEdge.AgentType.TELEGRAF)
                    .putLabels("os", "linux")
                    .build()
            );
            final TelemetryEdge.EnvoyInstruction instruction = instructions.next();
            assertThat(instruction, notNullValue());

            final List<MockAmbassadorService.AttachCall> attachCalls = mockAmbassadorService.getAttachCalls();
            assertThat(attachCalls, Matchers.hasSize(1));

            assertThat(attachCalls.get(0).getTenantId(), equalTo("aaaaaa"));
            assertThat(attachCalls.get(0).getEnvoyId(), equalTo("envoy-1"));
            final TelemetryEdge.EnvoySummary attachReq = attachCalls.get(0).getRequest();
            assertThat(attachReq.getSupportedAgentsList(), contains(TelemetryEdge.AgentType.TELEGRAF));
            assertThat(attachReq.getLabelsMap(), hasEntry("os", "linux"));

        } finally {
            channel.shutdown();
        }

    }
}