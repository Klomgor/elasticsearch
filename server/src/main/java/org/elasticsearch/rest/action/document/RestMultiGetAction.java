/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.rest.action.document;

import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.Scope;
import org.elasticsearch.rest.ServerlessScope;
import org.elasticsearch.rest.action.RestToXContentListener;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.List;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.POST;

@ServerlessScope(Scope.PUBLIC)
public class RestMultiGetAction extends BaseRestHandler {
    public static final String TYPES_DEPRECATION_MESSAGE = "[types removal] Specifying types in multi get requests is deprecated.";

    private final boolean allowExplicitIndex;

    public RestMultiGetAction(Settings settings) {
        this.allowExplicitIndex = MULTI_ALLOW_EXPLICIT_INDEX.get(settings);
    }

    @Override
    public List<Route> routes() {
        return List.of(
            new Route(GET, "/_mget"),
            new Route(POST, "/_mget"),
            new Route(GET, "/{index}/_mget"),
            new Route(POST, "/{index}/_mget")
        );
    }

    @Override
    public String getName() {
        return "document_mget_action";
    }

    @Override
    public RestChannelConsumer prepareRequest(final RestRequest request, final NodeClient client) throws IOException {
        MultiGetRequest multiGetRequest = new MultiGetRequest();
        multiGetRequest.refresh(request.paramAsBoolean("refresh", multiGetRequest.refresh()));
        multiGetRequest.preference(request.param("preference"));
        multiGetRequest.realtime(request.paramAsBoolean("realtime", multiGetRequest.realtime()));
        if (request.paramAsBoolean("force_synthetic_source", false)) {
            multiGetRequest.setForceSyntheticSource(true);
        }

        if (request.param("fields") != null) {
            throw new IllegalArgumentException(
                "The parameter [fields] is no longer supported, "
                    + "please use [stored_fields] to retrieve stored fields or _source filtering if the field is not stored"
            );
        }
        String[] sFields = null;
        String sField = request.param("stored_fields");
        if (sField != null) {
            sFields = Strings.splitStringByCommaToArray(sField);
        }

        FetchSourceContext defaultFetchSource = FetchSourceContext.parseFromRestRequest(request);
        try (XContentParser parser = request.contentOrSourceParamParser()) {
            multiGetRequest.add(request.param("index"), sFields, defaultFetchSource, request.param("routing"), parser, allowExplicitIndex);
        }

        return channel -> client.multiGet(multiGetRequest, new RestToXContentListener<>(channel));
    }
}
