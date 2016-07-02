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

package io.dropwizard.revolver.http;

import com.google.common.base.Strings;
import io.dropwizard.revolver.core.RevolverCommand;
import io.dropwizard.revolver.core.config.ClientConfig;
import io.dropwizard.revolver.core.config.RuntimeConfig;
import io.dropwizard.revolver.core.tracing.TraceCollector;
import io.dropwizard.revolver.core.util.RevolverCommandHelper;
import io.dropwizard.revolver.discovery.RevolverServiceResolver;
import io.dropwizard.revolver.discovery.model.Endpoint;
import io.dropwizard.revolver.http.config.RevolverHttpApiConfig;
import io.dropwizard.revolver.http.config.RevolverHttpServiceConfig;
import io.dropwizard.revolver.http.model.RevolverHttpRequest;
import io.dropwizard.revolver.http.model.RevolverHttpResponse;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import okhttp3.*;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.StrSubstitutor;

import javax.ws.rs.core.*;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * @author phaneesh
 */
@Slf4j
public class RevolverHttpCommand extends RevolverCommand<RevolverHttpRequest, RevolverHttpResponse, RevolverHttpContext, RevolverHttpServiceConfig, RevolverHttpApiConfig> {


    public static final String CALL_MODE_POLLING = "POLLING";
    public static final String CALL_MODE_CALLBACK = "CALLBACK";

    private final RevolverServiceResolver serviceResolver;
    private final OkHttpClient client;

    @Builder
    public RevolverHttpCommand(final RuntimeConfig runtimeConfig, final ClientConfig clientConfiguration,
                               final RevolverHttpServiceConfig serviceConfiguration,
                               final Map<String, RevolverHttpApiConfig> apiConfigurations,
                               final TraceCollector traceCollector, final RevolverServiceResolver serviceResolver)
            throws CertificateException, NoSuchAlgorithmException, KeyStoreException,
            IOException, KeyManagementException, UnrecoverableKeyException, ExecutionException {
        super(new RevolverHttpContext(), clientConfiguration, runtimeConfig, serviceConfiguration, apiConfigurations, traceCollector);
        (this.serviceResolver = serviceResolver).register(serviceConfiguration.getEndpoint());
        this.client = RevolverHttpClientFactory.buildClient(serviceConfiguration);
    }

    @Override
    public boolean isFallbackEnabled() {
        return false;
    }

    @Override
    protected RevolverHttpResponse execute(final RevolverHttpContext context, final RevolverHttpRequest request) throws Exception {
        final RevolverHttpApiConfig apiConfig = getApiConfigurations().get(request.getApi());
        if(apiConfig.getMethods().contains(request.getMethod())) {
            switch (request.getMethod()) {
                case GET: {
                    return doGet(request);
                }
                case POST: {
                    return doPost(request);
                }
                case PUT: {
                    return doPut(request);
                }
                case DELETE: {
                    return doDelete(request);
                }
                case HEAD: {
                    return doHead(request);
                }
                case OPTIONS: {
                    return doOptions(request);
                }
                case PATCH: {
                    return doPatch(request);
                }
            }
        }
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.putSingle("X-REQUEST-PATH", request.getPath());
        headers.putSingle("X-REQUEST-METHOD", request.getMethod().name());
        headers.putSingle("X-REQUEST-API", apiConfig.getApi());
        return RevolverHttpResponse.builder()
                .headers(headers)
                .statusCode(javax.ws.rs.core.Response.Status.BAD_REQUEST.getStatusCode()).build();
    }

    @Override
    protected RevolverHttpResponse fallback(final RevolverHttpContext context, final RevolverHttpRequest requestType) {
        log.error("Fallback triggered for command: " + RevolverCommandHelper.getName(requestType));
        return null;
    }

    private RevolverHttpResponse executeRequest(final RevolverHttpApiConfig apiConfiguration, final Request request, final boolean readBody) throws Exception {
        try {
            val response = client.newCall(request).execute();
            return this.getHttpResponse(apiConfiguration, response, readBody);
        } catch (Exception e) {
            log.error("Error running HTTP GET call: ", e);
            throw e;
        }
    }

    private RevolverHttpResponse doGet(final RevolverHttpRequest request) throws Exception {
        val apiConfiguration = this.getApiConfigurations().get(request.getApi());
        val endpoint = this.serviceResolver.resolve((this.getServiceConfiguration()).getEndpoint());
        val url = generateURI(request, apiConfiguration, endpoint);

        val httpRequest = new Request.Builder()
                .url(url);
        httpRequest.get();
        if (null != request.getHeaders()) {
            request.getHeaders().forEach((key, values) -> values.forEach(value -> httpRequest.addHeader(key, value)));
        }
        trackingHeaders(request, httpRequest);
        return executeRequest(apiConfiguration, httpRequest.build(), true);
    }

