package com.tradesim.common.fund;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Lua 资金脚本在真实 Redis 上的行为：原子冻结、幂等、T+1、成本结转。 */
@Testcontainers
class FundStoreTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2.5").withExposedPorts(6379);

    static LettuceConnectionFactory cf;
    static StringRedisTemplate redis;
    FundStore fund;

    static final long U = 10001, C = 1;
    static final String S = "SIM001";
    static final int BUY = 1, SELL = 2;

    @BeforeAll
    static void connect() {
        cf = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        cf.afterPropertiesSet();
        redis = new StringRedisTemplate(cf);
    }

    @AfterAll
    static void close() {
        cf.destroy();
    }

    @BeforeEach
    void reset() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        fund = new FundStore(redis);
        assertThat(fund.openAccount(U, C, 1_000_000)).isTrue();
    }

    @Test
    void openAccountIsIdempotent() {
        assertThat(fund.openAccount(U, C, 999)).isFalse();
        assertThat(acct().get("cash")).isEqualTo("1000000");
    }

    @Test
    void freezeRejectsWhenInsufficientAndIsIdempotent() {
        assertThat(fund.freezeCash(U, C, 1, 2_000_000)).isEqualTo(FundStore.INSUFFICIENT);
        assertThat(fund.freezeCash(U, C, 2, 600_000)).isEqualTo(FundStore.OK);
        assertThat(fund.freezeCash(U, C, 2, 600_000)).isEqualTo(FundStore.DUPLICATE);   // 重复投递
        assertThat(acct()).containsEntry("cash", "400000").containsEntry("frozen", "600000");
        assertThat(fund.freezeCash(99, C, 3, 1)).isEqualTo(FundStore.NO_ACCOUNT);
    }

    @Test
    void buySettleReleasesRemainderAndBlocksSameDaySell() {
        fund.freezeCash(U, C, 1, 110_500);                       // 按涨停价冻结
        // 成交 100 股 @ 10.00：成交额 100000 + 佣金 500，订单终结退回 10000
        assertThat(fund.settle(U, C, S, 900, BUY, 100, 100_000, 500, 10_000, 5)).isTrue();
        assertThat(fund.settle(U, C, S, 900, BUY, 100, 100_000, 500, 10_000, 5)).isFalse();   // 幂等
        assertThat(acct()).containsEntry("cash", "899500").containsEntry("frozen", "0");
        assertThat(pos()).containsEntry("qty", "100").containsEntry("pending", "100").containsEntry("cost", "100500");

        // 当日（day=5）卖出：可卖为 0
        assertThat(fund.freezePosition(U, C, S, 2, 100, 5)).isEqualTo(FundStore.INSUFFICIENT);
        // 次日（day=6）：pending 惰性转为可卖
        assertThat(fund.freezePosition(U, C, S, 3, 100, 6)).isEqualTo(FundStore.OK);
        assertThat(pos()).containsEntry("avail", "0").containsEntry("frozen", "100").containsEntry("pending", "0");
    }

    @Test
    void partialSellCarriesCostProportionally() {
        fund.freezeCash(U, C, 1, 200_000);
        fund.settle(U, C, S, 900, BUY, 1000, 100_000, 500, 99_500, 1);      // 成本 100500
        fund.freezePosition(U, C, S, 2, 600, 2);
        // 卖 400 股（部分成交，未终结）：成本结转 100500 × 400/1000 = 40200
        fund.settle(U, C, S, 901, SELL, 400, 60_000, 500, 0, 2);
        assertThat(pos()).containsEntry("qty", "600").containsEntry("frozen", "200").containsEntry("cost", "60300");
        // 撤掉剩余 200 股：退回可卖；重复撤单无效
        assertThat(fund.release(U, C, S, 2, SELL, 200, 2)).isTrue();
        assertThat(fund.release(U, C, S, 2, SELL, 200, 2)).isFalse();
        assertThat(pos()).containsEntry("avail", "600").containsEntry("frozen", "0");
        // 卖出到账 = 60000 - 佣金税费 500
        assertThat(Long.parseLong((String) acct().get("cash"))).isEqualTo(1_000_000 - 100_500 + 59_500);
    }

    @Test
    void undoFreezeAllowsRetryWithSameOrderId() {
        fund.freezeCash(U, C, 7, 300_000);
        fund.undoFreeze(U, C, S, 7, BUY, 300_000);
        assertThat(acct()).containsEntry("cash", "1000000").containsEntry("frozen", "0");
        assertThat(fund.freezeCash(U, C, 7, 300_000)).isEqualTo(FundStore.OK);
    }

    private Map<Object, Object> acct() {
        return redis.opsForHash().entries(FundKeys.account(U, C));
    }

    private Map<Object, Object> pos() {
        return redis.opsForHash().entries(FundKeys.position(U, C, S));
    }
}
