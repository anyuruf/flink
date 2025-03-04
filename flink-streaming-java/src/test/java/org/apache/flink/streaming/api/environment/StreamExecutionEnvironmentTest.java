/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.environment;

import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.operators.SlotSharingGroup;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.connector.source.Source;
import org.apache.flink.api.connector.source.lib.NumberSequenceSource;
import org.apache.flink.api.java.typeutils.GenericTypeInfo;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ExecutionOptions;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.configuration.StateChangelogOptions;
import org.apache.flink.connector.datagen.source.DataGeneratorSource;
import org.apache.flink.core.testutils.CheckedThread;
import org.apache.flink.core.testutils.OneShotLatch;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.functions.sink.v2.DiscardingSink;
import org.apache.flink.streaming.api.functions.source.legacy.FromElementsFunction;
import org.apache.flink.streaming.api.functions.source.legacy.SourceFunction;
import org.apache.flink.streaming.api.graph.StreamGraph;
import org.apache.flink.streaming.api.graph.StreamGraphGenerator;
import org.apache.flink.streaming.api.operators.AbstractUdfStreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.transformations.SourceTransformation;
import org.apache.flink.types.Row;
import org.apache.flink.util.Collector;
import org.apache.flink.util.SplittableIterator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/** Tests for {@link StreamExecutionEnvironment}. */
class StreamExecutionEnvironmentTest {

