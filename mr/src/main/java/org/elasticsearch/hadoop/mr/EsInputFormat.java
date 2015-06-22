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
package org.elasticsearch.hadoop.mr;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.util.Progressable;
import org.elasticsearch.hadoop.EsHadoopIllegalArgumentException;
import org.elasticsearch.hadoop.cfg.ConfigurationOptions;
import org.elasticsearch.hadoop.cfg.FieldPresenceValidation;
import org.elasticsearch.hadoop.cfg.Settings;
import org.elasticsearch.hadoop.cfg.SettingsManager;
import org.elasticsearch.hadoop.mr.compat.CompatHandler;
import org.elasticsearch.hadoop.rest.InitializationUtils;
import org.elasticsearch.hadoop.rest.QueryBuilder;
import org.elasticsearch.hadoop.rest.RestRepository;
import org.elasticsearch.hadoop.rest.ScrollQuery;
import org.elasticsearch.hadoop.rest.stats.Stats;
import org.elasticsearch.hadoop.serialization.ScrollReader;
import org.elasticsearch.hadoop.serialization.builder.ValueReader;
import org.elasticsearch.hadoop.serialization.dto.Node;
import org.elasticsearch.hadoop.serialization.dto.Shard;
import org.elasticsearch.hadoop.serialization.dto.mapping.Field;
import org.elasticsearch.hadoop.serialization.dto.mapping.MappingUtils;
import org.elasticsearch.hadoop.util.IOUtils;
import org.elasticsearch.hadoop.util.ObjectUtils;
import org.elasticsearch.hadoop.util.SettingsUtils;
import org.elasticsearch.hadoop.util.StringUtils;
import org.elasticsearch.hadoop.util.Version;

/**
 * ElasticSearch {@link InputFormat} for streaming data (typically based on a query) from ElasticSearch.
 * Returns the document ID as key and its content as value.
 *
 * <p/>This class implements both the "old" (<tt>org.apache.hadoop.mapred</tt>) and the "new" (<tt>org.apache.hadoop.mapreduce</tt>) API.
 */
public class EsInputFormat<K, V> extends InputFormat<K, V> implements org.apache.hadoop.mapred.InputFormat<K, V> {

    private static Log log = LogFactory.getLog(EsInputFormat.class);

    protected static class ShardInputSplit extends InputSplit implements org.apache.hadoop.mapred.InputSplit {

        private String nodeIp;
        private int httpPort;
        private String nodeId;
        private String nodeName;
        private String shardId;
        private String mapping;
        private String settings;

        public ShardInputSplit() {}

        public ShardInputSplit(String nodeIp, int httpPort, String nodeId, String nodeName, Integer shard,
                String mapping, String settings) {
            this.nodeIp = nodeIp;
            this.httpPort = httpPort;
            this.nodeId = nodeId;
            this.nodeName = nodeName;
            this.shardId = shard.toString();
            this.mapping = mapping;
            this.settings = settings;
        }

        @Override
        public long getLength() {
            // TODO: can this be computed easily?
            return 1l;
        }

        @Override
        public String[] getLocations() {
            // TODO: check whether the host name needs to be used instead
            return new String[] { nodeIp };
        }

        @Override
        public void write(DataOutput out) throws IOException {
            out.writeUTF(nodeIp);
            out.writeInt(httpPort);
            out.writeUTF(nodeId);
            out.writeUTF(nodeName);
            out.writeUTF(shardId);
            // avoid using writeUTF since the mapping can be longer than 65K
            byte[] utf = StringUtils.toUTF(mapping);
            out.writeInt(utf.length);
            out.write(utf);

            utf = StringUtils.toUTF(settings);
            out.writeInt(utf.length);
            out.write(utf);
        }

