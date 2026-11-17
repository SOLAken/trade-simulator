package com.tradesim.common.id;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GeneSnowflakeTest {

    @Test
    void idCarriesUserGeneSoShardingMatches() {
        GeneSnowflake sf = new GeneSnowflake(1);
        for (long uid : new long[]{0, 7, 63, 64, 12345, Long.MAX_VALUE >> 2}) {
            long id = sf.nextId(uid);
            assertThat(GeneSnowflake.gene(id)).isEqualTo(uid & 63);
            assertThat(id % 8).isEqualTo(uid % 8);  // 分片算法 x % 8 对 id 与 user_id 等价
        }
    }

    @Test
    void idsAreUniqueAndIncreasing() {
        GeneSnowflake sf = new GeneSnowflake(3);
        Set<Long> seen = new HashSet<>();
        long prev = -1;
        for (int i = 0; i < 200_000; i++) {
            long id = sf.nextId(42);
            assertThat(id).isGreaterThan(prev);
            assertThat(seen.add(id)).isTrue();
            prev = id;
        }
    }
}
