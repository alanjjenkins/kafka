/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.connect.mirror;

import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.common.config.provider.ConfigProvider;
import org.apache.kafka.common.config.ConfigData;
import org.apache.kafka.common.metrics.FakeMetricsReporter;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.HashMap;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class MirrorMakerConfigTest {

    private Map<String, String> makeProps(String... keyValues) {
        Map<String, String> props = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            props.put(keyValues[i], keyValues[i + 1]);
        }
        return props;
    }

    @Test
    public void testClusterConfigProperties() {
        MirrorMakerConfig mirrorConfig = new MirrorMakerConfig(makeProps(
            "clusters", "a, b",
            "a.bootstrap.servers", "servers-one",
            "b.bootstrap.servers", "servers-two",
            "security.protocol", "SASL",
            "replication.factor", "4",
            "a.security.protocol", "SSL",           // default: PLAINTEXT
            "a.producer.client.id", "a-b-producer", // default: null
            "a.consumer.security.providers", "custom_security", // default: null
            "b.producer.compression.type", "zstd",  // default: null
            "a->b.producer.acks", "all",            // default: 1
            "a->b.consumer.client.id", "a-b-consumer",  // default: null
            "a->b.admin.retry.backoff.ms", "150",   // default: 100
            "b.consumer.bootstrap.servers", "servers-two?",     // error!
            "a->b.producer.bootstrap.servers", "servers-two_",  // error!
            "producer.batch.size", "8192"   // default: 16384
        ));
        Map<String, String> connectorProps = mirrorConfig.connectorBaseConfig(new SourceAndTarget("a", "b"),
            MirrorSourceConnector.class);
        assertEquals("servers-one", connectorProps.get("source.cluster.bootstrap.servers"),
            "source.cluster.bootstrap.servers is set");
        assertEquals("servers-two", connectorProps.get("target.cluster.bootstrap.servers"),
            "target.cluster.bootstrap.servers is set");
        assertEquals("SASL", connectorProps.get("security.protocol"),
            "top-level security properties should be passed through to connector config");
        // 1. The documentation states that cluster-level client props, 'us-west.consumer.isolation.level = read_committed' or 'us-east.producer.buffer.memory = 32768' are allowed.
        // Also, the Javadoc of MirrorMakerConfig states that replication-level client props like 'A->B.producer.client.id = "A-B-producer"' are allowed.
        // It does not explicitly state the preference but, the replication-level props are more specific than the cluster-level ones so they override, like the following:
        assertEquals("SSL", connectorProps.get("source.cluster.security.protocol"), "cluster-level client props should be passed through to cluster config");
        assertEquals("SSL", connectorProps.get("source.producer.security.protocol"), "cluster-level client props should be passed through to client config");
        assertEquals("SSL", connectorProps.get("source.consumer.security.protocol"), "cluster-level client props should be passed through to client config");
        assertEquals("SSL", connectorProps.get("source.admin.security.protocol"), "cluster-level client props should be passed through to client config");
        assertEquals("SASL", connectorProps.get("target.cluster.security.protocol"), "top-level client props should be passed through to cluster config");
        assertEquals("SASL", connectorProps.get("target.producer.security.protocol"), "top-level client props should be passed through to client config");
        assertEquals("SASL", connectorProps.get("target.consumer.security.protocol"), "top-level client props should be passed through to client config");
        assertEquals("SASL", connectorProps.get("target.admin.security.protocol"), "top-level client props should be passed through to client config");
        // However, as you can see here, in replication-level props only the common properties like CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, CommonClientConfigs.SECURITY_PROTOCOL_CONFIG,
        // ssl, and sasl are counted now. (see MirrorClientConfig.CLIENT_CONFIG_DEF).
        assertEquals("custom_security", connectorProps.get("source.consumer.security.providers"),
            "cluster-level client props should be passed through to client config");    // cluster-level consumer prop is working.
        assertEquals("zstd", connectorProps.get("target.producer.compression.type"),
            "cluster-level client props should be passed through to client config");    // cluster-level producer prop is working.
        assertNotEquals("a-b-consumer", connectorProps.get("source.consumer.client.id"),
            "replication-level client props should be passed through to producer config");  // should be equal; replication-level consumer prop is not working.
        assertNotEquals("all", connectorProps.get("target.producer.acks"),
            "replication-level client props should be passed through to producer config");  // should be equal; replication-level producer prop is not working.
        // As of present, those non-common client props defined in replication-level are stored without '[source, target].{client-type}' prefix; this behavior causes three problems:
        // A. Since the methods like MirrorConnectorConfig#targetProducerConfig prefers the prefixed ones, the cluster-level client properties like 'a.consumer.client.id' or
        //   'b.producer.acks' may override the replication-level properties like 'a->b.consumer.client.id' or 'a->b.producer.acks'. It contradicts with the behavior described above, the
        //   'security.protocol' case.
        // B. If there are no cluster-level props like 'a.producer.acks', the value of 'a->b.producer.acks' will also be applied to the to-source producer, like an offset-syncs topic producer; since
        //    'a->b.producer.acks' is not overridden.
        // C. MirrorSourceConnector uses two admin clients, to control the topics in both clusters; However, the behavior of replication-level admin client props is problematic:
        //   a. If cluster-level props override override the replication ones (like as of present), it contradicts with the behavior described above, the 'security.protocol' case.
        //   b. If replication-level props override the cluster-level ones, it applies to both of two different admin clients.
        // Since it seems like disallowing non-common client props in replication level may be confusing, it would be better to fix problems A and B, and explicitly define a policy in problem C.
        assertEquals("a-b-consumer", connectorProps.get("consumer.client.id"), "replication-level client props should be passed through to producer config");
        assertEquals("all", connectorProps.get("producer.acks"), "replication-level client props should be passed through to producer config");
        assertEquals("150", connectorProps.get("admin.retry.backoff.ms"), "replication-level client props should be passed through to producer config");

        // 2. Since all of MirrorMaker2 Connectors (MirrorSourceConnector, MirrorCheckpointConnector, and MirrorHeartbeatConnector) are source connectors, they use producer created by Worker#producerConfigs
        // to publish the messages, dislike to the other clients for consuming messages, producing offset-sync, etc.
        // However, as of present, it has three problems like the following:
        // A. In standalone mode, the configurations in 'target.producer.{property-name}' are not applied to 'producer.override.{property-name}'; For this reason, the message-producing producer is actually
        //    not configurable.
        assertNotEquals("zstd", connectorProps.get("producer.override.compression.type"),
            "target producer props should be passed to 'producer.override.' config");   // should be equal
        assertNotEquals("all", connectorProps.get("producer.override.acks"),
            "target producer props should be passed to 'producer.override.' config");   // should be equal
        // B. In connector mode, 'target.producer.{property-name}' can't be automatically applied to 'producer.override.{property-name}' so the user has to configure them by themself. This behavior should be
        //    clearly stated in the documentation.
        // C. The documentation does not mention how traditional Kafka Connect client configurations are matched with MirrorMaker2 configurations; For the beginners, it would explicitly explain the
        //    relationships between '{src}->{dst}.producer.{property-name}' in standalone mode, 'target.producer.{property-name}' in connector mode and the traditional, 'producer.override.{property-name}'.

        // 3. MirrorMaker2 requires to define the 'bootstrap.servers' of the clusters in cluster-level, like 'a.bootstrap.servers' or 'b.bootstrap.servers'.
        // However, it also allows to override the 'bootstrap.servers' in client configuration, like 'a.consumer.bootstrap.servers' or 'a->b.producer.bootstrap.servers'.
        // As of present, replication-level client's bootstrap.servers are stored in 'consumer.bootstrap.servers' or 'producer.bootstrap.servers'; although they do no harm by being overridden by
        // '[source,target].cluster.bootstrap.servers' but, it would be better to ignore it and give a warning.
        assertNull(connectorProps.get("consumer.bootstrap.servers"), "non-cluster level bootstrap.servers override should be ignored");  // cluster-level client overriding is ignored.
        assertNotNull(connectorProps.get("producer.bootstrap.servers"),
            "non-cluster level bootstrap.servers override should be ignored");  // replication-level client overriding is not ignored; better to be null.
        // only the connector overridden client props, cluster-level client props, and replication-level client props are allowed.
        assertNotEquals("8192", connectorProps.get("producer.batch.size"),
            "top-level client props are only supported in MirrorClient; should be ignored in connector configs.");
    }

    @Test
    public void testReplicationConfigProperties() {
        MirrorMakerConfig mirrorConfig = new MirrorMakerConfig(makeProps(
            "clusters", "a, b",
            "a->b.tasks.max", "123"));
        Map<String, String> connectorProps = mirrorConfig.connectorBaseConfig(new SourceAndTarget("a", "b"),
            MirrorSourceConnector.class);
        assertEquals("123", connectorProps.get("tasks.max"), "connector props should include tasks.max");
    }

    @Test
    public void testClientConfigProperties() {
        MirrorMakerConfig mirrorConfig = new MirrorMakerConfig(makeProps(
            "clusters", "a, b",
            "config.providers", "fake",
            "config.providers.fake.class", FakeConfigProvider.class.getName(),
            "replication.policy.separator", "__",
            "ssl.truststore.password", "secret1",
            "ssl.key.password", "${fake:secret:password}",  // resolves to "secret2"
            "security.protocol", "SSL", 
            "a.security.protocol", "PLAINTEXT", 
            "a.producer.security.protocol", "SASL", 
            "a.bootstrap.servers", "one:9092, two:9092",
            "metrics.reporter", FakeMetricsReporter.class.getName(),
            "a.metrics.reporter", FakeMetricsReporter.class.getName(),
            "b->a.metrics.reporter", FakeMetricsReporter.class.getName(),
            "a.xxx", "yyy",
            "xxx", "zzz"));
        MirrorClientConfig aClientConfig = mirrorConfig.clientConfig("a");
        MirrorClientConfig bClientConfig = mirrorConfig.clientConfig("b");
        assertEquals("__", aClientConfig.getString("replication.policy.separator"),
            "replication.policy.separator is picked up in MirrorClientConfig");
        assertEquals("b__topic1", aClientConfig.replicationPolicy().formatRemoteTopic("b", "topic1"),
            "replication.policy.separator is honored");
        assertEquals("one:9092, two:9092", aClientConfig.adminConfig().get("bootstrap.servers"),
            "client configs include boostrap.servers");
        assertEquals("PLAINTEXT", aClientConfig.adminConfig().get("security.protocol"),
            "client configs include security.protocol");
        assertEquals("SASL", aClientConfig.producerConfig().get("security.protocol"),
            "producer configs include security.protocol");
        assertFalse(aClientConfig.adminConfig().containsKey("xxx"),
            "unknown properties aren't included in client configs");
        assertFalse(aClientConfig.adminConfig().containsKey("metric.reporters"),
            "top-leve metrics reporters aren't included in client configs");
        assertEquals("secret1", aClientConfig.getPassword("ssl.truststore.password").value(),
            "security properties are picked up in MirrorClientConfig");
        assertEquals("secret1", ((Password) aClientConfig.adminConfig().get("ssl.truststore.password")).value(),
            "client configs include top-level security properties");
        assertEquals("secret2", aClientConfig.getPassword("ssl.key.password").value(),
            "security properties are translated from external sources");
        assertEquals("secret2", ((Password) aClientConfig.adminConfig().get("ssl.key.password")).value(),
            "client configs are translated from external sources");
        assertFalse(aClientConfig.producerConfig().containsKey("metrics.reporter"),
            "client configs should not include metrics reporter");
        assertFalse(bClientConfig.adminConfig().containsKey("metrics.reporter"),
            "client configs should not include metrics reporter");
    }

    @Test
    public void testIncludesConnectorConfigProperties() {
        MirrorMakerConfig mirrorConfig = new MirrorMakerConfig(makeProps(
            "clusters", "a, b",
            "tasks.max", "100",
            "topics", "topic-1",
            "groups", "group-2",
            "replication.policy.separator", "__",
            "config.properties.exclude", "property-3",
            "metric.reporters", "FakeMetricsReporter",
            "topic.filter.class", DefaultTopicFilter.class.getName(),
            "xxx", "yyy"));
        SourceAndTarget sourceAndTarget = new SourceAndTarget("source", "target");
        Map<String, String> connectorProps = mirrorConfig.connectorBaseConfig(sourceAndTarget,
            MirrorSourceConnector.class);
        MirrorConnectorConfig connectorConfig = new MirrorConnectorConfig(connectorProps);
        assertEquals(100, (int) connectorConfig.getInt("tasks.max"),
            "Connector properties like tasks.max should be passed through to underlying Connectors.");
        assertEquals(Collections.singletonList("topic-1"), connectorConfig.getList("topics"),
            "Topics include should be passed through to underlying Connectors.");
        assertEquals(Collections.singletonList("group-2"), connectorConfig.getList("groups"),
            "Groups include should be passed through to underlying Connectors.");
        assertEquals(Collections.singletonList("property-3"), connectorConfig.getList("config.properties.exclude"),
            "Config properties exclude should be passed through to underlying Connectors.");
        assertEquals(Collections.singletonList("FakeMetricsReporter"), connectorConfig.getList("metric.reporters"),
            "Metrics reporters should be passed through to underlying Connectors.");
        assertEquals("DefaultTopicFilter", connectorConfig.getClass("topic.filter.class").getSimpleName(),
            "Filters should be passed through to underlying Connectors.");
        assertEquals("__", connectorConfig.getString("replication.policy.separator"),
            "replication policy separator should be passed through to underlying Connectors.");
        assertFalse(connectorConfig.originals().containsKey("xxx"),
            "Unknown properties should not be passed through to Connectors.");
    }

    @Test
    public void testConfigBackwardsCompatibility() {
        MirrorMakerConfig mirrorConfig = new MirrorMakerConfig(makeProps(
            "clusters", "a, b",
            "groups.blacklist", "group-7",
            "topics.blacklist", "topic3",
            "config.properties.blacklist", "property-3",
            "topic.filter.class", DefaultTopicFilter.class.getName()));
        SourceAndTarget sourceAndTarget = new SourceAndTarget("source", "target");
        Map<String, String> connectorProps = mirrorConfig.connectorBaseConfig(sourceAndTarget,
                                                                              MirrorSourceConnector.class);
        MirrorConnectorConfig connectorConfig = new MirrorConnectorConfig(connectorProps);
        DefaultTopicFilter.TopicFilterConfig filterConfig =
            new DefaultTopicFilter.TopicFilterConfig(connectorProps);

        assertEquals(Collections.singletonList("topic3"), filterConfig.getList("topics.exclude"),
            "Topics exclude should be backwards compatible.");

        assertEquals(Collections.singletonList("group-7"), connectorConfig.getList("groups.exclude"),
            "Groups exclude should be backwards compatible.");

        assertEquals(Collections.singletonList("property-3"), connectorConfig.getList("config.properties.exclude"),
            "Config properties exclude should be backwards compatible.");

    }

    @Test
    public void testConfigBackwardsCompatibilitySourceTarget() {
        MirrorMakerConfig mirrorConfig = new MirrorMakerConfig(makeProps(
            "clusters", "a, b",
            "source->target.topics.blacklist", "topic3",
            "source->target.groups.blacklist", "group-7",
            "topic.filter.class", DefaultTopicFilter.class.getName()));
        SourceAndTarget sourceAndTarget = new SourceAndTarget("source", "target");
        Map<String, String> connectorProps = mirrorConfig.connectorBaseConfig(sourceAndTarget,
                                                                              MirrorSourceConnector.class);
        MirrorConnectorConfig connectorConfig = new MirrorConnectorConfig(connectorProps);
        DefaultTopicFilter.TopicFilterConfig filterConfig =
            new DefaultTopicFilter.TopicFilterConfig(connectorProps);

        assertEquals(Collections.singletonList("topic3"), filterConfig.getList("topics.exclude"),
            "Topics exclude should be backwards compatible.");

        assertEquals(Collections.singletonList("group-7"), connectorConfig.getList("groups.exclude"),
            "Groups exclude should be backwards compatible.");
    }

    @Test
    public void testIncludesTopicFilterProperties() {
        MirrorMakerConfig mirrorConfig = new MirrorMakerConfig(makeProps(
            "clusters", "a, b",
            "source->target.topics", "topic1, topic2",
            "source->target.topics.exclude", "topic3"));
        SourceAndTarget sourceAndTarget = new SourceAndTarget("source", "target");
        Map<String, String> connectorProps = mirrorConfig.connectorBaseConfig(sourceAndTarget,
            MirrorSourceConnector.class);
        DefaultTopicFilter.TopicFilterConfig filterConfig = 
            new DefaultTopicFilter.TopicFilterConfig(connectorProps);
        assertEquals(Arrays.asList("topic1", "topic2"), filterConfig.getList("topics"),
            "source->target.topics should be passed through to TopicFilters.");
        assertEquals(Collections.singletonList("topic3"), filterConfig.getList("topics.exclude"),
            "source->target.topics.exclude should be passed through to TopicFilters.");
    }

    @Test
    public void testWorkerConfigs() {
        MirrorMakerConfig mirrorConfig = new MirrorMakerConfig(makeProps(
            "clusters", "a, b",
            "config.providers", "fake",
            "config.providers.fake.class", FakeConfigProvider.class.getName(),
            "replication.policy.separator", "__",
            "offset.storage.replication.factor", "123",
            "b.status.storage.replication.factor", "456",
            "b.producer.client.id", "client-one",
            "b.security.protocol", "PLAINTEXT",
            "b.producer.security.protocol", "SASL",
            "ssl.truststore.password", "secret1",
            "ssl.key.password", "${fake:secret:password}",  // resolves to "secret2"
            "b.xxx", "yyy"));
        SourceAndTarget a = new SourceAndTarget("b", "a");
        SourceAndTarget b = new SourceAndTarget("a", "b");
        Map<String, String> aProps = mirrorConfig.workerConfig(a);
        assertEquals("123", aProps.get("offset.storage.replication.factor"));
        Map<String, String> bProps = mirrorConfig.workerConfig(b);
        assertEquals("456", bProps.get("status.storage.replication.factor"));
        assertEquals("client-one", bProps.get("producer.client.id"),
            "producer props should be passed through to worker producer config: " + bProps);
        assertEquals("SASL", bProps.get("producer.security.protocol"),
            "replication-level security props should be passed through to worker producer config");
        assertEquals("SASL", bProps.get("producer.security.protocol"),
            "replication-level security props should be passed through to worker producer config");
        assertEquals("PLAINTEXT", bProps.get("consumer.security.protocol"),
            "replication-level security props should be passed through to worker consumer config");
        assertEquals("secret1", bProps.get("ssl.truststore.password"),
            "security properties should be passed through to worker config: " + bProps);
        assertEquals("secret1", bProps.get("producer.ssl.truststore.password"),
            "security properties should be passed through to worker producer config: " + bProps);
        assertEquals("secret2", bProps.get("ssl.key.password"),
            "security properties should be transformed in worker config");
        assertEquals("secret2", bProps.get("producer.ssl.key.password"),
            "security properties should be transformed in worker producer config");
    }

    @Test
    public void testClusterPairsWithDefaultSettings() {
        MirrorMakerConfig mirrorConfig = new MirrorMakerConfig(makeProps(
                "clusters", "a, b, c"));
        // implicit configuration associated
        // a->b.enabled=false
        // a->b.emit.heartbeat.enabled=true
        // a->c.enabled=false
        // a->c.emit.heartbeat.enabled=true
        // b->a.enabled=false
        // b->a.emit.heartbeat.enabled=true
        // b->c.enabled=false
        // b->c.emit.heartbeat.enabled=true
        // c->a.enabled=false
        // c->a.emit.heartbeat.enabled=true
        // c->b.enabled=false
        // c->b.emit.heartbeat.enabled=true
        List<SourceAndTarget> clusterPairs = mirrorConfig.clusterPairs();
        assertEquals(6, clusterPairs.size(), "clusterPairs count should match all combinations count");
    }

    @Test
    public void testEmptyClusterPairsWithGloballyDisabledHeartbeats() {
        MirrorMakerConfig mirrorConfig = new MirrorMakerConfig(makeProps(
                "clusters", "a, b, c",
                "emit.heartbeats.enabled", "false"));
        assertEquals(0, mirrorConfig.clusterPairs().size(), "clusterPairs count should be 0");
    }

    @Test
    public void testClusterPairsWithTwoDisabledHeartbeats() {
        MirrorMakerConfig mirrorConfig = new MirrorMakerConfig(makeProps(
                "clusters", "a, b, c",
                "a->b.emit.heartbeats.enabled", "false",
                "a->c.emit.heartbeats.enabled", "false"));
        List<SourceAndTarget> clusterPairs = mirrorConfig.clusterPairs();
        assertEquals(4, clusterPairs.size(),
            "clusterPairs count should match all combinations count except x->y.emit.heartbeats.enabled=false");
    }

    @Test
    public void testClusterPairsWithGloballyDisabledHeartbeats() {
        MirrorMakerConfig mirrorConfig = new MirrorMakerConfig(makeProps(
                "clusters", "a, b, c, d, e, f",
                "emit.heartbeats.enabled", "false",
                "a->b.enabled", "true",
                "a->c.enabled", "true",
                "a->d.enabled", "true",
                "a->e.enabled", "false",
                "a->f.enabled", "false"));
        List<SourceAndTarget> clusterPairs = mirrorConfig.clusterPairs();
        assertEquals(3, clusterPairs.size(),
            "clusterPairs count should match (x->y.enabled=true or x->y.emit.heartbeats.enabled=true) count");

        // Link b->a.enabled doesn't exist therefore it must not be in clusterPairs
        SourceAndTarget sourceAndTarget = new SourceAndTarget("b", "a");
        assertFalse(clusterPairs.contains(sourceAndTarget), "disabled/unset link x->y should not be in clusterPairs");
    }

    @Test
    public void testClusterPairsWithGloballyDisabledHeartbeatsCentralLocal() {
        MirrorMakerConfig mirrorConfig = new MirrorMakerConfig(makeProps(
                "clusters", "central, local_one, local_two, beats_emitter",
                "emit.heartbeats.enabled", "false",
                "central->local_one.enabled", "true",
                "central->local_two.enabled", "true",
                "beats_emitter->central.emit.heartbeats.enabled", "true"));

        assertEquals(3, mirrorConfig.clusterPairs().size(),
            "clusterPairs count should match (x->y.enabled=true or x->y.emit.heartbeats.enabled=true) count");
    }

    public static class FakeConfigProvider implements ConfigProvider {

        Map<String, String> secrets = Collections.singletonMap("password", "secret2");

        @Override
        public void configure(Map<String, ?> props) {
        }

        @Override
        public void close() {
        }

        @Override
        public ConfigData get(String path) {
            return new ConfigData(secrets);
        }

        @Override
        public ConfigData get(String path, Set<String> keys) {
            return get(path);
        }
    }
}
