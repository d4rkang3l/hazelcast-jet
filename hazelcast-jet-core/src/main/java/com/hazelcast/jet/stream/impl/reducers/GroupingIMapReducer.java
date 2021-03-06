/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.stream.impl.reducers;

import com.hazelcast.core.IMap;
import com.hazelcast.jet.DAG;
import com.hazelcast.jet.Distributed;
import com.hazelcast.jet.Vertex;
import com.hazelcast.jet.stream.DistributedCollector;
import com.hazelcast.jet.stream.DistributedCollector.Reducer;
import com.hazelcast.jet.stream.impl.pipeline.Pipeline;
import com.hazelcast.jet.stream.impl.pipeline.StreamContext;
import com.hazelcast.jet.stream.impl.processor.CombineGroupsP;
import com.hazelcast.jet.stream.impl.processor.GroupAndAccumulateP;

import java.util.function.Function;
import java.util.stream.Collector;

import static com.hazelcast.jet.Edge.between;
import static com.hazelcast.jet.KeyExtractors.entryKey;
import static com.hazelcast.jet.Partitioner.HASH_CODE;
import static com.hazelcast.jet.Processors.writeMap;
import static com.hazelcast.jet.stream.impl.StreamUtil.executeJob;
import static com.hazelcast.jet.stream.impl.StreamUtil.uniqueMapName;

public class GroupingIMapReducer<T, A, K, D> implements Reducer<T, IMap<K, D>> {

    private final String mapName;
    private final Function<? super T, ? extends K> classifier;
    private final Collector<? super T, A, D> collector;

    public GroupingIMapReducer(Distributed.Function<? super T, ? extends K> classifier,
                               DistributedCollector<? super T, A, D> downstream) {
        this(uniqueMapName(), classifier, downstream);
    }

    private GroupingIMapReducer(String mapName, Function<? super T, ? extends K> classifier,
                                Collector<? super T, A, D> collector) {
        this.mapName = mapName;
        this.classifier = classifier;
        this.collector = collector;
    }

    @Override
    public IMap<K, D> reduce(StreamContext context, Pipeline<? extends T> upstream) {
        IMap<K, D> target = context.getJetInstance().getMap(mapName);

        DAG dag = new DAG();
        Vertex previous = upstream.buildDAG(dag);
        Vertex merger = dag.newVertex("group-and-accumulate",
                () -> new GroupAndAccumulateP<>(classifier, collector));
        Vertex combiner = dag.newVertex("combine-groups", () -> new CombineGroupsP<>(collector));
        Vertex writer = dag.newVertex("write-map-" + mapName, writeMap(mapName));

        dag.edge(between(previous, merger).partitioned(classifier::apply, HASH_CODE))
           .edge(between(merger, combiner).distributed().partitioned(entryKey()))
           .edge(between(combiner, writer));
        executeJob(context, dag);
        return target;
    }
}
