/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.river.couchdb;

import org.apache.lucene.util.IOUtils;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.Base64;
import org.elasticsearch.common.collect.Maps;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.river.*;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.script.ScriptService;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

/**
 *
 */
public class CouchdbRiver extends AbstractRiverComponent implements River {

    private final Client client;

    private final String riverIndexName;

    private final String couchProtocol;
    private final String couchHost;
    private final int couchPort;
    private final String couchDb;
    private final String couchFilter;
    private final String couchFilterParamsUrl;
    private final String basicAuth;
    private final boolean noVerify;
    private final boolean couchIgnoreAttachments;
    private final TimeValue heartbeat;
    private final TimeValue readTimeout;

    private final String indexName;
    private final String typeName;
    private final int bulkSize;
    private final TimeValue bulkTimeout;
    private final int throttleSize;

    private final ExecutableScript script;

    private volatile Thread slurperThread;
    private volatile Thread indexerThread;
    private volatile boolean closed;

    private final BlockingQueue<String> stream;

    private final TimeValue bulkFlushInterval;
    private volatile BulkProcessor bulkProcessor;
    private final int maxConcurrentBulk;

    @SuppressWarnings({"unchecked"})
    @Inject
    public CouchdbRiver(RiverName riverName, RiverSettings settings, @RiverIndexName String riverIndexName, Client client, ScriptService scriptService) {
        super(riverName, settings);
        this.riverIndexName = riverIndexName;
        this.client = client;

        if (settings.settings().containsKey("couchdb")) {
            Map<String, Object> couchSettings = (Map<String, Object>) settings.settings().get("couchdb");
            couchProtocol = XContentMapValues.nodeStringValue(couchSettings.get("protocol"), "http");
            noVerify = XContentMapValues.nodeBooleanValue(couchSettings.get("no_verify"), false);
            couchHost = XContentMapValues.nodeStringValue(couchSettings.get("host"), "localhost");
            couchPort = XContentMapValues.nodeIntegerValue(couchSettings.get("port"), 5984);
            couchDb = XContentMapValues.nodeStringValue(couchSettings.get("db"), riverName.name());
            couchFilter = XContentMapValues.nodeStringValue(couchSettings.get("filter"), null);
            if (couchSettings.containsKey("filter_params")) {
                Map<String, Object> filterParams = (Map<String, Object>) couchSettings.get("filter_params");
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Object> entry : filterParams.entrySet()) {
                    try {
                        sb.append("&").append(URLEncoder.encode(entry.getKey(), "UTF-8")).append("=").append(URLEncoder.encode(entry.getValue().toString(), "UTF-8"));
                    } catch (UnsupportedEncodingException e) {
                        // should not happen...
                    }
                }
                couchFilterParamsUrl = sb.toString();
            } else {
                couchFilterParamsUrl = null;
            }
            heartbeat = XContentMapValues.nodeTimeValue(couchSettings.get("heartbeat"), TimeValue.timeValueSeconds(10));
            readTimeout = XContentMapValues.nodeTimeValue(couchSettings.get("read_timeout"), TimeValue.timeValueSeconds(heartbeat.getSeconds()*3));
            couchIgnoreAttachments = XContentMapValues.nodeBooleanValue(couchSettings.get("ignore_attachments"), false);
            if (couchSettings.containsKey("user") && couchSettings.containsKey("password")) {
                String user = couchSettings.get("user").toString();
                String password = couchSettings.get("password").toString();
                basicAuth = "Basic " + Base64.encodeBytes((user + ":" + password).getBytes());
            } else {
                basicAuth = null;
            }

            if (couchSettings.containsKey("script")) {
                String scriptType = "groovy";
                if(couchSettings.containsKey("script_type")) {
                    scriptType = couchSettings.get("script_type").toString();
                }

                script = scriptService.executable(scriptType, couchSettings.get("script").toString(),
                        ScriptService.ScriptType.INLINE, Maps.<String, Object>newHashMap());
            } else {
                script = null;
            }
        } else {
            couchProtocol = "http";
            couchHost = "localhost";
            couchPort = 5984;
            couchDb = riverName.name();
            couchFilter = null;
            couchFilterParamsUrl = null;
            couchIgnoreAttachments = false;
            heartbeat = TimeValue.timeValueSeconds(10);
            readTimeout = TimeValue.timeValueSeconds(heartbeat.getSeconds()*3);
            noVerify = false;
            basicAuth = null;
            script = null;
        }