    private RevolverHttpResponse doOptions(final RevolverHttpRequest request) throws Exception {
        val apiConfiguration = this.getApiConfigurations().get(request.getApi());
        val endpoint = this.serviceResolver.resolve((this.getServiceConfiguration()).getEndpoint());
        val url = generateURI(request, apiConfiguration, endpoint);
        val httpRequest = new Request.Builder()
                .url(url);
        httpRequest.method("OPTIONS", null);
        if (null != request.getHeaders()) {
            request.getHeaders().forEach((key, values) -> values.forEach(value -> httpRequest.addHeader(key, value)));
        }
        trackingHeaders(request, httpRequest);
        return executeRequest(apiConfiguration, httpRequest.build(), true);
    }

    private RevolverHttpResponse doHead(final RevolverHttpRequest request) throws Exception {
        val apiConfiguration = this.getApiConfigurations().get(request.getApi());
        val endpoint = this.serviceResolver.resolve((this.getServiceConfiguration()).getEndpoint());
        val url = generateURI(request, apiConfiguration, endpoint);
        val httpRequest = new Request.Builder()
                .url(url);
        httpRequest.head();
        if (null != request.getHeaders()) {
            request.getHeaders().forEach((key, values) -> values.forEach(value -> httpRequest.addHeader(key, value)));
        }
        trackingHeaders(request, httpRequest);
        return executeRequest(apiConfiguration, httpRequest.build(), false);
    }

    private RevolverHttpResponse doDelete(final RevolverHttpRequest request) throws Exception {
        val apiConfiguration = this.getApiConfigurations().get(request.getApi());
        val endpoint = this.serviceResolver.resolve((this.getServiceConfiguration()).getEndpoint());
        val url = generateURI(request, apiConfiguration, endpoint);
        val httpRequest = new Request.Builder()
                .url(url);
        httpRequest.delete();
        if (null != request.getHeaders()) {
            request.getHeaders().forEach((key, values) -> values.forEach(value -> httpRequest.addHeader(key, value)));
        }
        trackingHeaders(request, httpRequest);
        return executeRequest(apiConfiguration, httpRequest.build(), true);
    }

