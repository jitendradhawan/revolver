/*
 * Copyright 2016 Phaneesh Nagaraja <phaneesh.n@gmail.com>.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package io.dropwizard.revolver.callback;

import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.dropwizard.revolver.RevolverBundle;
import io.dropwizard.revolver.base.core.RevolverCallbackRequest;
import io.dropwizard.revolver.base.core.RevolverCallbackResponse;
import io.dropwizard.revolver.base.core.RevolverRequestState;
import io.dropwizard.revolver.core.RevolverExecutionException;
import io.dropwizard.revolver.core.config.CommandHandlerConfig;
import io.dropwizard.revolver.core.config.HystrixCommandConfig;
import io.dropwizard.revolver.core.config.RevolverConfig;
import io.dropwizard.revolver.core.config.hystrix.ThreadPoolConfig;
import io.dropwizard.revolver.discovery.EndpointSpec;
import io.dropwizard.revolver.discovery.model.RangerEndpointSpec;
import io.dropwizard.revolver.discovery.model.SimpleEndpointSpec;
import io.dropwizard.revolver.http.RevolverHttpCommand;
import io.dropwizard.revolver.http.RevolversHttpHeaders;
import io.dropwizard.revolver.http.config.RevolverHttpApiConfig;
import io.dropwizard.revolver.http.config.RevolverHttpServiceConfig;
import io.dropwizard.revolver.http.model.RevolverHttpRequest;
import io.dropwizard.revolver.http.model.RevolverHttpResponse;
import io.dropwizard.revolver.persistence.PersistenceProvider;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * @author phaneesh
 */
@Data
@Slf4j
public class CallbackHandler {

    private PersistenceProvider persistenceProvider;

    private RevolverConfig revolverConfig;

    private LoadingCache<CallbackConfigKey, RevolverHttpServiceConfig> clientLoadingCache;

    @Builder
    public CallbackHandler(PersistenceProvider persistenceProvider, RevolverConfig revolverConfig) {
        this.persistenceProvider = persistenceProvider;
        this.revolverConfig = revolverConfig;
        this.clientLoadingCache = CacheBuilder.newBuilder()
                .build(new CacheLoader<CallbackConfigKey, RevolverHttpServiceConfig>() {
                    @Override
                    public RevolverHttpServiceConfig load(CallbackConfigKey key) throws Exception {
                        return buildConfiguration(key.callbackRequest, key.uri);
                    }
                });
    }

    @Data
    @Builder
    @EqualsAndHashCode (exclude = "callbackRequest")
    @ToString(exclude = "callbackRequest")
    @AllArgsConstructor
    public static class CallbackConfigKey {
        private URI uri;
        private RevolverCallbackRequest callbackRequest;
    }

    public void handle(final String requestId) {
        final RevolverCallbackRequest request = persistenceProvider.request(requestId);
        if (request == null) {
            log.warn("Invalid request: {}", requestId);
            return;
        }
        RevolverRequestState state = persistenceProvider.requestState(requestId);
        if (state == null) {
            log.warn("Invalid request state: {}", requestId);
            return;
        }
        if (state != RevolverRequestState.RESPONDED) {
            log.warn("Invalid request state {}: {}", state.name(), requestId);
            return;
        }
        if (Strings.isNullOrEmpty(request.getCallbackUri())) {
            log.warn("Invalid callback uri: {}", requestId);
            return;
        }
        try {
            URI uri = new URI(request.getCallbackUri());
            switch (uri.getScheme()) {
                case "https":
                case "http":
                    makeCallback(requestId, uri, request);
                    break;
                case "ranger":
                    log.warn("Ranger is not supported yet for request: {}", requestId);
                    break;
                default:
                    log.warn("Invalid protocol for request: {}", requestId);
            }
        } catch (Exception e) {
            log.error("Invalid callback uri {} for request: {}", request.getCallbackUri(), requestId, e);
        }
    }

