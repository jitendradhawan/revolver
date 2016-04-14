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
package io.dropwizard.revolver.handler;

import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import io.dropwizard.revolver.core.config.RevolverConfig;
import io.dropwizard.revolver.http.RevolversHttpHeaders;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import javax.annotation.Priority;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * @author phaneesh
 */
@Slf4j
@Provider
@Priority(Priorities.AUTHENTICATION)
public class RevolverCallbackRequestFilter implements ContainerRequestFilter {

    private static final String FORWARDED_FOR = "X-FORWARDED-FOR";

    private final RevolverConfig config;

    public RevolverCallbackRequestFilter(RevolverConfig config) {
        this.config = config;
    }

    @Override
    public void filter(final ContainerRequestContext containerRequestContext) throws IOException {
        String requestId = containerRequestContext.getHeaderString(RevolversHttpHeaders.REQUEST_ID_HEADER);
        val transactionId = containerRequestContext.getHeaderString(RevolversHttpHeaders.TXN_ID_HEADER);
        val host = containerRequestContext.getHeaderString(HttpHeaders.HOST);
        containerRequestContext.getHeaders().putSingle(FORWARDED_FOR, host);
        if(Strings.isNullOrEmpty(requestId)) {
            requestId = UUID.randomUUID().toString();
            containerRequestContext.getHeaders().putSingle(RevolversHttpHeaders.REQUEST_ID_HEADER, requestId);
        }
        if(Strings.isNullOrEmpty(transactionId)) {
            containerRequestContext.getHeaders().putSingle(RevolversHttpHeaders.TXN_ID_HEADER, requestId);
        }
        if(Strings.isNullOrEmpty(containerRequestContext.getHeaderString(RevolversHttpHeaders.TIMESTAMP_HEADER))) {
            containerRequestContext.getHeaders().putSingle(RevolversHttpHeaders.TIMESTAMP_HEADER, Instant.now().toString());
        }
        //Default Accept & Content-Type to application/json
        if(Strings.isNullOrEmpty(containerRequestContext.getHeaderString(HttpHeaders.ACCEPT))) {
            containerRequestContext.getHeaders().putSingle(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON);
        }
        if(Strings.isNullOrEmpty(containerRequestContext.getHeaderString(HttpHeaders.CONTENT_TYPE))) {
            containerRequestContext.getHeaders().putSingle(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);
        }
        //Default encoding to UTF-8
        if(Strings.isNullOrEmpty(containerRequestContext.getHeaderString(HttpHeaders.CONTENT_ENCODING))) {
            containerRequestContext.getHeaders().putSingle(HttpHeaders.CONTENT_ENCODING, Charsets.UTF_8.name());
        }
        //Check if callback is enabled
        if(!Strings.isNullOrEmpty(containerRequestContext.getHeaderString(RevolversHttpHeaders.CALLBACK_URI_HEADER))) {
            //Add timeout header if it is absent
            if(Strings.isNullOrEmpty(containerRequestContext.getHeaderString(RevolversHttpHeaders.CALLBACK_TIMEOUT_HEADER))) {
                containerRequestContext.getHeaders().putSingle(RevolversHttpHeaders.CALLBACK_TIMEOUT_HEADER, String.valueOf(config.getCallbackTimeout()));
            }
            //Add callback method header if it is absent
            if(Strings.isNullOrEmpty(containerRequestContext.getHeaderString(RevolversHttpHeaders.CALLBACK_METHOD_HEADER))) {
                containerRequestContext.getHeaders().putSingle(RevolversHttpHeaders.CALLBACK_METHOD_HEADER, "POST");
            }
        }

    }
}
