/*
 *  Copyright (c) 2024 Bayerische Motoren Werke Aktiengesellschaft (BMW AG)
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Bayerische Motoren Werke Aktiengesellschaft (BMW AG) - initial API and implementation
 *
 */

package org.eclipse.edc.test.e2e.protocol;

import jakarta.json.Json;
import org.eclipse.edc.connector.controlplane.asset.spi.domain.Asset;
import org.eclipse.edc.connector.controlplane.asset.spi.index.AssetIndex;
import org.eclipse.edc.connector.controlplane.contract.spi.offer.store.ContractDefinitionStore;
import org.eclipse.edc.connector.controlplane.contract.spi.types.offer.ContractDefinition;
import org.eclipse.edc.connector.controlplane.policy.spi.PolicyDefinition;
import org.eclipse.edc.connector.controlplane.policy.spi.store.PolicyDefinitionStore;
import org.eclipse.edc.connector.controlplane.services.spi.protocol.ProtocolVersionRegistry;
import org.eclipse.edc.junit.annotations.EndToEndTest;
import org.eclipse.edc.junit.extensions.EmbeddedRuntime;
import org.eclipse.edc.junit.extensions.RuntimeExtension;
import org.eclipse.edc.junit.extensions.RuntimePerClassExtension;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.util.io.Ports;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static jakarta.json.Json.createObjectBuilder;
import static java.util.Arrays.stream;
import static java.util.stream.IntStream.range;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.jsonld.spi.JsonLdKeywords.CONTEXT;
import static org.eclipse.edc.jsonld.spi.JsonLdKeywords.TYPE;
import static org.eclipse.edc.jsonld.spi.JsonLdKeywords.VOCAB;
import static org.eclipse.edc.jsonld.spi.Namespaces.DSPACE_SCHEMA;
import static org.eclipse.edc.protocol.dsp.spi.type.DspCatalogPropertyAndTypeNames.DSPACE_PROPERTY_FILTER;
import static org.eclipse.edc.protocol.dsp.spi.type.DspCatalogPropertyAndTypeNames.DSPACE_TYPE_CATALOG_REQUEST_MESSAGE;
import static org.eclipse.edc.protocol.dsp.spi.version.DspVersions.V_2024_1;
import static org.eclipse.edc.spi.constants.CoreConstants.EDC_NAMESPACE;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.not;

@EndToEndTest
public class DspCatalogApiEndToEndTest {

    private static final int PROTOCOL_PORT = Ports.getFreePort();

    @RegisterExtension
    static RuntimeExtension runtime = new RuntimePerClassExtension(new EmbeddedRuntime(
            "runtime",
            Map.of(
                    "web.http.protocol.path", "/protocol",
                    "web.http.protocol.port", String.valueOf(PROTOCOL_PORT)
            ),
            ":data-protocols:dsp:dsp-catalog:dsp-catalog-http-api",
            ":data-protocols:dsp:dsp-catalog:dsp-catalog-transform",
            ":data-protocols:dsp:dsp-http-api-configuration",
            ":data-protocols:dsp:dsp-http-core",
            ":extensions:common:iam:iam-mock",
            ":core:control-plane:control-plane-aggregate-services",
            ":core:control-plane:control-plane-core",
            ":extensions:common:http"
    ));

    @Test
    void shouldExposeVersion2024_1() {
        given()
                .port(PROTOCOL_PORT)
                .basePath("/protocol")
                .contentType(JSON)
                .header("Authorization", "{\"region\": \"any\", \"audience\": \"any\", \"clientId\":\"any\"}")
                .body(createObjectBuilder()
                        .add(CONTEXT, createObjectBuilder().add(VOCAB, EDC_NAMESPACE))
                        .add(TYPE, DSPACE_TYPE_CATALOG_REQUEST_MESSAGE)
                        .build())
                .post("/2024/1/catalog/request")
                .then()
                .log().ifValidationFails()
                .statusCode(200)
                .contentType(JSON)
                .body("'dspace:participantId'", notNullValue());

        assertThat(runtime.getService(ProtocolVersionRegistry.class).getAll().protocolVersions())
                .contains(V_2024_1);
    }