    private RevolverHttpResponse doPatch(final RevolverHttpRequest request) throws Exception {
        val apiConfiguration = this.getApiConfigurations().get(request.getApi());
        val endpoint = this.serviceResolver.resolve((this.getServiceConfiguration()).getEndpoint());
        val url = generateURI(request, apiConfiguration, endpoint);
        val httpRequest = new Request.Builder()
                .url(url);
        if (null != request.getHeaders()) {
            request.getHeaders().forEach((key, values) -> values.forEach(value -> httpRequest.addHeader(key, value)));
        }
        if(request.getBody() != null) {
            if(null != request.getHeaders() && StringUtils.isNotBlank(request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)))
                httpRequest.patch(RequestBody.create(MediaType.parse(request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)), request.getBody()));
            else
                httpRequest.patch(RequestBody.create(MediaType.parse("*/*"), request.getBody()));
        } else {
            httpRequest.patch(RequestBody.create(MediaType.parse("*/*"), new byte[0]));
        }
        trackingHeaders(request, httpRequest);
        return executeRequest(apiConfiguration, httpRequest.build(), true);
    }

    private RevolverHttpResponse doPost(final RevolverHttpRequest request) throws Exception {
        val apiConfiguration = this.getApiConfigurations().get(request.getApi());
        val endpoint = this.serviceResolver.resolve((this.getServiceConfiguration()).getEndpoint());
        val url = generateURI(request, apiConfiguration, endpoint);
        val httpRequest = new Request.Builder()
                .url(url);
        if (null != request.getHeaders()) {
            request.getHeaders()
                    .forEach((key, values) ->
                            values.forEach(value ->
                                    httpRequest.addHeader(key, value)));
        }
        if(request.getBody() != null) {
            if(null != request.getHeaders() && StringUtils.isNotBlank(request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)))
                httpRequest.post(RequestBody.create(MediaType.parse(request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)), request.getBody()));
            else
                httpRequest.post(RequestBody.create(MediaType.parse("*/*"), request.getBody()));
        } else {
            httpRequest.post(RequestBody.create(MediaType.parse("*/*"), new byte[0]));
        }
        trackingHeaders(request, httpRequest);
        return executeRequest(apiConfiguration, httpRequest.build(), true);
    }

    private RevolverHttpResponse doPut(final RevolverHttpRequest request) throws Exception {
        val apiConfiguration = this.getApiConfigurations().get(request.getApi());
        val endpoint = this.serviceResolver.resolve((this.getServiceConfiguration()).getEndpoint());
        val url = generateURI(request, apiConfiguration, endpoint);
        val httpRequest = new Request.Builder()
                .url(url);
        if (null != request.getHeaders()) {
            request.getHeaders().forEach((key, values) -> values.forEach(value -> httpRequest.addHeader(key, value)));
        }
        if(request.getBody() != null) {
            if(null != request.getHeaders() && StringUtils.isNotBlank(request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)))
                httpRequest.put(RequestBody.create(MediaType.parse(request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)), request.getBody()));
            else
                httpRequest.put(RequestBody.create(MediaType.parse("*/*"), request.getBody()));
        } else {
            httpRequest.put(RequestBody.create(MediaType.parse("*/*"), new byte[0]));
        }
        trackingHeaders(request, httpRequest);
        return executeRequest(apiConfiguration, httpRequest.build(), true);
    }

    private HttpUrl generateURI(final RevolverHttpRequest request, final RevolverHttpApiConfig apiConfiguration, final Endpoint endpoint) {
        val builder = new HttpUrl.Builder();
        addQueryParams(request, builder);
        if (getServiceConfiguration().isSecured())
            builder.scheme("https");
        else
            builder.scheme("http");
        builder.host(endpoint.getHost()).port(endpoint.getPort()).encodedPath(resolvePath(apiConfiguration, request));
        return builder.build();
    }

    private RevolverHttpResponse getHttpResponse(final RevolverHttpApiConfig apiConfiguration, final Response response, final boolean readBody) throws Exception {
        if (apiConfiguration.getAcceptableResponseCodes() != null && !apiConfiguration.getAcceptableResponseCodes().isEmpty() && !apiConfiguration.getAcceptableResponseCodes().contains(response.code())) {
            if (response.body() != null) {
                log.error("Response: " + response.body().string());
            }
            throw new Exception(String.format("HTTP %s %s failed with [%d - %s]", new Object[]{apiConfiguration.getMethods(), apiConfiguration.getApi(), response.code(), response.message()}));
        }
        val headers = new MultivaluedHashMap<String, String>();
        response.headers().names().stream().forEach( h -> headers.putSingle(h, response.header(h)));
        val revolverResponse = RevolverHttpResponse.builder()
                .statusCode(response.code())
                .headers(headers);
        if(readBody) {
            revolverResponse.body(response.body().bytes());
        }
        return revolverResponse.build();
    }

    private String resolvePath(final RevolverHttpApiConfig httpApiConfiguration, final RevolverHttpRequest request) {
        String uri = null;
        if (Strings.isNullOrEmpty(request.getPath())) {
            if (null != request.getPathParams()) {
                uri = StrSubstitutor.replace(httpApiConfiguration.getPath(), request.getPathParams());
            }
        } else {
            uri = request.getPath();
        }
        if (Strings.isNullOrEmpty(uri)) {
            uri = httpApiConfiguration.getPath();
        }
        return uri.charAt(0) == '/' ? uri : "/" + uri;
    }

    private void addQueryParams(final RevolverHttpRequest request, final HttpUrl.Builder builder) {
        if (null != request.getQueryParams()) {
            request.getQueryParams().forEach((key, values) -> values.forEach(value -> builder.addQueryParameter(key, value)));
        }
    }

    private void trackingHeaders(final RevolverHttpRequest request, final Request.Builder requestBuilder) {
        if (!getServiceConfiguration().isTrackingHeaders()) {
            return;
        }
        val spanInfo = request.getTrace();
        if(request.getHeaders() == null) {
            request.setHeaders(new MultivaluedHashMap<>());
        }
        List<String> existing = request.getHeaders().keySet().stream().map(String::toLowerCase).collect(Collectors.toList());
        if (!existing.contains(RevolversHttpHeaders.TXN_ID_HEADER.toLowerCase())) {
            requestBuilder.addHeader(RevolversHttpHeaders.TXN_ID_HEADER, spanInfo.getTransactionId());
        }
        if (!existing.contains(RevolversHttpHeaders.REQUEST_ID_HEADER.toLowerCase())) {
            requestBuilder.addHeader(RevolversHttpHeaders.REQUEST_ID_HEADER, spanInfo.getRequestId());
        }
        if (!existing.contains(RevolversHttpHeaders.PARENT_REQUEST_ID_HEADER.toLowerCase())) {
            requestBuilder.addHeader(RevolversHttpHeaders.PARENT_REQUEST_ID_HEADER, spanInfo.getParentRequestId());
        }
        if (!existing.contains(RevolversHttpHeaders.TIMESTAMP_HEADER.toLowerCase())) {
            requestBuilder.addHeader(RevolversHttpHeaders.TIMESTAMP_HEADER, Long.toString(spanInfo.getTimestamp()));
        }
        if (!existing.contains(RevolversHttpHeaders.CLIENT_HEADER.toLowerCase())) {
            requestBuilder.addHeader(RevolversHttpHeaders.CLIENT_HEADER, this.getClientConfiguration().getClientName());
        }
    }

}
