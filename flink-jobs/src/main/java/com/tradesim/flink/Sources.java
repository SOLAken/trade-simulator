package com.tradesim.flink;

import com.tradesim.flink.model.Json;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.io.Serializable;

/** Kafka 源/汇与作业公共配置。 */
final class Sources {
    static final String KAFKA = System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "kafka:29092");

    private Sources() {}

    static StreamExecutionEnvironment env() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        // 10 秒一次 checkpoint；作业重启从最近 checkpoint 恢复状态与 Kafka 位点
        env.enableCheckpointing(10_000);
        env.getCheckpointConfig().setMinPauseBetweenCheckpoints(5_000);
        env.getCheckpointConfig().setExternalizedCheckpointCleanup(
                CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
        return env;
    }

    /**
     * @param fromEarliest 首次启动从最早位点读：收益计算需要完整成交历史；风控只关心实时，从最新读
     */
    static <T> DataStream<T> json(StreamExecutionEnvironment env, String topic, String group,
                                  boolean fromEarliest, Class<T> type) {
        KafkaSource<String> src = KafkaSource.<String>builder()
                .setBootstrapServers(KAFKA)
                .setTopics(topic)
                .setGroupId(group)
                .setStartingOffsets(OffsetsInitializer.committedOffsets(
                        fromEarliest ? org.apache.kafka.clients.consumer.OffsetResetStrategy.EARLIEST
                                     : org.apache.kafka.clients.consumer.OffsetResetStrategy.LATEST))
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();
        return env.fromSource(src, WatermarkStrategy.noWatermarks(), topic)
                .map(s -> Json.read(s, type)).returns(type).name("parse-" + topic);
    }

    /** 算子闭包会被序列化分发到 TaskManager，java.util.function.Function 不可序列化。 */
    interface KeyFn<T> extends Serializable {
        String apply(T value);
    }

    static <T> KafkaSink<T> sink(String topic, KeyFn<T> key) {
        return KafkaSink.<T>builder()
                .setBootstrapServers(KAFKA)
                .setRecordSerializer(KafkaRecordSerializationSchema.<T>builder()
                        .setTopic(topic)
                        .setKeySerializationSchema(v -> key.apply(v).getBytes())
                        .setValueSerializationSchema(v -> Json.write(v).getBytes())
                        .build())
                // 下游幂等（排行榜覆盖写、告警有冷却），用 at-least-once 免去 Kafka 事务的延迟
                .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                .build();
    }
}
