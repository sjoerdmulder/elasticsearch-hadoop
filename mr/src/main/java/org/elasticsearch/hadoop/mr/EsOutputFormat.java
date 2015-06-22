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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.TaskID;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.util.Progressable;
import org.elasticsearch.hadoop.cfg.Settings;
import org.elasticsearch.hadoop.cfg.SettingsManager;
import org.elasticsearch.hadoop.mr.compat.CompatHandler;
import org.elasticsearch.hadoop.rest.InitializationUtils;
import org.elasticsearch.hadoop.rest.Resource;
import org.elasticsearch.hadoop.rest.RestRepository;
import org.elasticsearch.hadoop.serialization.dto.Node;
import org.elasticsearch.hadoop.serialization.dto.Shard;
import org.elasticsearch.hadoop.serialization.field.IndexExtractor;
import org.elasticsearch.hadoop.serialization.field.MapWritableFieldExtractor;
import org.elasticsearch.hadoop.util.Assert;
import org.elasticsearch.hadoop.util.ObjectUtils;
import org.elasticsearch.hadoop.util.SettingsUtils;
import org.elasticsearch.hadoop.util.Version;

import static org.elasticsearch.hadoop.cfg.ConfigurationOptions.*;

/**
 * ElasticSearch {@link OutputFormat} (old and new API) for adding data to an index inside ElasticSearch.
 */
@SuppressWarnings("rawtypes")
// since this class implements two generic interfaces, to avoid dealing with 4 types in every declaration, we force raw types...
public class EsOutputFormat extends OutputFormat implements org.apache.hadoop.mapred.OutputFormat {

    private static Log log = LogFactory.getLog(EsOutputFormat.class);
    private static final int NO_TASK_ID = -1;

    // don't use mapred.OutputCommitter as it performs mandatory casts to old API resulting in CCE
    public static class EsOutputCommitter extends org.apache.hadoop.mapreduce.OutputCommitter {

        @Override
        public void setupJob(JobContext jobContext) throws IOException {}

        // compatibility check with Hadoop 0.20.2
        @Deprecated
        public void cleanupJob(JobContext jobContext) throws IOException {}

        @Override
        public void setupTask(TaskAttemptContext taskContext) throws IOException {
            //no-op
        }

        @Override
        public boolean needsTaskCommit(TaskAttemptContext taskContext) throws IOException {
            //no-op
            return false;
        }

        @Override
        public void commitTask(TaskAttemptContext taskContext) throws IOException {
            //no-op
        }

        @Override
        public void abortTask(TaskAttemptContext taskContext) throws IOException {
            //no-op
        }

    }

    public static class EsOldAPIOutputCommitter extends org.apache.hadoop.mapred.OutputCommitter {

        @Override
        public void setupJob(org.apache.hadoop.mapred.JobContext jobContext) throws IOException {
            //no-op
        }

        @Override
        public void setupTask(org.apache.hadoop.mapred.TaskAttemptContext taskContext) throws IOException {
            //no-op
        }

        @Override
        public boolean needsTaskCommit(org.apache.hadoop.mapred.TaskAttemptContext taskContext) throws IOException {
            //no-op
            return false;
        }

        @Override
        public void commitTask(org.apache.hadoop.mapred.TaskAttemptContext taskContext) throws IOException {
            //no-op
        }

        @Override
        public void abortTask(org.apache.hadoop.mapred.TaskAttemptContext taskContext) throws IOException {
            //no-op
        }

        @Override
        @Deprecated
        public void cleanupJob(org.apache.hadoop.mapred.JobContext context) throws IOException {
            // no-op
            // added for compatibility with hadoop 0.20.x (used by old tools, such as Cascalog)
        }
    }

    protected static class EsRecordWriter extends RecordWriter implements org.apache.hadoop.mapred.RecordWriter {

        protected final Configuration cfg;
        protected boolean initialized = false;

        protected RestRepository repository;
        private String uri;
        private Resource resource;

        private HeartBeat beat;
        private Progressable progressable;