    @Test
    void shouldPermitPaginationWithLinkHeader() {
        var assetIndex = runtime.getService(AssetIndex.class);
        range(0, 8)
                .mapToObj(i -> Asset.Builder.newInstance().id(i + "").dataAddress(DataAddress.Builder.newInstance().type("any").build()).build())
                .forEach(assetIndex::create);
        var policyDefinitionStore = runtime.getService(PolicyDefinitionStore.class);
        policyDefinitionStore.create(PolicyDefinition.Builder.newInstance().policy(Policy.Builder.newInstance().build()).build())
                .onSuccess(policy -> {
                    var contractDefinition = ContractDefinition.Builder.newInstance().accessPolicyId(policy.getId()).contractPolicyId(policy.getId()).build();
                    runtime.getService(ContractDefinitionStore.class).save(contractDefinition);
                });

        var link = given()
                .port(PROTOCOL_PORT)
                .basePath("/protocol")
                .contentType(JSON)
                .header("Authorization", "{\"region\": \"any\", \"audience\": \"any\", \"clientId\":\"any\"}")
                .body(createObjectBuilder()
                        .add(CONTEXT, createObjectBuilder().add(VOCAB, EDC_NAMESPACE))
                        .add(TYPE, DSPACE_TYPE_CATALOG_REQUEST_MESSAGE)
                        .add(DSPACE_PROPERTY_FILTER, Json.createObjectBuilder()
                                .add("offset", 0)
                                .add("limit", 5))
                        .build())
                .post("/2024/1/catalog/request")
                .then()
                .log().ifValidationFails()
                .statusCode(200)
                .contentType(JSON)
                .body("'dcat:dataset'.size()", equalTo(5))
                .header("Link", containsString("/2024/1/catalog/request"))
                .header("Link", containsString("next"))
                .header("Link", not(containsString("prev")))
                .extract().header("Link");

        var nextPageUrl = stream(link.split(", ")).filter(it -> it.endsWith("\"next\"")).findAny()
                .map(it -> it.split(">;")).map(it -> it[0]).map(it -> it.substring(1))
                .orElseThrow();

        given()
                .contentType(JSON)
                .header("Authorization", "{\"region\": \"any\", \"audience\": \"any\", \"clientId\":\"any\"}")
                .body(createObjectBuilder()
                        .add(CONTEXT, createObjectBuilder().add(VOCAB, EDC_NAMESPACE))
                        .add(TYPE, DSPACE_TYPE_CATALOG_REQUEST_MESSAGE)
                        .build()
                )
                .post(nextPageUrl)
                .then()
                .log().ifValidationFails()
                .body("'dcat:dataset'.size()", equalTo(3))
                .statusCode(200)
                .contentType(JSON)
                .header("Link", containsString("/2024/1/catalog/request"))
                .header("Link", containsString("prev"))
                .header("Link", not(containsString("next")));
    }

    @Test
    void catalogRequest_shouldReturnError_whenNotAuthorized() {

        var authorizationHeader = """
                {"region": "any", "audience": "any", "clientId":"faultyClientId"}"
                """;
        given()
                .port(PROTOCOL_PORT)
                .basePath("/protocol")
                .contentType(JSON)
                .header("Authorization", authorizationHeader)
                .body(createObjectBuilder()
                        .add(CONTEXT, createObjectBuilder().add(VOCAB, EDC_NAMESPACE))
                        .add(TYPE, DSPACE_TYPE_CATALOG_REQUEST_MESSAGE)
                        .build())
                .post("/catalog/request")
                .then()
                .log().ifValidationFails()
                .statusCode(401)
                .contentType(JSON)
                .body("'@type'", equalTo("dspace:CatalogError"))
                .body("'dspace:code'", equalTo("401"))
                .body("'dspace:reason'", equalTo("Unauthorized"))
                .body("'@context'.dspace", equalTo(DSPACE_SCHEMA));

    }

    @Test
    void catalogRequest_shouldReturnError_whenMissingToken() {

        given()
                .port(PROTOCOL_PORT)
                .basePath("/protocol")
                .contentType(JSON)
                .body(createObjectBuilder()
                        .add(CONTEXT, createObjectBuilder().add(VOCAB, EDC_NAMESPACE))
                        .add(TYPE, DSPACE_TYPE_CATALOG_REQUEST_MESSAGE)
                        .build())
                .post("/catalog/request")
                .then()
                .log().ifValidationFails()
                .statusCode(401)
                .contentType(JSON)
                .body("'@type'", equalTo("dspace:CatalogError"))
                .body("'dspace:code'", equalTo("401"))
                .body("'dspace:reason'", equalTo("Unauthorized."))
                .body("'@context'.dspace", equalTo(DSPACE_SCHEMA));

    }

    @Test
    void catalogRequest_shouldReturnError_whenValidationFails() {
        var authorizationHeader = """
                {"region": "any", "audience": "any", "clientId":"any"}"
                """;
        given()
                .port(PROTOCOL_PORT)
                .basePath("/protocol")
                .header("Authorization", authorizationHeader)
                .contentType(JSON)
                .body(createObjectBuilder()
                        .add(CONTEXT, createObjectBuilder().add(VOCAB, EDC_NAMESPACE))
                        .add(TYPE, "FakeType")
                        .build())
                .post("/catalog/request")
                .then()
                .log().ifValidationFails()
                .statusCode(400)
                .contentType(JSON)
                .body("'@type'", equalTo("dspace:CatalogError"))
                .body("'dspace:code'", equalTo("400"))
                .body("'dspace:reason'", equalTo("Bad request."))
                .body("'@context'.dspace", equalTo(DSPACE_SCHEMA));

    }

    @Test
    void shouldReturnError_whenDatasetNotFound() {

        var id = UUID.randomUUID().toString();
        var authorizationHeader = """
                {"region": "any", "audience": "any", "clientId":"any"}"
                """;
        given()
                .port(PROTOCOL_PORT)
                .basePath("/protocol")
                .contentType(JSON)
                .header("Authorization", authorizationHeader)
                .get("/catalog/datasets/" + id)
                .then()
                .log().ifValidationFails()
                .statusCode(404)
                .contentType(JSON)
                .body("'@type'", equalTo("dspace:CatalogError"))
                .body("'dspace:code'", equalTo("404"))
                .body("'dspace:reason'", equalTo("Dataset %s does not exist".formatted(id)))
                .body("'@context'.dspace", equalTo(DSPACE_SCHEMA));

    }
}
