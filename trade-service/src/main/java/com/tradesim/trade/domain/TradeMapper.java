package com.tradesim.trade.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TradeMapper extends BaseMapper<TradePO> {
    @Select("SELECT * FROM t_trade WHERE user_id = #{userId} AND contest_id = #{contestId} ORDER BY created_at DESC LIMIT #{limit}")
    List<TradePO> listByUser(@Param("userId") long userId, @Param("contestId") long contestId, @Param("limit") int limit);
}