        if (settings.settings().containsKey("index")) {
            Map<String, Object> indexSettings = (Map<String, Object>) settings.settings().get("index");
            indexName = XContentMapValues.nodeStringValue(indexSettings.get("index"), couchDb);
            typeName = XContentMapValues.nodeStringValue(indexSettings.get("type"), couchDb);
            bulkSize = XContentMapValues.nodeIntegerValue(indexSettings.get("bulk_size"), 100);
            if (indexSettings.containsKey("bulk_timeout")) {
                bulkTimeout = TimeValue.parseTimeValue(XContentMapValues.nodeStringValue(indexSettings.get("bulk_timeout"), "10ms"), TimeValue.timeValueMillis(10));
            } else {
                bulkTimeout = TimeValue.timeValueMillis(10);
            }
            this.bulkFlushInterval = TimeValue.parseTimeValue(XContentMapValues.nodeStringValue(
                    indexSettings.get("flush_interval"), "5s"), TimeValue.timeValueSeconds(5));
            this.maxConcurrentBulk = XContentMapValues.nodeIntegerValue(indexSettings.get("max_concurrent_bulk"), 1);
            throttleSize = XContentMapValues.nodeIntegerValue(indexSettings.get("throttle_size"), bulkSize * 5);
        } else {
            indexName = couchDb;
            typeName = couchDb;
            bulkSize = 100;
            bulkTimeout = TimeValue.timeValueMillis(10);
            throttleSize = bulkSize * 5;
            this.maxConcurrentBulk = 1;
            this.bulkFlushInterval = TimeValue.timeValueSeconds(5);
        }
        if (throttleSize == -1) {
            stream = new LinkedTransferQueue<String>();
        } else {
            stream = new ArrayBlockingQueue<String>(throttleSize);
        }
    }

    @Override
    public void start() {
        logger.info("starting couchdb stream: host [{}], port [{}], filter [{}], db [{}], indexing to [{}]/[{}]", couchHost, couchPort, couchFilter, couchDb, indexName, typeName);

        // Creating bulk processor
        this.bulkProcessor = BulkProcessor.builder(client, new BulkProcessor.Listener() {
            @Override
            public void beforeBulk(long executionId, BulkRequest request) {
                logger.debug("Going to execute new bulk composed of {} actions", request.numberOfActions());
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
                logger.debug("Executed bulk composed of {} actions", request.numberOfActions());
                if (response.hasFailures()) {
                    logger.warn("There was failures while executing bulk", response.buildFailureMessage());
                    if (logger.isDebugEnabled()) {
                        for (BulkItemResponse item : response.getItems()) {
                            if (item.isFailed()) {
                                logger.debug("Error for {}/{}/{} for {} operation: {}", item.getIndex(),
                                        item.getType(), item.getId(), item.getOpType(), item.getFailureMessage());
                            }
                        }
                    }
                }
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
                logger.warn("Error executing bulk", failure);
            }
        })
                .setBulkActions(bulkSize)
                .setConcurrentRequests(maxConcurrentBulk)
                .setFlushInterval(bulkFlushInterval)
                .build();


        slurperThread = EsExecutors.daemonThreadFactory(settings.globalSettings(), "couchdb_river_slurper").newThread(new Slurper());
        indexerThread = EsExecutors.daemonThreadFactory(settings.globalSettings(), "couchdb_river_indexer").newThread(new Indexer());
        indexerThread.start();
        slurperThread.start();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        logger.info("closing couchdb stream river");
        if (slurperThread != null) {
            slurperThread.interrupt();
        }
        if (indexerThread != null) {
            indexerThread.interrupt();
        }

        closed = true;

        if (this.bulkProcessor != null) {
            this.bulkProcessor.close();
        }
    }

    @SuppressWarnings({"unchecked"})
    private Object processLine(String s) {
        Map<String, Object> ctx;
        try {
            ctx = XContentFactory.xContent(XContentType.JSON).createParser(s).mapAndClose();
        } catch (IOException e) {
            logger.warn("failed to parse {}", e, s);
            return null;
        }
        if (ctx.containsKey("error")) {
            logger.warn("received error {}", s);
            return null;
        }
        Object seq = ctx.get("seq");
        Object oId = ctx.get("id");
        if (oId == null) {
            return null;
        }

        String id = oId.toString();

        if (closed) {
            logger.warn("river was closing while processing couchdb doc [{}]. Operation skipped.", id);
            return null;
        }

        // Ignore design documents
        if (id.startsWith("_design/")) {
            if (logger.isTraceEnabled()) {
                logger.trace("ignoring design document {}", id);
            }
            return seq;
        }

        if (script != null) {
            script.setNextVar("ctx", ctx);
            try {
                script.run();
                // we need to unwrap the ctx...
                ctx = (Map<String, Object>) script.unwrap(ctx);
            } catch (Exception e) {
                logger.warn("failed to script process {}, ignoring", e, ctx);
                return seq;
            }
        }

        if (ctx.containsKey("ignore") && ctx.get("ignore").equals(Boolean.TRUE)) {
            // ignore dock
        } else if (ctx.containsKey("deleted") && ctx.get("deleted").equals(Boolean.TRUE)) {
            String index = extractIndex(ctx);
            String type = extractType(ctx);
            if (logger.isTraceEnabled()) {
                logger.trace("processing [delete]: [{}]/[{}]/[{}]", index, type, id);
            }
            if (closed) {
                logger.warn("river was closing while trying to delete document [{}/{}/{}]. Operation skipped.", index, type, id);
                return null;
            }
            bulkProcessor.add(new DeleteRequest(index, type, id).routing(extractRouting(ctx)).parent(extractParent(ctx)));
        } else if (ctx.containsKey("doc")) {
            String index = extractIndex(ctx);
            String type = extractType(ctx);
            Map<String, Object> doc = (Map<String, Object>) ctx.get("doc");

            // Remove _attachment from doc if needed
            if (couchIgnoreAttachments) {
                // no need to log that we removed it, the doc indexed will be shown without it
                doc.remove("_attachments");
            } else {
                // TODO by now, couchDB river does not really store attachments but only attachments meta infomration
                // So we perhaps need to fully support attachments
            }

            if (logger.isTraceEnabled()) {
                logger.trace("processing [index ]: [{}]/[{}]/[{}], source {}", index, type, id, doc);
            }
            if (closed) {
                logger.warn("river was closing while trying to index document [{}/{}/{}]. Operation skipped.", index, type, id);
                return null;
            }
            bulkProcessor.add(new IndexRequest(index, type, id).source(doc).routing(extractRouting(ctx)).parent(extractParent(ctx)));
        } else {
            logger.warn("ignoring unknown change {}", s);
        }
        return seq;
    }

    private String extractParent(Map<String, Object> ctx) {
        return (String) ctx.get("_parent");
    }

    private String extractRouting(Map<String, Object> ctx) {
        return (String) ctx.get("_routing");
    }

    private String extractType(Map<String, Object> ctx) {
        String type = (String) ctx.get("_type");
        if (type == null) {
            type = typeName;
        }
        return type;
    }

    private String extractIndex(Map<String, Object> ctx) {
        String index = (String) ctx.get("_index");
        if (index == null) {
            index = indexName;
        }
        return index;
    }

    private class Indexer implements Runnable {
        @Override
        public void run() {
            while (true) {
                if (closed) {
                    return;
                }
                String s;
                try {
                    s = stream.take();
                } catch (InterruptedException e) {
                    if (closed) {
                        return;
                    }
                    continue;
                }

                Object lastSeq = null;
                Object lineSeq = processLine(s);
                if (lineSeq != null) {
                    lastSeq = lineSeq;
                }

                // spin a bit to see if we can get some more changes
                try {
                    while ((s = stream.poll(bulkTimeout.millis(), TimeUnit.MILLISECONDS)) != null) {
                        lineSeq = processLine(s);
                        if (lineSeq != null) {
                            lastSeq = lineSeq;
                        }
                    }
                } catch (InterruptedException e) {
                    if (closed) {
                        return;
                    }
                }

                if (lastSeq != null) {
                    try {
                        // we always store it as a string
                        String lastSeqAsString = null;
                        if (lastSeq instanceof List) {
                            // bigcouch uses array for the seq
                            try {
                                XContentBuilder builder = XContentFactory.jsonBuilder();
                                //builder.startObject();
                                builder.startArray();
                                for (Object value : ((List) lastSeq)) {
                                    builder.value(value);
                                }
                                builder.endArray();
                                //builder.endObject();
                                lastSeqAsString = builder.string();
                            } catch (Exception e) {
                                logger.error("failed to convert last_seq to a json string", e);
                            }
                        } else {
                            lastSeqAsString = lastSeq.toString();
                        }
                        if (logger.isTraceEnabled()) {
                            logger.trace("processing [_seq  ]: [{}]/[{}]/[{}], last_seq [{}]", riverIndexName, riverName.name(), "_seq", lastSeqAsString);
                        }
                        if (closed) {
                            logger.warn("river was closing while trying to update sequence [{}/{}/{}]. Operation skipped.", riverIndexName, riverName.name(), "_seq");
                            return;
                        }
                        bulkProcessor.add(new IndexRequest(riverIndexName, riverName.name(), "_seq")
                                .source(jsonBuilder().startObject().startObject("couchdb").field("last_seq", lastSeqAsString).endObject().endObject()));
                    } catch (IOException e) {
                        logger.warn("failed to add last_seq entry to bulk indexing");
                    }
                }
            }
        }
    }


    private class Slurper implements Runnable {
        @SuppressWarnings({"unchecked"})
        @Override
        public void run() {

            while (true) {
                if (closed) {
                    return;
                }

                String lastSeq = null;
                try {
                    client.admin().indices().prepareRefresh(riverIndexName).execute().actionGet();
                    GetResponse lastSeqGetResponse = client.prepareGet(riverIndexName, riverName().name(), "_seq").execute().actionGet();
                    if (lastSeqGetResponse.isExists()) {
                        Map<String, Object> couchdbState = (Map<String, Object>) lastSeqGetResponse.getSourceAsMap().get("couchdb");
                        if (couchdbState != null) {
                            lastSeq = couchdbState.get("last_seq").toString(); // we know its always a string
                        }
                    }
                } catch (Exception e) {
                    logger.warn("failed to get last_seq, throttling....", e);
                    try {
                        Thread.sleep(5000);
                        continue;
                    } catch (InterruptedException e1) {
                        if (closed) {
                            return;
                        }
                    }
                }

                String file = "/" + couchDb + "/_changes?feed=continuous&include_docs=true&heartbeat=" + heartbeat.getMillis();
                if (couchFilter != null) {
                    try {
                        file = file + "&filter=" + URLEncoder.encode(couchFilter, "UTF-8");
                    } catch (UnsupportedEncodingException e) {
                        // should not happen!
                    }
                    if (couchFilterParamsUrl != null) {
                        file = file + couchFilterParamsUrl;
                    }
                }

                if (lastSeq != null) {
                    try {
                        file = file + "&since=" + URLEncoder.encode(lastSeq, "UTF-8");
                    } catch (UnsupportedEncodingException e) {
                        // should not happen, but in any case...
                        file = file + "&since=" + lastSeq;
                    }
                }

                if (logger.isDebugEnabled()) {
                    logger.debug("using host [{}], port [{}], path [{}]", couchHost, couchPort, file);
                }

                HttpURLConnection connection = null;
                InputStream is = null;
                try {
                    URL url = new URL(couchProtocol, couchHost, couchPort, file);
                    connection = (HttpURLConnection) url.openConnection();
                    if (basicAuth != null) {
                        connection.addRequestProperty("Authorization", basicAuth);
                    }
                    connection.setDoInput(true);
                    connection.setReadTimeout((int) readTimeout.getMillis());
                    connection.setUseCaches(false);

                    if (noVerify) {
                        ((HttpsURLConnection) connection).setHostnameVerifier(
                                new HostnameVerifier() {
                                    public boolean verify(String string, SSLSession ssls) {
                                        return true;
                                    }
                                }
                        );
                    }

                    is = connection.getInputStream();

                    final BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (closed) {
                            return;
                        }
                        if (line.length() == 0) {
                            logger.trace("[couchdb] heartbeat");
                            continue;
                        }
                        if (logger.isTraceEnabled()) {
                            logger.trace("[couchdb] {}", line);
                        }
                        // we put here, so we block if there is no space to add
                        stream.put(line);
                    }
                } catch (Exception e) {
                    IOUtils.closeWhileHandlingException(is);
                    if (connection != null) {
                        try {
                            connection.disconnect();
                        } catch (Exception e1) {
                            // ignore
                        } finally {
                            connection = null;
                        }
                    }
                    if (closed) {
                        return;
                    }
                    logger.warn("failed to read from _changes, throttling....", e);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e1) {
                        if (closed) {
                            return;
                        }
                    }
                } finally {
                    IOUtils.closeWhileHandlingException(is);
                    if (connection != null) {
                        try {
                            connection.disconnect();
                        } catch (Exception e1) {
                            // ignore
                        } finally {
                            connection = null;
                        }
                    }
                }
            }
        }
    }
}
