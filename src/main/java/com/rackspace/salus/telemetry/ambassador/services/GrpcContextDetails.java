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

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.net.SocketAddress;
import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.security.auth.x500.X500Principal;
import lombok.extern.slf4j.Slf4j;
import org.lognet.springboot.grpc.GRpcGlobalInterceptor;

/**
 * This GRPC interceptor extracts two pieces of information from the gRPC call context:
 * <ul>
 *   <li>Tenant ID via the common name of the client certificate used for authentication.
 *   An implementation of a service method can obtain this value from {@link #getCallerTenantId()}.
 *   </li>
 *   <li>Remote caller's address.
 *   An implementation of a service method can obtain this value from {@link #getCallerRemoteAddress()}.
 *   </li>
 * </ul>
 */
@GRpcGlobalInterceptor
@Slf4j
public class GrpcContextDetails implements ServerInterceptor {

    public static final String ENVOY_ID_HEADER = "x-envoy-id";

    private static final Pattern cnPattern = Pattern.compile("cn=([^,]+)");
    private static final Context.Key<String> TENANT_ID = Context.key("tenantId");
    private static final Context.Key<String> ENVOY_ID = Context.key("envoyId");
    private static final Context.Key<SocketAddress> REMOTE_ADDR = Context.key("remoteAddr");

    /**
     * @return the tenant ID of the current gRPC caller, which is obtained from the common name (CN)
     * of the client's authenticated certificate
     */
    public static String getCallerTenantId() {
        return TENANT_ID.get();
    }

    /**
     * @return the remote address of the current gRPC caller
     */
    public static SocketAddress getCallerRemoteAddress() {
        return REMOTE_ADDR.get();
    }

    /**
     * @return the Envoy instance ID of the current gRPC caller
     */
    public static String getCallerEnvoyId() {
        return ENVOY_ID.get();
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        final SocketAddress remoteAddr = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);

        final String envoyId = headers.get(Metadata.Key.of(ENVOY_ID_HEADER, Metadata.ASCII_STRING_MARSHALLER));

        final String tenantId;

        SSLSession sslSession = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if (sslSession != null) {
            try {
                final Principal peerPrincipal = sslSession.getPeerPrincipal();
                final X500Principal x500PeerPrincipal = (X500Principal) peerPrincipal;
                final String principalName = x500PeerPrincipal.getName(X500Principal.CANONICAL);
                final Matcher m = cnPattern.matcher(principalName);
                if (m.find()) {
                    tenantId = m.group(1);
                }
                else {
                    log.warn("Unable to parse CN from SSL session's principal={} from remoteAddr={}",
                            principalName, remoteAddr);
                    throw new StatusRuntimeException(Status.UNAUTHENTICATED);
                }
            } catch (SSLPeerUnverifiedException e) {
                log.warn("Unable to access peer principal from remoteAddr={}", remoteAddr, e);
                throw new StatusRuntimeException(Status.UNAUTHENTICATED);
            }
        }
        else {
            log.warn("Missing SSL session from remoteAddr={}", remoteAddr);
            throw new StatusRuntimeException(Status.UNAUTHENTICATED);
        }

        return Contexts.interceptCall(
                Context.current().withValues(
                    TENANT_ID, tenantId,
                    REMOTE_ADDR, remoteAddr,
                    ENVOY_ID, envoyId
                ),
                call, headers, next
        );
    }
}
