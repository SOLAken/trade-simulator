package com.tradesim.trade.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface OrderMapper extends BaseMapper<OrderPO> {

    /**
     * 成交回写：以 filled_qty 作 CAS 版本号，并发撤单/成交时只有一方成功，另一方重算后重试。
     */
    @Update("""
            UPDATE t_order SET filled_qty = filled_qty + #{qty}, filled_amount = filled_amount + #{amount},
                   used = used + #{used}, status = #{status}
            WHERE id = #{id} AND user_id = #{userId} AND filled_qty = #{expectFilled} AND status IN (0, 1)""")
    int applyFill(@Param("id") long id, @Param("userId") long userId, @Param("expectFilled") long expectFilled,
                  @Param("qty") long qty, @Param("amount") long amount, @Param("used") long used, @Param("status") int status);

    @Update("""
            UPDATE t_order SET status = #{status}, reject_reason = #{reason}
            WHERE id = #{id} AND user_id = #{userId} AND filled_qty = #{expectFilled} AND status IN (0, 1)""")
    int close(@Param("id") long id, @Param("userId") long userId, @Param("expectFilled") long expectFilled,
              @Param("status") int status, @Param("reason") String reason);

    /** 无分片键 → ShardingSphere 广播到全部 8 张分表（仅启动恢复时调用一次）。 */
    @Select("SELECT * FROM t_order WHERE node = #{node} AND status IN (0, 1)")
    List<OrderPO> listOpenByNode(@Param("node") int node);

    @Select("SELECT * FROM t_order WHERE user_id = #{userId} AND client_key = #{key}")
    OrderPO findByClientKey(@Param("userId") long userId, @Param("key") String key);

    @Select("""
            <script>
            SELECT * FROM t_order WHERE user_id = #{userId} AND contest_id = #{contestId}
            <if test="openOnly"> AND status IN (0, 1)</if>
            ORDER BY created_at DESC LIMIT #{limit}
            </script>""")
    List<OrderPO> listByUser(@Param("userId") long userId, @Param("contestId") long contestId,
                             @Param("openOnly") boolean openOnly, @Param("limit") int limit);
}
