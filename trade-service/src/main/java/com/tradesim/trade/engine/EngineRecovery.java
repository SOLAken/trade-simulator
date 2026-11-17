package com.tradesim.trade.engine;

import com.tradesim.common.order.OrderStatus;
import com.tradesim.common.order.OrderType;
import com.tradesim.common.rule.TradeRules;
import com.tradesim.trade.config.TradeConfig.NodeId;
import com.tradesim.trade.domain.OrderMapper;
import com.tradesim.trade.domain.OrderPO;
import com.tradesim.trade.ledger.OrderLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动恢复：从 MySQL 重建本节点的订单簿。
 * - 当日的限价/止损挂单 → 重新入簿（按创建时间保持时间优先）
 * - 过期挂单、悬空的市价单（宕机时未来得及成交）→ 撤销并解冻
 */
@Component
public class EngineRecovery implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(EngineRecovery.class);

    private final OrderMapper orders;
    private final MatchingEngine engine;
    private final OrderLedger ledger;
    private final TradeRules rules;
    private final int node;

    public EngineRecovery(OrderMapper orders, MatchingEngine engine, OrderLedger ledger, TradeRules rules, NodeId node) {
        this.orders = orders;
        this.engine = engine;
        this.ledger = ledger;
        this.rules = rules;
        this.node = node.value();
    }

    @Override
    public void run(ApplicationArguments args) {
        int today = rules.clock().tradingDay(System.currentTimeMillis());
        var open = orders.listOpenByNode(node);
        open.sort(java.util.Comparator.comparing((OrderPO o) -> o.createdAt));
        int restored = 0, closed = 0;
        for (OrderPO o : open) {
            if (o.type == OrderType.MARKET.code || o.tradingDay < today) {
                ledger.cancel(o.id, OrderStatus.CANCELED, o.type == OrderType.MARKET.code ? "系统恢复撤销" : "收盘未成交，自动撤销");
                closed++;
            } else {
                engine.submit(EngineRouter.toBook(o));
                restored++;
            }
        }
        log.info("engine recovery node={} restored={} closed={}", node, restored, closed);
    }
}