        @Override
        public void readFields(DataInput in) throws IOException {
            nodeIp = in.readUTF();
            httpPort = in.readInt();
            nodeId = in.readUTF();
            nodeName = in.readUTF();
            shardId = in.readUTF();
            int length = in.readInt();
            byte[] utf = new byte[length];
            in.readFully(utf);
            mapping = StringUtils.asUTFString(utf);

            length = in.readInt();
            utf = new byte[length];
            in.readFully(utf);
            settings = StringUtils.asUTFString(utf);
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("ShardInputSplit [node=[").append(nodeId).append("/").append(nodeName)
                        .append("|").append(nodeIp).append(":").append(httpPort)
                        .append("],shard=").append(shardId).append("]");
            return builder.toString();
        }
    }


    protected static abstract class ShardRecordReader<K,V> extends RecordReader<K, V> implements org.apache.hadoop.mapred.RecordReader<K, V> {

        private int read = 0;
        private ShardInputSplit esSplit;
        private ScrollReader scrollReader;

        private RestRepository client;
        private QueryBuilder queryBuilder;
        private ScrollQuery scrollQuery;

        // reuse objects
        private K currentKey;
        private V currentValue;

        private long size = 0;

        private HeartBeat beat;
        private Progressable progressable;

        // default constructor used by the NEW api
        public ShardRecordReader() {
        }

        // constructor used by the old API
        public ShardRecordReader(org.apache.hadoop.mapred.InputSplit split, Configuration job, Reporter reporter) {
            reporter.setStatus(split.toString());
            init((ShardInputSplit) split, job, reporter);
        }

        // new API init call
        @Override
        public void initialize(InputSplit split, TaskAttemptContext context) throws IOException {
            org.elasticsearch.hadoop.mr.compat.TaskAttemptContext compatContext = CompatHandler.taskAttemptContext(context);
            compatContext.setStatus(split.toString());
            init((ShardInputSplit) split, compatContext.getConfiguration(), compatContext);
        }

        void init(ShardInputSplit esSplit, Configuration cfg, Progressable progressable) {
            // get a copy to override the host/port
            Settings settings = SettingsManager.loadFrom(cfg).copy().load(esSplit.settings);

            if (!SettingsUtils.hasPinnedNode(settings)) {
                SettingsUtils.pinNode(settings, esSplit.nodeIp, esSplit.httpPort);
            }

            if (log.isTraceEnabled()) {
                log.trace(String.format("Init shard reader from cfg %s", HadoopCfgUtils.asProperties(cfg)));
                log.trace(String.format("Init shard reader w/ settings %s", esSplit.settings));
            }

            this.esSplit = esSplit;

            // initialize mapping/ scroll reader
            InitializationUtils.setValueReaderIfNotSet(settings, WritableValueReader.class, log);
            ValueReader reader = ObjectUtils.instantiate(settings.getSerializerValueReaderClassName(), settings);

            String mappingData = esSplit.mapping;

            Field mapping = null;

            if (StringUtils.hasText(mappingData)) {
                mapping = IOUtils.deserializeFromBase64(mappingData);
            }
            else {
                log.warn(String.format("No mapping found for [%s] - either no index exists or the split configuration has been corrupted", esSplit));
            }

            scrollReader = new ScrollReader(reader, mapping);

            // heart-beat
            // in Hadoop-like envs (Spark) the progressable might be null and thus the heart-beat is not needed
			if (progressable != null) {
				beat = new HeartBeat(progressable, cfg, settings.getHeartBeatLead(), log);
			}

            // initialize REST client
            client = new RestRepository(settings);

            queryBuilder = QueryBuilder.query(settings)
                    .shard(esSplit.shardId)
                    .onlyNode(esSplit.nodeId);

            queryBuilder.fields(settings.getScrollFields());

            this.progressable = progressable;

            if (log.isDebugEnabled()) {
                log.debug(String.format("Initializing RecordReader for [%s]", esSplit));
            }
        }

