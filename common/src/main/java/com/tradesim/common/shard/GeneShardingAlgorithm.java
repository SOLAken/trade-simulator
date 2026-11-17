package com.tradesim.common.shard;

import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingAlgorithm;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;

import java.util.*;

/**
 * 基因分片：2 库 × 4 表 = 8 个物理分片，slot = key % 8，库 = slot / 4，表 = slot % 4。
 * <p>
 * 分片键可以是 user_id，也可以是基因法生成的 id / order_id（低位嵌入了 user_id 的低 6 位，
 * 对 8 取模结果与 user_id 相同），所以「按用户查列表」与「按订单号查详情」都能精确路由，不会广播。
 * 两者都没有时退化为全分片广播（仅对账、管理后台使用）。
 * <p>
 * 扩容：slot 数 8 对应基因 64 的约数，未来扩到 64 分片时只需迁移数据、不用改 ID。
 */
public class GeneShardingAlgorithm implements ComplexKeysShardingAlgorithm<Comparable<?>> {
    public static final int SLOTS = 8;
    public static final int TABLES_PER_DB = 4;
    private static final List<String> KEY_COLUMNS = List.of("user_id", "id", "order_id");

    private String target = "table";

    @Override
    public void init(Properties props) {
        this.target = props.getProperty("target", "table");
    }

    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames,
                                         ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        Map<String, Collection<Comparable<?>>> values = shardingValue.getColumnNameAndShardingValuesMap();
        for (String col : KEY_COLUMNS) {
            Collection<Comparable<?>> keys = values.get(col);
            if (keys == null || keys.isEmpty()) continue;
            Set<String> result = new LinkedHashSet<>();
            for (Comparable<?> k : keys) {
                String suffix = "_" + suffix(((Number) k).longValue());
                availableTargetNames.stream().filter(t -> t.endsWith(suffix)).forEach(result::add);
            }
            return result;
        }
        return availableTargetNames;
    }

    int suffix(long key) {
        int slot = (int) Math.floorMod(key, (long) SLOTS);
        return "db".equals(target) ? slot / TABLES_PER_DB : slot % TABLES_PER_DB;
    }

    @Override
    public String getType() {
        return "GENE";
    }
}