        public EsRecordWriter(Configuration cfg, Progressable progressable) {
            this.cfg = cfg;
            this.progressable = progressable;
        }

        @Override
        public void write(Object key, Object value) throws IOException {
            if (!initialized) {
                initialized = true;
                init();
            }
            repository.writeToIndex(value);
        }

        protected void init() throws IOException {
            //int instances = detectNumberOfInstances(cfg);
            int currentInstance = detectCurrentInstance(cfg);

            if (log.isTraceEnabled()) {
                log.trace(String.format("EsRecordWriter instance [%s] initiating discovery of target shard...",
                        currentInstance));
            }

            Settings settings = SettingsManager.loadFrom(cfg).copy();

            if (log.isTraceEnabled()) {
                log.trace(String.format("Init shard writer from cfg %s", HadoopCfgUtils.asProperties(cfg)));
            }

            InitializationUtils.setValueWriterIfNotSet(settings, WritableValueWriter.class, log);
            InitializationUtils.setBytesConverterIfNeeded(settings, WritableBytesConverter.class, log);
            InitializationUtils.setFieldExtractorIfNotSet(settings, MapWritableFieldExtractor.class, log);
            InitializationUtils.discoverNodesIfNeeded(settings, log);
            InitializationUtils.discoverEsVersion(settings, log);

            List<String> nodes = SettingsUtils.discoveredOrDeclaredNodes(settings);
            // select the appropriate nodes first, to spread the load before-hand
            SettingsUtils.pinNode(settings, nodes.get(currentInstance % nodes.size()));

			// in Hadoop-like envs (Spark) the progressable might be null and thus the heart-beat is not needed
			if (progressable != null) {
				beat = new HeartBeat(progressable, cfg, settings.getHeartBeatLead(), log);
				beat.start();
			}

            resource = new Resource(settings, false);

            // single index vs multi indices
            IndexExtractor iformat = ObjectUtils.instantiate(settings.getMappingIndexExtractorClassName(), settings);
            iformat.compile(resource.toString());
            if (iformat.hasPattern()) {
                initMultiIndices(settings, currentInstance);
            }
            else {
                initSingleIndex(settings, currentInstance);
            }
        }

        private void initSingleIndex(Settings settings, int currentInstance) throws IOException {
            if (log.isDebugEnabled()) {
                log.debug(String.format("Resource [%s] resolves as a single index", resource));
            }

            repository = new RestRepository(settings);
            // create the index if needed
            if (repository.touch()) {
                if (repository.waitForYellow()) {
                    log.warn(String.format("Timed out waiting for index [%s] to reach yellow health", resource));
                }
            }

            Map<Shard, Node> targetShards = repository.getWriteTargetPrimaryShards();
            repository.close();

            Assert.isTrue(!targetShards.isEmpty(),
                    String.format("Cannot determine write shards for [%s]; likely its format is incorrect (maybe it contains illegal characters?)", resource));

            List<Shard> orderedShards = new ArrayList<Shard>(targetShards.keySet());
            // make sure the order is strict
            Collections.sort(orderedShards);
            if (log.isTraceEnabled()) {
                log.trace(String.format("ESRecordWriter instance [%s] discovered %s primary shards %s", currentInstance, orderedShards.size(), orderedShards));
            }

            // if there's no task info, just pick a random bucket
            if (currentInstance <= 0) {
                currentInstance = new Random().nextInt(targetShards.size()) + 1;
            }
            int bucket = currentInstance % targetShards.size();
            Shard chosenShard = orderedShards.get(bucket);
            Node targetNode = targetShards.get(chosenShard);

            // pin settings
            SettingsUtils.pinNode(settings, targetNode.getIpAddress(), targetNode.getHttpPort());

            repository = new RestRepository(settings);

            if (log.isDebugEnabled()) {
                log.debug(String.format("EsRecordWriter instance [%s] assigned to primary shard [%s] at address [%s]", currentInstance, chosenShard.getName(), uri));
            }
        }

