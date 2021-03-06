package org.appenders.log4j2.elasticsearch.bulkprocessor;

/*-
 * #%L
 * log4j2-elasticsearch
 * %%
 * Copyright (C) 2017 Rafal Foltynski
 * %%
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * #L%
 */


import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.function.Function;

import org.apache.logging.log4j.core.config.ConfigurationException;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.Required;

import org.appenders.log4j2.elasticsearch.Auth;
import org.appenders.log4j2.elasticsearch.BatchOperations;
import org.appenders.log4j2.elasticsearch.ClientObjectFactory;
import org.appenders.log4j2.elasticsearch.ClientProvider;
import org.appenders.log4j2.elasticsearch.FailoverPolicy;
import org.appenders.log4j2.elasticsearch.IndexTemplate;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkRequestIntrospector;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.transport.client.PreBuiltTransportClient;

@Plugin(name = BulkProcessorObjectFactory.PLUGIN_NAME, category = Node.CATEGORY, elementType = ClientObjectFactory.ELEMENT_TYPE, printObject = true)
public class BulkProcessorObjectFactory implements ClientObjectFactory<TransportClient, BulkRequest> {

    static final String PLUGIN_NAME = "ElasticsearchBulkProcessor";

    private final Collection<String> serverUris;
    private final UriParser uriParser = new UriParser();
    private final Auth auth;

    private TransportClient client;

    protected BulkProcessorObjectFactory(Collection<String> serverUris, Auth auth) {
        this.serverUris = serverUris;
        this.auth = auth;
    }

    @Override
    public Collection<String> getServerList() {
        return new ArrayList<>(serverUris);
    }

    @Override
    public TransportClient createClient() {
        if (client == null) {
            TransportClient client = getClientProvider().createClient();
            for (String serverUri : serverUris) {
                try {
                    String host = uriParser.getHost(serverUri);
                    int port = uriParser.getPort(serverUri);
                    client.addTransportAddress(new TransportAddress(InetAddress.getByName(host), port));
                } catch (UnknownHostException e) {
                    throw new ConfigurationException(e.getMessage());
                }
            }
            this.client = client;
        }
        return client;
    }

    // visible for testing
    ClientProvider<TransportClient> getClientProvider() {
        return auth == null ? new InsecureTransportClientProvider() : new SecureClientProvider(auth);
    }

    @Override
    public Function<BulkRequest, Boolean> createBatchListener(FailoverPolicy failoverPolicy) {
        return noop -> true;
    }

    @Override
    public Function<BulkRequest, Boolean> createFailureHandler(FailoverPolicy failover) {
        return new Function<BulkRequest, Boolean>() {

            private final BulkRequestIntrospector introspector = new BulkRequestIntrospector();

            @Override
            public Boolean apply(BulkRequest bulk) {
                introspector.items(bulk).forEach(failedItem -> failover.deliver(failedItem));
                return true;
            }

        };
    }

    @Override
    public BatchOperations<BulkRequest> createBatchOperations() {
        return new ElasticsearchBatchOperations();
    }

    @Override
    public void execute(IndexTemplate indexTemplate) {
        try {
            createClient().admin().indices().putTemplate(
                    new PutIndexTemplateRequest()
                            .name(indexTemplate.getName())
                            .source(indexTemplate.getSource(), XContentType.JSON)
            );
        } catch (Exception e) {
            throw new ConfigurationException(e.getMessage(), e);
        }
    }

    @PluginBuilderFactory
    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder implements org.apache.logging.log4j.core.util.Builder<BulkProcessorObjectFactory> {

        @PluginBuilderAttribute
        @Required(message = "No serverUris provided for " + PLUGIN_NAME)
        private String serverUris;

        @PluginElement("auth")
        private Auth auth;

        @Override
        public BulkProcessorObjectFactory build() {
            if (serverUris == null) {
                throw new ConfigurationException("No serverUris provided for " + PLUGIN_NAME);
            }
            return new BulkProcessorObjectFactory(Arrays.asList(serverUris.split(";")), auth);
        }

        public Builder withServerUris(String serverUris) {
            this.serverUris = serverUris;
            return this;
        }

        public Builder withAuth(Auth auth) {
            this.auth = auth;
            return this;
        }
    }

    static class InsecureTransportClientProvider implements ClientProvider<TransportClient> {

        @Override
        public TransportClient createClient() {
            return new PreBuiltTransportClient(Settings.Builder.EMPTY_SETTINGS, Collections.EMPTY_LIST);
        }

    }

}