    @Test
    void fromElementsWithBaseTypeTest1() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.fromData(ParentClass.class, new SubClass(1, "Java"), new ParentClass(1, "hello"));
    }

    @Test
    void fromElementsWithBaseTypeTest2() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        assertThatThrownBy(
                        () ->
                                env.fromData(
                                        SubClass.class,
                                        new SubClass(1, "Java"),
                                        new ParentClass(1, "hello")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testFromElementsDeducedType() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStreamSource<String> source = env.fromData("a", "b");

        DataGeneratorSource<String> generatorSource = getSourceFromStream(source);

        assertThat(generatorSource.getProducedType()).isEqualTo(BasicTypeInfo.STRING_TYPE_INFO);
    }

    @Test
    void testFromElementsPostConstructionType() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStreamSource<String> source = env.fromData("a", "b");
        TypeInformation<String> customType = new GenericTypeInfo<>(String.class);

        source.returns(customType);

        DataGeneratorSource<String> generatorSource = getSourceFromStream(source);
        source.sinkTo(new DiscardingSink<>());
        env.getStreamGraph();

        assertThat(generatorSource.getProducedType()).isNotEqualTo(BasicTypeInfo.STRING_TYPE_INFO);
        assertThat(generatorSource.getProducedType()).isEqualTo(customType);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testFromElementsPostConstructionTypeIncompatible() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStreamSource<String> source = env.fromData("a", "b");
        source.returns((TypeInformation) BasicTypeInfo.INT_TYPE_INFO);
        source.sinkTo(new DiscardingSink<>());

        assertThatThrownBy(env::getStreamGraph)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not all subclasses of java.lang.Integer");
    }

    @Test
    void testFromElementsNullElement() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        assertThatThrownBy(() -> env.fromData("a", null, "c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contains a null element");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFromCollectionParallelism() {
        try {
            TypeInformation<Integer> typeInfo = BasicTypeInfo.INT_TYPE_INFO;
            StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

            DataStreamSource<Integer> dataStream2 =
                    env.fromParallelCollection(new DummySplittableIterator<Integer>(), typeInfo)
                            .setParallelism(4);

            dataStream2.sinkTo(new DiscardingSink<>());

            final StreamGraph streamGraph = env.getStreamGraph();
            streamGraph.getStreamingPlanAsJSON();

            assertThat(streamGraph.getStreamNode(dataStream2.getId()).getParallelism())
                    .as("Parallelism of parallel collection source must be 4.")
                    .isEqualTo(4);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testSources() {
        // TODO: remove this test when SourceFunction API gets removed together with the deprecated
        //  StreamExecutionEnvironment generateSequence() and fromCollection() methods
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        SourceFunction<Integer> srcFun =
                new SourceFunction<Integer>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void run(SourceContext<Integer> ctx) throws Exception {}

                    @Override
                    public void cancel() {}
                };
        DataStreamSource<Integer> src1 = env.addSource(srcFun);
        src1.sinkTo(new DiscardingSink<>());
        assertThat(getFunctionFromDataSource(src1)).isEqualTo(srcFun);

        List<Long> list = Arrays.asList(0L, 1L, 2L);

        DataStreamSource<Long> src2 = env.fromSequence(0, 2);
        Object generatorSource = getSourceFromStream(src2);
        assertThat(generatorSource).isInstanceOf(NumberSequenceSource.class);

        DataStreamSource<Long> src3 = env.fromData(0L, 1L, 2L);
        assertThat(getSourceFromDataSourceTyped(src3)).isInstanceOf(DataGeneratorSource.class);

        DataStreamSource<Long> src4 = env.fromCollection(list);
        assertThat(getFunctionFromDataSource(src4)).isInstanceOf(FromElementsFunction.class);
    }

    /** Verifies that the API method doesn't throw and creates a source of the expected type. */
    @Test
    void testFromSequence() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStreamSource<Long> src = env.fromSequence(0, 2);

        assertThat(src.getType()).isEqualTo(BasicTypeInfo.LONG_TYPE_INFO);
    }

    @Test
    void testParallelismBounds() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        SourceFunction<Integer> srcFun =
                new SourceFunction<Integer>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void run(SourceContext<Integer> ctx) throws Exception {}

                    @Override
                    public void cancel() {}
                };

        SingleOutputStreamOperator<Object> operator =
                env.addSource(srcFun)
                        .flatMap(
                                new FlatMapFunction<Integer, Object>() {

                                    private static final long serialVersionUID = 1L;

                                    @Override
                                    public void flatMap(Integer value, Collector<Object> out)
                                            throws Exception {}
                                });

        // default value for max parallelism
        assertThat(operator.getTransformation().getMaxParallelism()).isEqualTo(-1);

        // bounds for parallelism 1
        assertThatThrownBy(() -> operator.setParallelism(0))
                .isInstanceOf(IllegalArgumentException.class);

        // bounds for parallelism 2
        operator.setParallelism(1);
        assertThat(operator.getParallelism()).isOne();

        // bounds for parallelism 3
        operator.setParallelism(1 << 15);
        assertThat(operator.getParallelism()).isEqualTo(1 << 15);

        // default value after generating
        env.getStreamGraph(false).getJobGraph();
        assertThat(operator.getTransformation().getMaxParallelism()).isEqualTo(-1);

        // configured value after generating
        env.setMaxParallelism(42);
        env.getStreamGraph(false).getJobGraph();
        assertThat(operator.getTransformation().getMaxParallelism()).isEqualTo(42);

        // bounds configured parallelism 1
        assertThatThrownBy(() -> env.setMaxParallelism(0))
                .isInstanceOf(IllegalArgumentException.class);

        // bounds configured parallelism 2
        assertThatThrownBy(() -> env.setMaxParallelism(1 + (1 << 15)))
                .isInstanceOf(IllegalArgumentException.class);

        // bounds for max parallelism 1
        assertThatThrownBy(() -> operator.setMaxParallelism(0))
                .isInstanceOf(IllegalArgumentException.class);

        // bounds for max parallelism 2
        assertThatThrownBy(() -> operator.setMaxParallelism(1 + (1 << 15)))
                .isInstanceOf(IllegalArgumentException.class);

        // bounds for max parallelism 3
        operator.setMaxParallelism(1);
        assertThat(operator.getTransformation().getMaxParallelism()).isOne();

        // bounds for max parallelism 4
        operator.setMaxParallelism(1 << 15);
        assertThat(operator.getTransformation().getMaxParallelism()).isEqualTo(1 << 15);

        // override config
        env.getStreamGraph(false).getJobGraph();
        assertThat(operator.getTransformation().getMaxParallelism()).isEqualTo(1 << 15);
    }

    @Test
    void testRegisterSlotSharingGroup() {
        final SlotSharingGroup ssg1 =
                SlotSharingGroup.newBuilder("ssg1").setCpuCores(1).setTaskHeapMemoryMB(100).build();
        final SlotSharingGroup ssg2 =
                SlotSharingGroup.newBuilder("ssg2").setCpuCores(2).setTaskHeapMemoryMB(200).build();
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.registerSlotSharingGroup(ssg1);
        env.registerSlotSharingGroup(ssg2);
        env.registerSlotSharingGroup(SlotSharingGroup.newBuilder("ssg3").build());

        final DataStream<Integer> source = env.fromData(1).slotSharingGroup("ssg1");
        source.map(value -> value).slotSharingGroup(ssg2).sinkTo(new DiscardingSink<>());

        final StreamGraph streamGraph = env.getStreamGraph();
        assertThat(streamGraph.getSlotSharingGroupResource("ssg1").get())
                .isEqualTo(ResourceProfile.fromResources(1, 100));
        assertThat(streamGraph.getSlotSharingGroupResource("ssg2").get())
                .isEqualTo(ResourceProfile.fromResources(2, 200));
        assertThat(streamGraph.getSlotSharingGroupResource("ssg3")).isNotPresent();
    }

    @Test
    void testRegisterSlotSharingGroupConflict() {
        final SlotSharingGroup ssg =
                SlotSharingGroup.newBuilder("ssg1").setCpuCores(1).setTaskHeapMemoryMB(100).build();
        final SlotSharingGroup ssgConflict =
                SlotSharingGroup.newBuilder("ssg1").setCpuCores(2).setTaskHeapMemoryMB(200).build();
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.registerSlotSharingGroup(ssg);

        final DataStream<Integer> source = env.fromData(1).slotSharingGroup("ssg1");
        source.map(value -> value).slotSharingGroup(ssgConflict).sinkTo(new DiscardingSink<>());

        assertThatThrownBy(env::getStreamGraph).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testGetStreamGraph() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStreamSource<Integer> dataStream1 = env.fromData(1, 2, 3);
        dataStream1.sinkTo(new DiscardingSink<>());
        assertThat(env.getStreamGraph().getStreamNodes().size()).isEqualTo(2);

        DataStreamSource<Integer> dataStream2 = env.fromData(1, 2, 3);
        dataStream2.sinkTo(new DiscardingSink<>());
        // Previous getStreamGraph() call cleaned dataStream1 transformations
        assertThat(env.getStreamGraph().getStreamNodes().size()).isEqualTo(2);

        DataStreamSource<Integer> dataStream3 = env.fromData(1, 2, 3);
        dataStream3.sinkTo(new DiscardingSink<>());
        // Does not clear the transformations.
        env.getExecutionPlan();
        DataStreamSource<Integer> dataStream4 = env.fromData(1, 2, 3);
        dataStream4.sinkTo(new DiscardingSink<>());
        // dataStream3 are preserved
        assertThat(env.getStreamGraph().getStreamNodes().size()).isEqualTo(4);
    }

    @Test
    void testDefaultJobName() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        testJobName(StreamGraphGenerator.DEFAULT_STREAMING_JOB_NAME, env);

        env.setRuntimeMode(RuntimeExecutionMode.BATCH);
        testJobName(StreamGraphGenerator.DEFAULT_BATCH_JOB_NAME, env);
    }

    @Test
    void testUserDefinedJobName() {
        String jobName = "MyTestJob";
        Configuration config = new Configuration();
        config.set(PipelineOptions.NAME, jobName);
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(config);
        testJobName(jobName, env);
    }

    @Test
    void testUserDefinedJobNameWithConfigure() {
        String jobName = "MyTestJob";
        Configuration config = new Configuration();
        config.set(PipelineOptions.NAME, jobName);
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.configure(config, this.getClass().getClassLoader());
        testJobName(jobName, env);
    }

    private void testJobName(String expectedJobName, StreamExecutionEnvironment env) {
        env.fromData(1, 2, 3).print();
        StreamGraph streamGraph = env.getStreamGraph();
        assertThat(streamGraph.getJobName()).isEqualTo(expectedJobName);
    }

    @Test
    void testAddSourceWithUserDefinedTypeInfo() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStreamSource<Row> source1 =
                env.addSource(new RowSourceFunction(), Types.ROW(Types.STRING));
        // the source type information should be the user defined type
        assertThat(source1.getType()).isEqualTo(Types.ROW(Types.STRING));

        DataStreamSource<Row> source2 = env.addSource(new RowSourceFunction());
        // the source type information should be derived from RowSourceFunction#getProducedType
        assertThat(source2.getType()).isEqualTo(new GenericTypeInfo<>(Row.class));
    }

    @Test
    void testPeriodicMaterializeEnabled() {
        Configuration config = new Configuration();
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.configure(config, this.getClass().getClassLoader());
        assertThat(env.getConfig().isPeriodicMaterializeEnabled())
                .isEqualTo(StateChangelogOptions.PERIODIC_MATERIALIZATION_ENABLED.defaultValue());

        config.set(StateChangelogOptions.PERIODIC_MATERIALIZATION_ENABLED, false);
        env.configure(config, this.getClass().getClassLoader());
        assertThat(env.getConfig().isPeriodicMaterializeEnabled()).isFalse();
    }

    @Test
    void testPeriodicMaterializeInterval() {
        Configuration config = new Configuration();
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.configure(config, this.getClass().getClassLoader());
        assertThat(env.getConfig().getPeriodicMaterializeIntervalMillis())
                .isEqualTo(
                        StateChangelogOptions.PERIODIC_MATERIALIZATION_INTERVAL
                                .defaultValue()
                                .toMillis());

        config.setString(StateChangelogOptions.PERIODIC_MATERIALIZATION_INTERVAL.key(), "60s");
        env.configure(config, this.getClass().getClassLoader());
        assertThat(env.getConfig().getPeriodicMaterializeIntervalMillis()).isEqualTo(60 * 1000);

        assertThatThrownBy(
                        () -> {
                            config.setString(
                                    StateChangelogOptions.PERIODIC_MATERIALIZATION_INTERVAL.key(),
                                    "-1ms");
                            env.configure(config, this.getClass().getClassLoader());
                        })
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testBufferTimeoutByDefault() {
        Configuration config = new Configuration();
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        testBufferTimeout(config, env);
    }

    @Test
    void testBufferTimeoutEnabled() {
        Configuration config = new Configuration();
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        config.set(ExecutionOptions.BUFFER_TIMEOUT_ENABLED, true);
        testBufferTimeout(config, env);
    }

    @Test
    void testBufferTimeoutDisabled() {
        Configuration config = new Configuration();
        config.set(ExecutionOptions.BUFFER_TIMEOUT_ENABLED, false);
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // The execution.buffer-timeout's default value 100ms will not take effect.
        env.configure(config, this.getClass().getClassLoader());
        assertThat(env.getBufferTimeout())
                .isEqualTo(ExecutionOptions.DISABLED_NETWORK_BUFFER_TIMEOUT);

        // Setting execution.buffer-timeout's to 0ms will not take effect.
        env.setBufferTimeout(0);
        assertThat(env.getBufferTimeout())
                .isEqualTo(ExecutionOptions.DISABLED_NETWORK_BUFFER_TIMEOUT);

        // Setting execution.buffer-timeout's to -1ms will not take effect.
        env.setBufferTimeout(-1);
        assertThat(env.getBufferTimeout())
                .isEqualTo(ExecutionOptions.DISABLED_NETWORK_BUFFER_TIMEOUT);
    }

    @Test
    void testAsyncExecutionConfigurations() {
        Configuration config = new Configuration();
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.configure(config, this.getClass().getClassLoader());

        assertThat(env.getConfig().getAsyncStateTotalBufferSize())
                .isEqualTo(ExecutionOptions.ASYNC_STATE_TOTAL_BUFFER_SIZE.defaultValue());
        assertThat(env.getConfig().getAsyncStateActiveBufferSize())
                .isEqualTo(ExecutionOptions.ASYNC_STATE_ACTIVE_BUFFER_SIZE.defaultValue());
        assertThat(env.getConfig().getAsyncStateActiveBufferTimeout())
                .isEqualTo(ExecutionOptions.ASYNC_STATE_ACTIVE_BUFFER_TIMEOUT.defaultValue());

        config.set(ExecutionOptions.ASYNC_STATE_TOTAL_BUFFER_SIZE, 3);
        config.set(ExecutionOptions.ASYNC_STATE_ACTIVE_BUFFER_SIZE, 2);
        config.set(ExecutionOptions.ASYNC_STATE_ACTIVE_BUFFER_TIMEOUT, 1L);
        env.configure(config, this.getClass().getClassLoader());
        assertThat(env.getConfig().getAsyncStateTotalBufferSize()).isEqualTo(3);
        assertThat(env.getConfig().getAsyncStateActiveBufferSize()).isEqualTo(2);
        assertThat(env.getConfig().getAsyncStateActiveBufferTimeout()).isEqualTo(1);

        env.getConfig()
                .setAsyncStateTotalBufferSize(6)
                .setAsyncStateActiveBufferSize(5)
                .setAsyncStateActiveBufferTimeout(4);
        assertThat(env.getConfig().getAsyncStateTotalBufferSize()).isEqualTo(6);
        assertThat(env.getConfig().getAsyncStateActiveBufferSize()).isEqualTo(5);
        assertThat(env.getConfig().getAsyncStateActiveBufferTimeout()).isEqualTo(4);
    }

    private void testBufferTimeout(Configuration config, StreamExecutionEnvironment env) {
        env.configure(config, this.getClass().getClassLoader());
        assertThat(env.getBufferTimeout())
                .isEqualTo(ExecutionOptions.BUFFER_TIMEOUT.defaultValue().toMillis());

        config.setString(ExecutionOptions.BUFFER_TIMEOUT.key(), "0ms");
        env.configure(config, this.getClass().getClassLoader());
        assertThat(env.getBufferTimeout()).isZero();

        assertThatThrownBy(
                        () -> {
                            config.setString(ExecutionOptions.BUFFER_TIMEOUT.key(), "-1ms");
                            env.configure(config, this.getClass().getClassLoader());
                            env.getBufferTimeout();
                        })
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testConcurrentSetContext() throws Exception {
        int numThreads = 20;
        final CountDownLatch waitingThreadCount = new CountDownLatch(numThreads);
        final OneShotLatch latch = new OneShotLatch();
        final List<CheckedThread> threads = new ArrayList<>();
        for (int x = 0; x < numThreads; x++) {
            final CheckedThread thread =
                    new CheckedThread() {
                        @Override
                        public void go() {
                            final StreamExecutionEnvironment preparedEnvironment =
                                    new StreamExecutionEnvironment();
                            StreamExecutionEnvironment.initializeContextEnvironment(
                                    configuration -> preparedEnvironment);
                            try {
                                waitingThreadCount.countDown();
                                latch.awaitQuietly();
                                assertThat(StreamExecutionEnvironment.getExecutionEnvironment())
                                        .isSameAs(preparedEnvironment);
                            } finally {
                                StreamExecutionEnvironment.resetContextEnvironment();
                            }
                        }
                    };
            thread.start();
            threads.add(thread);
        }

        // wait for all threads to be ready and trigger the job submissions at the same time
        waitingThreadCount.await();
        latch.trigger();

        for (CheckedThread thread : threads) {
            thread.sync();
        }
    }

    /////////////////////////////////////////////////////////////
    // Utilities
    /////////////////////////////////////////////////////////////

    private static StreamOperator<?> getOperatorFromDataStream(DataStream<?> dataStream) {
        StreamExecutionEnvironment env = dataStream.getExecutionEnvironment();
        StreamGraph streamGraph = env.getStreamGraph();
        return streamGraph.getStreamNode(dataStream.getId()).getOperator();
    }

    @SuppressWarnings("unchecked")
    private static <T> SourceFunction<T> getFunctionFromDataSource(
            DataStreamSource<T> dataStreamSource) {
        dataStreamSource.sinkTo(new DiscardingSink<>());
        AbstractUdfStreamOperator<?, ?> operator =
                (AbstractUdfStreamOperator<?, ?>) getOperatorFromDataStream(dataStreamSource);
        return (SourceFunction<T>) operator.getUserFunction();
    }

    @SuppressWarnings("unchecked")
    private static <T, S extends Source<T, ?, ?>> S getSourceFromStream(DataStream<T> stream) {
        return (S) ((SourceTransformation<T, ?, ?>) stream.getTransformation()).getSource();
    }

    private static <T> Source<T, ?, ?> getSourceFromDataSourceTyped(
            DataStreamSource<T> dataStreamSource) {
        dataStreamSource.sinkTo(new DiscardingSink<>());
        dataStreamSource.getExecutionEnvironment().getStreamGraph();
        return ((SourceTransformation<T, ?, ?>) dataStreamSource.getTransformation()).getSource();
    }

    private static class DummySplittableIterator<T> extends SplittableIterator<T> {
        private static final long serialVersionUID = 1312752876092210499L;

        @SuppressWarnings("unchecked")
        @Override
        public Iterator<T>[] split(int numPartitions) {
            return (Iterator<T>[]) new Iterator<?>[0];
        }

        @Override
        public int getMaximumNumberOfSplits() {
            return 0;
        }

        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public T next() {
            throw new NoSuchElementException();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private static class ParentClass {
        int num;
        String string;

        public ParentClass(int num, String string) {
            this.num = num;
            this.string = string;
        }
    }

    private static class SubClass extends ParentClass {
        public SubClass(int num, String string) {
            super(num, string);
        }
    }

    private static class RowSourceFunction
            implements SourceFunction<Row>, ResultTypeQueryable<Row> {
        private static final long serialVersionUID = 5216362688122691404L;

        @Override
        public TypeInformation<Row> getProducedType() {
            return TypeInformation.of(Row.class);
        }

        @Override
        public void run(SourceContext<Row> ctx) throws Exception {}

        @Override
        public void cancel() {}
    }
}
