package com.tradesim.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("t_trade")
public class TradePO {
    @TableId(type = IdType.INPUT)
    public Long id;
    public Long orderId;
    public Long userId;
    public Long contestId;
    public String symbol;
    public Integer side;
    public Long price;
    public Long qty;
    public Long amount;
    public Long commission;
    public Long tax;
    public Integer tradingDay;
    public LocalDateTime createdAt;
}