        private void initMultiIndices(Settings settings, int currentInstance) throws IOException {
            if (log.isDebugEnabled()) {
                log.debug(String.format("Resource [%s] resolves as an index pattern", resource));
            }

            // multi-index write - since we don't know before hand what index will be used, pick a random node from the given list
            List<String> nodes = SettingsUtils.discoveredOrDeclaredNodes(settings);
            String node = nodes.get(new Random().nextInt(nodes.size()));
            // override the global settings to communicate directly with the target node
            SettingsUtils.pinNode(settings, node);

            if (log.isDebugEnabled()) {
                log.debug(String.format("EsRecordWriter instance [%s] assigned to [%s]", currentInstance, uri));
            }

            repository = new RestRepository(settings);
        }

        private int detectCurrentInstance(Configuration conf) {
            TaskID taskID = HadoopCfgUtils.getTaskID(conf);

            if (taskID == null) {
                log.warn(String.format("Cannot determine task id - redirecting writes in a random fashion"));
                return NO_TASK_ID;
            }

            return taskID.getId();
        }

        @Override
        public void close(TaskAttemptContext context) throws IOException {
            doClose(context);
        }

        @Override
        public void close(Reporter reporter) throws IOException {
            doClose(reporter);
        }

        protected void doClose(Progressable progressable) {
            if (log.isTraceEnabled()) {
                log.trace(String.format("Closing RecordWriter [%s][%s]", uri, resource));
            }

            if (beat != null) {
                beat.stop();
            }

            if (repository != null) {
                repository.close();
                ReportingUtils.report(progressable, repository.stats());
            }

            initialized = false;
        }
    }

    //
    // new API - just delegates to the Old API
    //
    @Override
    public org.apache.hadoop.mapreduce.RecordWriter getRecordWriter(TaskAttemptContext context) {
        return (org.apache.hadoop.mapreduce.RecordWriter) getRecordWriter(null, HadoopCfgUtils.asJobConf(CompatHandler.taskAttemptContext(context).getConfiguration()), null, context);
    }

    @Override
    public void checkOutputSpecs(JobContext context) throws IOException {
        // careful as it seems the info here saved by in the config is discarded
        init(CompatHandler.jobContext(context).getConfiguration());
    }

    @Override
    public org.apache.hadoop.mapreduce.OutputCommitter getOutputCommitter(TaskAttemptContext context) {
        return new EsOutputCommitter();
    }

    //
    // old API
    //
    @Override
    public org.apache.hadoop.mapred.RecordWriter getRecordWriter(FileSystem ignored, JobConf job, String name, Progressable progress) {
        return new EsRecordWriter(job, progress);
    }

    @Override
    public void checkOutputSpecs(FileSystem ignored, JobConf cfg) throws IOException {
        init(cfg);
    }

    // NB: all changes to the config objects are discarded before the job is submitted if _the old MR api_ is used
    private void init(Configuration cfg) throws IOException {
        Settings settings = SettingsManager.loadFrom(cfg);
        Assert.hasText(settings.getResourceWrite(), String.format("No resource ['%s'] (index/query/location) specified", ES_RESOURCE));

        // lazy-init
        RestRepository client = null;

        InitializationUtils.checkIdForOperation(settings);
        InitializationUtils.checkIndexExistence(settings, client);

        if (HadoopCfgUtils.getReduceTasks(cfg) != null) {
            if (HadoopCfgUtils.getSpeculativeReduce(cfg)) {
                log.warn("Speculative execution enabled for reducer - consider disabling it to prevent data corruption");
            }
        }
        else {
            if (HadoopCfgUtils.getSpeculativeMap(cfg)) {
                log.warn("Speculative execution enabled for mapper - consider disabling it to prevent data corruption");
            }
        }

        Version.logVersion();
        log.info(String.format("Writing to [%s]", settings.getResourceWrite()));

        //log.info(String.format("Starting to write/index to [%s][%s]", settings.getTargetUri(), settings.getTargetResource()));
    }
}