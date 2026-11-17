package com.tradesim.trade.api;

import com.tradesim.common.web.Headers;
import com.tradesim.common.web.Result;
import com.tradesim.trade.domain.OrderMapper;
import com.tradesim.trade.domain.OrderPO;
import com.tradesim.trade.domain.TradeMapper;
import com.tradesim.trade.domain.TradePO;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/trade")
public class TradeController {
    private final OrderService service;
    private final OrderMapper orders;
    private final TradeMapper trades;

    public TradeController(OrderService service, OrderMapper orders, TradeMapper trades) {
        this.service = service;
        this.orders = orders;
        this.trades = trades;
    }

    /** 客户端每次「提交」生成一个 Idempotency-Key（UUID），网络重试时复用同一个。 */
    @PostMapping("/orders")
    public Result<OrderPO> place(@RequestHeader(Headers.USER_ID) long userId,
                                 @RequestHeader(Headers.IDEMPOTENCY_KEY) @NotBlank String key,
                                 @RequestBody OrderService.PlaceReq req) {
        if (key.length() > 64) throw new IllegalArgumentException("Idempotency-Key 过长");
        return Result.ok(service.place(userId, key, req));
    }

    @DeleteMapping("/orders/{id}")
    public Result<Boolean> cancel(@RequestHeader(Headers.USER_ID) long userId, @PathVariable long id) {
        return Result.ok(service.cancel(userId, id));
    }

    @GetMapping("/orders")
    public Result<List<OrderPO>> list(@RequestHeader(Headers.USER_ID) long userId,
                                      @RequestParam long contestId,
                                      @RequestParam(defaultValue = "false") boolean openOnly) {
        return Result.ok(orders.listByUser(userId, contestId, openOnly, 100));
    }

    @GetMapping("/trades")
    public Result<List<TradePO>> trades(@RequestHeader(Headers.USER_ID) long userId, @RequestParam long contestId) {
        return Result.ok(trades.listByUser(userId, contestId, 100));
    }
}
