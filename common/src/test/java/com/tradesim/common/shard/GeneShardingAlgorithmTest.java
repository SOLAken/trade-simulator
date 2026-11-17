package com.tradesim.common.shard;

import com.tradesim.common.id.GeneSnowflake;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class GeneShardingAlgorithmTest {
    private static GeneShardingAlgorithm algo(String target) {
        var a = new GeneShardingAlgorithm();
        var p = new Properties();
        p.setProperty("target", target);
        a.init(p);
        return a;
    }

    @Test
    void orderIdRoutesToSameShardAsUser() {
        var db = algo("db");
        var tb = algo("table");
        var sf = new GeneSnowflake(0);
        for (long uid = 10001; uid < 10100; uid++) {
            long oid = sf.nextId(uid);
            assertThat(db.suffix(oid)).isEqualTo(db.suffix(uid));
            assertThat(tb.suffix(oid)).isEqualTo(tb.suffix(uid));
        }
    }

    @Test
    void eightSlotsAreAllUsed() {
        var db = algo("db");
        var tb = algo("table");
        var seen = new HashSet<String>();
        for (long uid = 0; uid < 8; uid++) seen.add(db.suffix(uid) + "." + tb.suffix(uid));
        assertThat(seen).hasSize(8);
    }
}
