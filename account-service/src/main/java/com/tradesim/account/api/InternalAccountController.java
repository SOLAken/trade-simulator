package com.tradesim.account.api;

import com.tradesim.account.domain.LedgerMapper;
import com.tradesim.common.fund.FundStore;
import com.tradesim.common.web.Result;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** 服务间接口：仅 user-service 报名比赛时调用，网关不路由 /internal/**。 */
@RestController
@RequestMapping("/internal/account")
public class InternalAccountController {
    private final LedgerMapper ledger;
    private final FundStore fund;

    public InternalAccountController(LedgerMapper ledger, FundStore fund) {
        this.ledger = ledger;
        this.fund = fund;
    }

    /** 开户：MySQL 账本落库后初始化 Redis 热数据（两步都幂等，重复报名安全）。 */
    @PostMapping("/open")
    @Transactional
    public Result<Boolean> open(@RequestParam long userId, @RequestParam long contestId, @RequestParam long initCash) {
        boolean created = ledger.insertAccount(userId, contestId, initCash) == 1;
        if (created) ledger.insertFlow(userId, contestId, contestId, "INIT", null, initCash, 0, 0);
        fund.openAccount(userId, contestId, initCash);
        return Result.ok(created);
    }
}
