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

package io.dropwizard.revolver.resource;

import com.codahale.metrics.annotation.Metered;
import com.google.common.collect.ImmutableMap;
import io.dropwizard.revolver.RevolverBundle;
import io.swagger.annotations.ApiOperation;
import java.util.stream.Collectors;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Path("/revolver")
@Slf4j
@Data
@Singleton
public class RevolverApiManageResource {

    @Builder
    public RevolverApiManageResource() {

    }

    @Path("/v1/manage/api/status/{service}/{api}")
    @GET
    @Metered
    @ApiOperation(value = "API Status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStatus(@PathParam("service") String service,
            @PathParam("api") String api) {
        String key = service + "." + api;
        if (RevolverBundle.apiStatus.containsKey(key)) {
            return Response.ok(ImmutableMap.<String, Object>builder().put("service", service)
                    .put("api", api)
                    .put("status", RevolverBundle.apiStatus.get(service + "." + api)).build())
                    .build();
        } else {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ImmutableMap.<String, Object>builder().put("service", service)
                            .put("api", api).build()).build();
        }
    }


    @Path("/v1/manage/api/status/{service}/{api}/enable")
    @POST
    @Metered
    @ApiOperation(value = "Enable API")
    @Produces(MediaType.APPLICATION_JSON)
    public Response enable(@PathParam("service") String service,
            @PathParam("api") String api) {
        String key = service + "." + api;
        if (RevolverBundle.apiStatus.containsKey(key)) {
            RevolverBundle.apiStatus.put(key, true);
            return Response.ok(ImmutableMap.<String, Object>builder().put("service", service)
                    .put("api", api)
                    .put("status", RevolverBundle.apiStatus.get(service + "." + api)).build())
                    .build();
        } else {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ImmutableMap.<String, Object>builder().put("service", service)
                            .put("api", api).build()).build();
        }
    }


    @Path("/v1/manage/api/status/{service}/{api}/disable")
    @POST
    @Metered
    @ApiOperation(value = "Disable API")
    @Produces(MediaType.APPLICATION_JSON)
    public Response disable(@PathParam("service") String service,
            @PathParam("api") String api) {
        String key = service + "." + api;
        if (RevolverBundle.apiStatus.containsKey(key)) {
            RevolverBundle.apiStatus.put(key, false);
            return Response.ok(ImmutableMap.<String, Object>builder().put("service", service)
                    .put("api", api)
                    .put("status", RevolverBundle.apiStatus.get(service + "." + api)).build())
                    .build();
        } else {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ImmutableMap.<String, Object>builder().put("service", service)
                            .put("api", api).build()).build();
        }
    }

    @Path("/v1/manage/api/status")
    @GET
    @Metered
    @ApiOperation(value = "Full API Status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status() {
        return Response.ok(RevolverBundle.apiStatus.entrySet().stream().map(e -> {
            String[] key = e.getKey().split("\\.");
            return ImmutableMap.<String, Object>builder().put("service", key[0]).put("api", key[1])
                    .put("status", e.getValue()).build();
        }).collect(Collectors.toList())).build();
    }
}
