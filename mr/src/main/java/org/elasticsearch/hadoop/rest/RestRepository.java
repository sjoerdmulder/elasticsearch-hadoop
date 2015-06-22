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
package org.elasticsearch.hadoop.rest;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.elasticsearch.hadoop.EsHadoopException;
import org.elasticsearch.hadoop.EsHadoopIllegalStateException;
import org.elasticsearch.hadoop.cfg.Settings;
import org.elasticsearch.hadoop.rest.stats.Stats;
import org.elasticsearch.hadoop.rest.stats.StatsAware;
import org.elasticsearch.hadoop.serialization.ScrollReader;
import org.elasticsearch.hadoop.serialization.bulk.BulkCommand;
import org.elasticsearch.hadoop.serialization.bulk.BulkCommands;
import org.elasticsearch.hadoop.serialization.dto.Node;
import org.elasticsearch.hadoop.serialization.dto.Shard;
import org.elasticsearch.hadoop.serialization.dto.mapping.Field;
import org.elasticsearch.hadoop.util.Assert;
import org.elasticsearch.hadoop.util.BytesArray;
import org.elasticsearch.hadoop.util.BytesRef;
import org.elasticsearch.hadoop.util.StringUtils;
import org.elasticsearch.hadoop.util.TrackingBytesArray;
import org.elasticsearch.hadoop.util.unit.TimeValue;

/**
 * Rest client performing high-level operations using buffers to improve performance. Stateful in that once created, it is used to perform updates against the same index.
 */
public class RestRepository implements Closeable, StatsAware {

    private static Log log = LogFactory.getLog(RestRepository.class);

    // serialization artifacts
    private int bufferEntriesThreshold;

    private final BytesArray ba = new BytesArray(0);
    private final TrackingBytesArray data = new TrackingBytesArray(ba);
    private int dataEntries = 0;
    private boolean requiresRefreshAfterBulk = false;
    private boolean executedBulkWrite = false;
    private BytesRef trivialBytesRef;
    private boolean writeInitialized = false;

    // indicates whether there were writes errorrs or not
    // flag indicating whether to flush the batch at close-time or not
    private boolean hadWriteErrors = false;


    private RestClient client;
    private Resource resourceR;
    private Resource resourceW;
    private BulkCommand command;
    private final Settings settings;
    private final Stats stats = new Stats();

    public RestRepository(Settings settings) {
        this.settings = settings;

        if (StringUtils.hasText(settings.getResourceRead())) {
            this.resourceR = new Resource(settings, true);
        }

        if (StringUtils.hasText(settings.getResourceWrite())) {
            this.resourceW = new Resource(settings, false);
        }

        Assert.isTrue(resourceR != null || resourceW != null, "Invalid configuration - No read or write resource specified");

        this.client = new RestClient(settings);
    }

    /** postpone writing initialization since we can do only reading so there's no need to allocate buffers */
    private void lazyInitWriting() {
        if (!writeInitialized) {
            writeInitialized = true;

            ba.bytes(new byte[settings.getBatchSizeInBytes()], 0);
            trivialBytesRef = new BytesRef();
            bufferEntriesThreshold = settings.getBatchSizeInEntries();
            requiresRefreshAfterBulk = settings.getBatchRefreshAfterWrite();

            this.command = BulkCommands.create(settings);
        }
    }

    /**
     * Returns a pageable (scan based) result to the given query.
     *
     * @param query scan query
     * @param reader scroll reader
     * @return a scroll query
     */
    ScrollQuery scan(String query, BytesArray body, ScrollReader reader) {
        String[] scrollInfo = client.scan(query, body);
        String scrollId = scrollInfo[0];
        long totalSize = Long.parseLong(scrollInfo[1]);
        return new ScrollQuery(this, scrollId, totalSize, reader);
    }

    /**
     * Writes the objects to index.
     *
     * @param object object to add to the index
     */
    public void writeToIndex(Object object) throws IOException {
        Assert.notNull(object, "no object data given");

        lazyInitWriting();
        doWriteToIndex(command.write(object));
    }

    /**
     * Writes the objects to index.
     *
     * @param data as a byte array
     * @param size the length to use from the given array
     */
    public void writeProcessedToIndex(BytesArray ba) {
        Assert.notNull(ba, "no data given");
        Assert.isTrue(ba.length() > 0, "no data given");

        lazyInitWriting();
        trivialBytesRef.reset();
        trivialBytesRef.add(ba);
        doWriteToIndex(trivialBytesRef);
    }

    private void doWriteToIndex(BytesRef payload) {
        // check space first
        if (payload.length() > ba.available()) {
            sendBatch();
        }

        data.copyFrom(payload);
        payload.reset();

        dataEntries++;
        if (bufferEntriesThreshold > 0 && dataEntries >= bufferEntriesThreshold) {
            sendBatch();
        }
    }

    private void sendBatch() {
        if (log.isDebugEnabled()) {
            log.debug(String.format("Sending batch of [%d] bytes/[%s] entries", data.length(), dataEntries));
        }

        try {
            client.bulk(resourceW, data);
        } catch (EsHadoopException ex) {
            hadWriteErrors = true;
            throw ex;
        }

        data.reset();
        dataEntries = 0;
        executedBulkWrite = true;
    }

