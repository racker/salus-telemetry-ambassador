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

import com.google.protobuf.util.JsonFormat;
import io.grpc.ServerBuilder;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.io.File;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;
import lombok.extern.slf4j.Slf4j;
import org.lognet.springboot.grpc.GRpcServerBuilderConfigurer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.CertificateBundle;
import org.springframework.vault.support.VaultCertificateRequest;
import org.springframework.vault.support.VaultCertificateResponse;

@Configuration
@Slf4j
public class GrpcConfig extends GRpcServerBuilderConfigurer {
    private final AmbassadorProperties appProperties;
    private final VaultTemplate vaultTemplate;

    @Autowired
    public GrpcConfig(AmbassadorProperties appProperties, @Nullable VaultTemplate vaultTemplate) {
        this.appProperties = appProperties;
        this.vaultTemplate = vaultTemplate;
    }

    @Override
    public void configure(ServerBuilder<?> serverBuilder) {
        final NettyServerBuilder nettyServerBuilder =
            ((NettyServerBuilder) serverBuilder)
                .bossEventLoopGroup(new NioEventLoopGroup(appProperties.getGrpcBossThreads()))
                .workerEventLoopGroup(new NioEventLoopGroup(
                    appProperties.getGrpcWorkerThreads(),
                    new DefaultThreadFactory("grpc-worker")
                ))
                .channelType(NioServerSocketChannel.class);

        try {
            if (!appProperties.isDisableTls()) {
                nettyServerBuilder
                        .sslContext(buildSslContext());
            }
            else {
                log.warn("gRPC TLS is DISABLED -- only suitable for local development");
            }

        } catch (SSLException e) {
            throw new RuntimeException("Failed to initialize SSL context");
        }
    }

    private SslContext buildSslContext() throws SSLException {
        final SslContextBuilder contextBuilder;
        if (vaultTemplate != null) {
            contextBuilder = buildSslContextFromVault();
        }
        else {
            contextBuilder = buildSslContextFromProvided();
        }

        return GrpcSslContexts.configure(contextBuilder, SslProvider.OPENSSL)
                .build();
    }

    private SslContextBuilder buildSslContextFromProvided() {
        log.info("Loading certificates from provided files: certChain={}, key={}, trustCert={}",
            appProperties.getCertChainPath(),
            appProperties.getKeyPath(),
            appProperties.getTrustCertPath());

        return SslContextBuilder.forServer(
            new File(appProperties.getCertChainPath()),
            new File(appProperties.getKeyPath()))
            .trustManager(new File(appProperties.getTrustCertPath()))
            .clientAuth(ClientAuth.REQUIRE);
    }

    private SslContextBuilder buildSslContextFromVault() {
        final String externalName = appProperties.getExternalName();
        Assert.hasText(
            externalName,
            "Ambassador's externalName must be set to its FQDN");

        log.info("Loading certificates from Vault for {}", externalName);
        final VaultCertificateResponse resp = vaultTemplate.opsForPki()
            .issueCertificate(appProperties.getVaultPkiRole(),
                VaultCertificateRequest.builder()
                    .commonName(externalName)
                    .altNames(appProperties.getAltExternalNames())
                    .build());

        final CertificateBundle certBundle = resp.getData();
        if (certBundle == null) {
            throw new IllegalStateException("Cert bundle from Vault response was null");
        }
        final KeyStore keyStore = certBundle.createKeyStore("key");

        try {
            return SslContextBuilder
                .forServer(
                    (PrivateKey) keyStore.getKey(
                        "key", // just needs to match createKeyStore alias above
                        new char[0] // was created with empty password in key store above
                    ),
                    certBundle.getX509Certificate()
                )
                .trustManager(certBundle.getX509IssuerCertificate())
                .clientAuth(ClientAuth.REQUIRE);

        } catch (KeyStoreException | NoSuchAlgorithmException | UnrecoverableKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    @Bean
    public JsonFormat.Printer jsonPrinter() {
        return JsonFormat.printer()
            .includingDefaultValueFields()
            .omittingInsignificantWhitespace();
    }
}
