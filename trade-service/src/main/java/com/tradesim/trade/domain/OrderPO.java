package com.tradesim.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("t_order")
public class OrderPO {
    @TableId(type = IdType.INPUT)
    public Long id;
    public Long userId;
    public Long contestId;
    public String clientKey;
    public String symbol;
    public Integer side;
    public Integer type;
    public Long price;
    public Long stopPrice;
    public Long qty;
    public Long filledQty;
    public Long filledAmount;
    public Long frozen;
    public Long used;
    public Integer status;
    public String rejectReason;
    public Integer tradingDay;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