    @Override
    public void close() {
        if (log.isDebugEnabled()) {
            log.debug("Closing repository and connection to Elasticsearch ...");
        }

        if (data.length() > 0) {
            if (!hadWriteErrors) {
                sendBatch();
            }
            else {
                if (log.isDebugEnabled()) {
                    log.debug("Dirty close; ignoring last existing write batch...");
                }
            }
        }

        if (requiresRefreshAfterBulk && executedBulkWrite) {
            // refresh batch
            client.refresh(resourceW);

            if (log.isDebugEnabled()) {
                log.debug(String.format("Refreshing index [%s]", resourceW));
            }
        }

        if (client != null) {
            client.close();
            stats.aggregate(client.stats());
            client = null;
        }
    }

    public RestClient getRestClient() {
        return client;
    }


    public Map<Shard, Node> getReadTargetShards() {
        for (int retries = 0; retries < 3; retries++) {
            Map<Shard, Node> map = doGetReadTargetShards();
            if (map != null) {
                return map;
            }
        }
        throw new EsHadoopIllegalStateException("Cluster state volatile; cannot find node backing shards - please check whether your cluster is stable");
    }

    protected Map<Shard, Node> doGetReadTargetShards() {
        Map<Shard, Node> shards = new LinkedHashMap<Shard, Node>();
        List<List<Map<String, Object>>> info = client.targetShards(resourceR.index());

        Map<String, Node> nodes = client.getNodes();

        for (List<Map<String, Object>> shardGroup : info) {
            // find the first started shard in each group (round-robin)
            for (Map<String, Object> shardData : shardGroup) {
                Shard shard = new Shard(shardData, false);
                if (shard.getState().isStarted()) {
                    Node node = nodes.get(shard.getNode());
                    if (node == null) {
                        log.warn(String.format("Cannot find node with id [%s] (is HTTP enabled?) from shard [%s] in nodes [%s]; layout [%s]", shard.getNode(), shard, nodes, info));
                        return null;
                    }
                    shards.put(shard, node);
                    break;
                }
            }
        }
        return shards;
    }

    public Map<Shard, Node> getWriteTargetPrimaryShards() throws IOException {
        for (int retries = 0; retries < 3; retries++) {
            Map<Shard, Node> map = doGetWriteTargetPrimaryShards();
            if (map != null) {
                return map;
            }
        }
        throw new EsHadoopIllegalStateException("Cluster state volatile; cannot find node backing shards - please check whether your cluster is stable");
    }

    protected Map<Shard, Node> doGetWriteTargetPrimaryShards() {
        List<List<Map<String, Object>>> info = client.targetShards(resourceW.index());
        Map<Shard, Node> shards = new LinkedHashMap<Shard, Node>();
        Map<String, Node> nodes = client.getNodes();

        for (List<Map<String, Object>> shardGroup : info) {
            // consider only primary shards
            for (Map<String, Object> shardData : shardGroup) {
                Shard shard = new Shard(shardData, true);
                if (shard.isPrimary()) {
                    Node node = nodes.get(shard.getNode());
                    if (node == null) {
                        log.warn(String.format("Cannot find node with id [%s] (is HTTP enabled?) from shard [%s] in nodes [%s]; layout [%s]", shard.getNode(), shard, nodes, info));
                        return null;
                    }
                    shards.put(shard, node);
                    break;
                }
            }
        }
        return shards;
    }

    public Field getMapping() throws IOException {
        return Field.parseField(client.getMapping(resourceR.mapping()));
    }

    public List<Object[]> scroll(String scrollId, ScrollReader reader) throws IOException {
        InputStream scroll = client.scroll(scrollId);
        try {
            return reader.read(scroll);
        } finally {
            if (scroll instanceof StatsAware) {
                stats.aggregate(((StatsAware) scroll).stats());
            }
        }
    }

    public boolean indexExists(boolean read) throws IOException {
        Resource res = (read ? resourceR : resourceW);
        // cheap hit
        boolean exists = client.exists(res.indexAndType());
        // could be a _all or a pattern which is valid for read
        // try again by asking the mapping - could be expensive
        if (!exists && read) {
            try {
                // make sure the mapping is null since the index might exist but the type might be missing
                exists = !client.getMapping(res.mapping()).isEmpty();
            } catch (EsHadoopInvalidRequest ex) {
                exists = false;
            }
        }
        return exists;
    }

    public void putMapping(BytesArray mapping) throws IOException {
        client.putMapping(resourceW.index(), resourceW.mapping(), mapping.bytes());
    }

    public boolean touch() throws IOException {
        return client.touch(resourceW.index());
    }

    public boolean waitForYellow() throws IOException {
        return client.health(resourceW.index(), RestClient.HEALTH.YELLOW, TimeValue.timeValueSeconds(10));
    }

    @Override
    public Stats stats() {
        Stats copy = new Stats(stats);
        if (client != null) {
            copy.aggregate(client.stats());
        }
        return copy;
    }

    public Settings getSettings() {
        return settings;
    }
}