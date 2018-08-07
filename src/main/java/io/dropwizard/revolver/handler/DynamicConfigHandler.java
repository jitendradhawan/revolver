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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.lifecycle.Managed;
import io.dropwizard.revolver.RevolverBundle;
import io.dropwizard.revolver.core.config.RevolverConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.Charsets;

import java.net.URL;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class DynamicConfigHandler implements Managed {

    private RevolverConfig revolverConfig;

    private ScheduledExecutorService scheduledExecutorService;

    private ObjectMapper objectMapper;

    private String configAttribute;

    private String prevConfigHash;

    private long prevLoadTime;

    public DynamicConfigHandler(final String configAttribute,
                                RevolverConfig revolverConfig, ObjectMapper objectMapper) {
        this.configAttribute = configAttribute;
        this.revolverConfig = revolverConfig;
        this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        this.objectMapper = objectMapper.copy();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES, false);
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try {
            prevConfigHash = computeHash(loadConfigData());
            log.info("Initializing dynamic config handler... Config Hash: {}", prevConfigHash);
        } catch (Exception e) {
            log.error("Error fetching configuration", e);
        }
    }


    @Override
    public void start() {
        scheduledExecutorService.scheduleWithFixedDelay(this::refreshConfig, 120, revolverConfig.getConfigPollIntervalSeconds(), TimeUnit.SECONDS);
    }

    public String refreshConfig() {
        try {
            final String substituted = loadConfigData();
            String curHash = computeHash(substituted);
            log.info("Old Config Hash: {} | New Config Hash: {}", prevConfigHash, curHash);
            if (!prevConfigHash.equals(curHash)) {
                log.info("Refreshing config with new hash: {}", curHash);
                RevolverConfig revolverConfig = objectMapper.readValue(substituted, RevolverConfig.class);
                RevolverBundle.loadServiceConfiguration(revolverConfig);
                this.prevConfigHash = curHash;
                prevLoadTime = System.currentTimeMillis();
                return prevConfigHash;
            } else {
                log.info("No config changes detected. Not reloading config..");
                return prevConfigHash;
            }
        } catch (Exception e) {
            log.error("Error fetching configuration", e);
            return null;
        }
    }

    public Map<String, Object> configLoadInfo() {
        return ImmutableMap.<String, Object>builder()
                .put("hash", prevConfigHash)
                .put("loadTime", new Date(prevLoadTime))
                .build();
    }

    private String loadConfigData() throws Exception {
        log.info("Fetching configuration from dynamic url: {}", revolverConfig.getDynamicConfigUrl());
        JsonNode node = objectMapper.readTree(new YAMLFactory().createParser(new URL(revolverConfig.getDynamicConfigUrl())));
        EnvironmentVariableSubstitutor substitute = new EnvironmentVariableSubstitutor(false, true);
        return substitute.replace(node.get(configAttribute).toString());
    }

    private String computeHash(final String config) {
        return Hashing.sha256().hashString(config, Charsets.UTF_8).toString();
    }

    @Override
    public void stop() {
        scheduledExecutorService.shutdown();
    }
}
