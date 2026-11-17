package com.tradesim.account.mq;

import com.tradesim.account.domain.LedgerMapper;
import com.tradesim.common.order.FundMessages;
import com.tradesim.common.order.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 消息 → 账本。每个方法一个本地事务：先写流水（幂等闸），再做增量。 */
@Service
public class LedgerService {
    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);
    private final LedgerMapper ledger;

    public LedgerService(LedgerMapper ledger) {
        this.ledger = ledger;
    }

    @Transactional
    public void freeze(FundMessages.Freeze m) {
        boolean buy = m.side() == Side.BUY.code;
        if (!flow(m.userId(), m.contestId(), m.orderId(), "FREEZE", m.symbol(),
                buy ? -m.amount() : 0, buy ? m.amount() : 0, buy ? 0 : -m.amount())) return;
        if (buy) ledger.addAccount(m.userId(), m.contestId(), -m.amount(), m.amount());
        else ledger.addPosition(m.userId(), m.contestId(), m.symbol(), 0, m.amount());
    }

    @Transactional
    public void settle(FundMessages.Settle m) {
        if (m.side() == Side.BUY.code) {
            long cost = m.amount() + m.commission();
            if (!flow(m.userId(), m.contestId(), m.tradeId(), "SETTLE", m.symbol(),
                    m.release(), -cost - m.release(), m.qty())) return;
            ledger.addAccount(m.userId(), m.contestId(), m.release(), -cost - m.release());
            ledger.addPosition(m.userId(), m.contestId(), m.symbol(), m.qty(), 0);
        } else {
            long proceeds = m.amount() - m.commission() - m.tax();
            if (!flow(m.userId(), m.contestId(), m.tradeId(), "SETTLE", m.symbol(),
                    proceeds, 0, -m.qty())) return;
            ledger.addAccount(m.userId(), m.contestId(), proceeds, 0);
            ledger.addPosition(m.userId(), m.contestId(), m.symbol(), -m.qty(), -m.qty() - m.release());
        }
    }

    @Transactional
    public void release(FundMessages.Release m) {
        boolean buy = m.side() == Side.BUY.code;
        if (!flow(m.userId(), m.contestId(), m.orderId(), "RELEASE", m.symbol(),
                buy ? m.release() : 0, buy ? -m.release() : 0, buy ? 0 : m.release())) return;
        if (buy) ledger.addAccount(m.userId(), m.contestId(), m.release(), -m.release());
        else ledger.addPosition(m.userId(), m.contestId(), m.symbol(), 0, -m.release());
    }

    /** @return false 表示重复消息 */
    private boolean flow(long uid, long cid, long bizId, String type, String symbol, long cash, long frozen, long qty) {
        try {
            ledger.insertFlow(uid, cid, bizId, type, symbol, cash, frozen, qty);
            return true;
        } catch (DuplicateKeyException e) {
            log.info("duplicate {} biz={} skipped", type, bizId);
            return false;
        }
    }
}