    private void makeCallback(final String requestId, final URI uri, final RevolverCallbackRequest callbackRequest) {
        final RevolverCallbackResponse response = persistenceProvider.response(requestId);
        if (response == null) {
            log.warn("Invalid response: {}", requestId);
            return;
        }
        try {
            final RevolverHttpServiceConfig httpCommandConfig = clientLoadingCache.get(CallbackConfigKey.builder()
                    .callbackRequest(callbackRequest)
                    .uri(uri)
            .build());
            final RevolverHttpCommand httpCommand = getCommand(httpCommandConfig);
            final MultivaluedMap<String, String> requestHeaders = new MultivaluedHashMap<>();
            response.getHeaders().forEach(requestHeaders::put);
            //Remove host header
            requestHeaders.remove(HttpHeaders.HOST);
            String method = callbackRequest.getHeaders()
                    .getOrDefault(RevolversHttpHeaders.CALLBACK_METHOD_HEADER, Collections.singletonList("POST")).get(0);
            method = Strings.isNullOrEmpty(method) ? "POST" : method;
            final RevolverHttpRequest httpRequest = RevolverHttpRequest.builder()
                    .path(uri.getRawPath())
                    .api("callback")
                    .body(response.getBody() == null ? new byte[0] : response.getBody())
                    .headers(requestHeaders)
                    .method(RevolverHttpApiConfig.RequestMethod.valueOf(method))
                    .service(httpCommandConfig.getService())
                    .build();
            CompletableFuture<RevolverHttpResponse> httpResponseFuture = httpCommand.executeAsync(httpRequest);
            httpResponseFuture.thenAcceptAsync( httpResponse -> {
                if (httpResponse.getStatusCode() >= 200 && httpResponse.getStatusCode() <= 210) {
                    log.debug("Callback success: " + httpResponse.toString());
                } else {
                    log.error("Error from callback host: {} | Status Code: {} | Response Body: ", uri.getHost(),
                            httpResponse.getStatusCode(), httpResponse.getBody() != null ? new String(httpResponse.getBody()) : "NONE");
                }
            });
        } catch (MalformedURLException e) {
            log.error("Invalid callback URL: {} for request: {}", uri.toString(), requestId, e);
        } catch (IOException e) {
            log.error("Error making callback for: {} for request: {}", uri.toString(), requestId, e);
        } catch (CertificateException e) {
            log.error("Error making callback for: {} for request: {}", uri.toString(), requestId, e);
        } catch (NoSuchAlgorithmException e) {
            log.error("Error making callback for: {} for request: {}", uri.toString(), requestId, e);
        } catch (UnrecoverableKeyException e) {
            log.error("Error making callback for: {} for request: {}", uri.toString(), requestId, e);
        } catch (KeyStoreException e) {
            log.error("Error making callback for: {} for request: {}", uri.toString(), requestId, e);
        } catch (KeyManagementException e) {
            log.error("Error making callback for: {} for request: {}", uri.toString(), requestId, e);
        } catch (ExecutionException e) {
            log.error("Error making callback for: {} for request: {}", uri.toString(), requestId, e);
        }
    }

    private RevolverHttpServiceConfig buildConfiguration(final RevolverCallbackRequest callbackRequest, final URI uri) throws MalformedURLException {
        EndpointSpec endpointSpec = null;
        String apiName = "callback";
        String serviceName = uri.getHost().replace(".", "-");
        String type = null;
        String method = callbackRequest.getHeaders()
                .getOrDefault(RevolversHttpHeaders.CALLBACK_METHOD_HEADER, Collections.singletonList("POST")).get(0);
        method = Strings.isNullOrEmpty(method) ? "POST" : method;
        String timeout = callbackRequest.getHeaders()
                .getOrDefault(RevolversHttpHeaders.CALLBACK_TIMEOUT_HEADER, Collections.singletonList(String.valueOf(revolverConfig.getCallbackTimeout()))).get(0);
        timeout = Strings.isNullOrEmpty(timeout) ? String.valueOf(revolverConfig.getCallbackTimeout()) : timeout;
        switch (uri.getScheme()) {
            case "https":
            case "http":
                val simpleEndpoint = new SimpleEndpointSpec();
                simpleEndpoint.setHost(uri.getHost());
                simpleEndpoint.setPort((uri.getPort() == 0 || uri.getPort() == -1) ? 80 : uri.getPort());
                endpointSpec = simpleEndpoint;
                type = uri.getScheme();
                break;
            case "ranger": //format for ranger host: environment.service.api
                val rangerEndpoint = new RangerEndpointSpec();
                val discoveryData = uri.getHost().split("\\.");
                if (discoveryData.length != 3) {
                    throw new MalformedURLException("Invalid ranger host format. Accepted format is environment.service.api");
                }
                rangerEndpoint.setEnvironment(discoveryData[0]);
                rangerEndpoint.setService(discoveryData[1]);
                endpointSpec = rangerEndpoint;
                type = "ranger_sharded";
                apiName = discoveryData[2];
        }

        return RevolverHttpServiceConfig.builder()
                .authEnabled(false)
                .connectionPoolSize(10)
                .secured(uri.getScheme().equals("https"))
                .enpoint(endpointSpec)
                .service(serviceName)
                .type(type)
                .api(RevolverHttpApiConfig.configBuilder()
                        .api(apiName)
                        .method(RevolverHttpApiConfig.RequestMethod.valueOf(method))
                        .path(null)
                        .runtime(HystrixCommandConfig.builder()
                                .threadPool(ThreadPoolConfig.builder()
                                        .concurrency(10)
                                            .timeout(Integer.parseInt(timeout))
                                        .build())
                                .build()).build()).build();
    }

    private RevolverHttpCommand getCommand(final RevolverHttpServiceConfig httpConfig) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException, IOException, ExecutionException {
        try {
            return RevolverBundle.getHttpCommand(httpConfig.getService());
        } catch (RevolverExecutionException e) {
            RevolverBundle.addHttpCommand(httpConfig.getService(),
                    RevolverHttpCommand.builder()
                            .clientConfiguration(revolverConfig.getClientConfig())
                            .runtimeConfig(revolverConfig.getGlobal())
                            .serviceConfiguration(httpConfig)
                            .apiConfigurations(generateApiConfigMap(httpConfig))
                            .serviceResolver(RevolverBundle.getServiceNameResolver())
                            .traceCollector(trace -> {
                                //TODO: Put in a publisher if required
                            }).build()
            );
        }
        return RevolverBundle.getHttpCommand(httpConfig.getService());
    }

    private Map<String, RevolverHttpApiConfig> generateApiConfigMap(final RevolverHttpServiceConfig serviceConfiguration) {
        return serviceConfiguration.getApis().stream()
                .collect(Collectors.toMap(CommandHandlerConfig::getApi, apiConfig -> apiConfig));
    }
}