        @Override
        public boolean nextKeyValue() throws IOException {
            // new API call routed to old API
			// under the new API always create new objects since consumers can (and sometimes will) modify them
			currentKey = createKey();
			currentValue = createValue();
            return next(currentKey, currentValue);
        }

        @Override
        public K getCurrentKey() throws IOException {
            return currentKey;
        }

        @Override
        public V getCurrentValue() {
            return currentValue;
        }

        @Override
        public float getProgress() {
            return size == 0 ? 0 : ((float) getPos()) / size;
        }

        @Override
        public void close() throws IOException {
            try {
                if (log.isDebugEnabled()) {
                    log.debug(String.format("Closing RecordReader for [%s]", esSplit));
                }

                if (beat != null) {
                    beat.stop();
                }

                if (scrollQuery != null) {
                    scrollQuery.close();
                }

                if (client != null) {
                    client.close();
                }

            } finally {
                Stats stats = new Stats();
                if (client != null) {
                    stats.aggregate(client.stats());
                    client = null;
                }
                if (scrollQuery != null) {
                    stats.aggregate(scrollQuery.stats());
                    scrollQuery = null;
                }
                ReportingUtils.report(progressable, stats);
            }
        }

        @Override
        public boolean next(K key, V value) throws IOException {
            if (scrollQuery == null) {
				if (beat != null) {
					beat.start();
				}

                scrollQuery = queryBuilder.build(client, scrollReader);
                size = scrollQuery.getSize();

                if (log.isTraceEnabled()) {
                    log.trace(String.format("Received scroll [%s],  size [%d] for query [%s]", scrollQuery, size, queryBuilder));
                }
            }

            boolean hasNext = scrollQuery.hasNext();

            if (!hasNext) {
                return false;
            }

            Object[] next = scrollQuery.next();

			// NB: the left assignment is not needed since method override
			// the writable content however for consistency, they are below
			currentKey = setCurrentKey(key, next[0]);
			currentValue = setCurrentValue(value, next[1]);

            // keep on counting
            read++;
            return true;
        }

        @Override
        public abstract K createKey();

        @Override
        public abstract V createValue();

		/**
		 * Sets the current key.
		 *
		 * @param hadoopKey hadoop key
		 * @param object the actual value to read
		 * @return returns the key to be used; needed in scenario where the key is immutable (like Pig)
		 */
		protected abstract K setCurrentKey(K hadoopKey, Object object);

		/**
		 * Sets the current value.
		 *
		 * @param hadoopValue hadoop value
		 * @param object the actual value to read
		 * @return returns the value to be used; needed in scenario where the passed value is immutable (like Pig)
		 */
		protected abstract V setCurrentValue(V hadoopValue, Object object);

        @Override
        public long getPos() {
            return read;
        }
    }

    protected static class WritableShardRecordReader extends ShardRecordReader<Text, Map<Writable, Writable>> {

        private boolean useLinkedMapWritable = true;

        public WritableShardRecordReader() {
            super();
        }

        public WritableShardRecordReader(org.apache.hadoop.mapred.InputSplit split, Configuration job, Reporter reporter) {
            super(split, job, reporter);
        }


        @Override
        void init(ShardInputSplit esSplit, Configuration cfg, Progressable progressable) {
            useLinkedMapWritable = (!MapWritable.class.getName().equals(HadoopCfgUtils.getMapValueClass(cfg)));

            super.init(esSplit, cfg, progressable);
        }

        @Override
        public Text createKey() {
            return new Text();
        }

        @Override
        public Map<Writable, Writable> createValue() {
            return (useLinkedMapWritable ? new LinkedMapWritable() : new MapWritable());
        }

        @Override
		protected Text setCurrentKey(Text hadoopKey, Object object) {
			if (hadoopKey != null) {
				hadoopKey.set(object.toString());
            }
			return hadoopKey;
        }

