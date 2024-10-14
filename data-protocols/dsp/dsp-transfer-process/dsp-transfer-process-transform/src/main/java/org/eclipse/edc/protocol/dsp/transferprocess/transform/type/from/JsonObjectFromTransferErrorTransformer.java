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

package org.eclipse.edc.protocol.dsp.transferprocess.transform.type.from;

import jakarta.json.JsonBuilderFactory;
import jakarta.json.JsonObject;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.protocol.TransferError;
import org.eclipse.edc.jsonld.spi.transformer.AbstractJsonLdTransformer;
import org.eclipse.edc.transform.spi.TransformerContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static org.eclipse.edc.jsonld.spi.JsonLdKeywords.TYPE;
import static org.eclipse.edc.protocol.dsp.spi.type.DspPropertyAndTypeNames.DSPACE_PROPERTY_CODE;
import static org.eclipse.edc.protocol.dsp.spi.type.DspPropertyAndTypeNames.DSPACE_PROPERTY_REASON;
import static org.eclipse.edc.protocol.dsp.spi.type.DspTransferProcessPropertyAndTypeNames.DSPACE_TYPE_TRANSFER_ERROR;

/**
 * Transforms a {@link TransferError} to a {@link JsonObject} in JSON-LD expanded form.
 */
public class JsonObjectFromTransferErrorTransformer extends AbstractJsonLdTransformer<TransferError, JsonObject> {

    private final JsonBuilderFactory jsonFactory;

    public JsonObjectFromTransferErrorTransformer(JsonBuilderFactory jsonFactory) {
        super(TransferError.class, JsonObject.class);
        this.jsonFactory = jsonFactory;
    }

    @Override
    public @Nullable JsonObject transform(@NotNull TransferError error, @NotNull TransformerContext context) {
        return jsonFactory.createObjectBuilder()
                .add(TYPE, DSPACE_TYPE_TRANSFER_ERROR)
                .add(DSPACE_PROPERTY_CODE, error.getCode())
                .add(DSPACE_PROPERTY_REASON, jsonFactory.createArrayBuilder(error.getMessages()))
                .build();
    }
}
