package com.tradesim.account.domain;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * MySQL 账本。所有余额变更都是「流水 + 增量 UPDATE」：
 * 流水插入撞唯一键 = 重复消息，整笔跳过；增量 UPDATE 满足交换律，消息乱序也收敛到同一结果。
 */
@Mapper
public interface LedgerMapper {

    @Insert("""
            INSERT INTO t_fund_flow(user_id, contest_id, biz_id, type, symbol, cash_delta, frozen_delta, qty_delta)
            VALUES (#{uid}, #{cid}, #{bizId}, #{type}, #{symbol}, #{cash}, #{frozen}, #{qty})""")
    void insertFlow(@Param("uid") long uid, @Param("cid") long cid, @Param("bizId") long bizId, @Param("type") String type,
                    @Param("symbol") String symbol, @Param("cash") long cash, @Param("frozen") long frozen, @Param("qty") long qty);

    @Insert("INSERT IGNORE INTO t_account(user_id, contest_id, init_cash, cash, frozen) VALUES (#{uid}, #{cid}, #{cash}, #{cash}, 0)")
    int insertAccount(@Param("uid") long uid, @Param("cid") long cid, @Param("cash") long cash);

    @Update("UPDATE t_account SET cash = cash + #{cash}, frozen = frozen + #{frozen} WHERE user_id = #{uid} AND contest_id = #{cid}")
    int addAccount(@Param("uid") long uid, @Param("cid") long cid, @Param("cash") long cash, @Param("frozen") long frozen);

    @Insert("""
            INSERT INTO t_position(user_id, contest_id, symbol, qty, frozen) VALUES (#{uid}, #{cid}, #{symbol}, #{qty}, #{frozen})
            ON DUPLICATE KEY UPDATE qty = qty + #{qty}, frozen = frozen + #{frozen}""")
    void addPosition(@Param("uid") long uid, @Param("cid") long cid, @Param("symbol") String symbol,
                     @Param("qty") long qty, @Param("frozen") long frozen);

    @Select("SELECT user_id, contest_id, init_cash, cash, frozen FROM t_account WHERE user_id = #{uid} AND contest_id = #{cid}")
    Map<String, Object> account(@Param("uid") long uid, @Param("cid") long cid);

    @Select("SELECT symbol, qty, frozen FROM t_position WHERE user_id = #{uid} AND contest_id = #{cid} AND qty <> 0")
    List<Map<String, Object>> positions(@Param("uid") long uid, @Param("cid") long cid);
}