        @SuppressWarnings("unchecked")
        @Override
		protected Map<Writable, Writable> setCurrentValue(Map<Writable, Writable> hadoopValue, Object object) {
			if (hadoopValue != null) {
				hadoopValue.clear();
				Map<Writable, Writable> val = (Map<Writable, Writable>) object;
				hadoopValue.putAll(val);
            }
			return hadoopValue;
        }
    }

    //
    // new API - just delegates to the Old API
    //
    @Override
    public List<InputSplit> getSplits(JobContext context) throws IOException {
        org.elasticsearch.hadoop.mr.compat.JobContext compatJobContext = CompatHandler.jobContext(context);
        JobConf conf = HadoopCfgUtils.asJobConf(compatJobContext.getConfiguration());
        // NOTE: this method expects a ShardInputSplit to be returned (which implements both the old and the new API).
        return Arrays.asList((InputSplit[]) getSplits(conf, conf.getNumMapTasks()));
    }

    @SuppressWarnings("unchecked")
    @Override
    public ShardRecordReader<K, V> createRecordReader(InputSplit split, TaskAttemptContext context) {
        return (ShardRecordReader<K, V>) new WritableShardRecordReader();
    }


    //
    // Old API - if this method is replaced, make sure to return a new/old-API compatible InputSplit
    //

    // Note: data written to the JobConf will be silently discarded
    @Override
    public org.apache.hadoop.mapred.InputSplit[] getSplits(JobConf job, int numSplits) throws IOException {

        Settings settings = SettingsManager.loadFrom(job);
        InitializationUtils.discoverNodesIfNeeded(settings, log);
        InitializationUtils.discoverEsVersion(settings, log);

        String savedSettings = settings.save();

        RestRepository client = new RestRepository(settings);
        boolean indexExists = client.indexExists(true);
        Map<Shard, Node> targetShards = null;

        if (!indexExists) {
            if (settings.getIndexReadMissingAsEmpty()) {
                log.info(String.format("Index [%s] missing - treating it as empty", settings.getResourceRead()));
                targetShards = Collections.emptyMap();
            }
            else {
                client.close();
                throw new EsHadoopIllegalArgumentException(
                        String.format("Index [%s] missing and settings [%s] is set to false", settings.getResourceRead(), ConfigurationOptions.ES_FIELD_READ_EMPTY_AS_NULL));
            }
        }
        else {
            targetShards = client.getReadTargetShards();
            if (log.isTraceEnabled()) {
                log.trace("Creating splits for shards " + targetShards);
            }
        }

        Version.logVersion();
        log.info(String.format("Reading from [%s]", settings.getResourceRead()));

        String savedMapping = null;
        if (!targetShards.isEmpty()) {
            Field mapping = client.getMapping();
            log.info(String.format("Discovered mapping {%s} for [%s]", mapping, settings.getResourceRead()));
            // validate if possible
            FieldPresenceValidation validation = settings.getFieldExistanceValidation();
            if (validation.isRequired()) {
                MappingUtils.validateMapping(settings.getScrollFields(), mapping, validation, log);
            }

            //TODO: implement this more efficiently
            savedMapping = IOUtils.serializeToBase64(mapping);

        }

        client.close();

        ShardInputSplit[] splits = new ShardInputSplit[targetShards.size()];

        int index = 0;
        for (Entry<Shard, Node> entry : targetShards.entrySet()) {
            Shard shard = entry.getKey();
            Node node = entry.getValue();
            splits[index++] =
                        new ShardInputSplit(node.getIpAddress(), node.getHttpPort(), node.getId(), node.getName(), shard.getName(), savedMapping, savedSettings);
        }

        log.info(String.format("Created [%d] shard-splits", splits.length));
        return splits;
    }

    @SuppressWarnings("unchecked")
    @Override
    public ShardRecordReader<K, V> getRecordReader(org.apache.hadoop.mapred.InputSplit split, JobConf job, Reporter reporter) {
        return (ShardRecordReader<K, V>) new WritableShardRecordReader(split, job, reporter);
    }
}