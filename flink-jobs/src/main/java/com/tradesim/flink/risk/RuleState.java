package com.tradesim.flink.risk;

import com.tradesim.flink.model.Events.RiskRules;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ReadOnlyBroadcastState;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;

/** 风控阈值的广播状态：所有并行实例共享同一份，新规则到达后下一条事件即按新阈值判断。 */
public final class RuleState {
    public static final MapStateDescriptor<String, RiskRules> DESC =
            new MapStateDescriptor<>("risk-rules", Types.STRING, TypeInformation.of(RiskRules.class));
    public static final String KEY = "current";
    private static final RiskRules DEFAULTS = new RiskRules();

    private RuleState() {}

    public static RiskRules get(ReadOnlyBroadcastState<String, RiskRules> s) throws Exception {
        RiskRules r = s.get(KEY);
        return r == null ? DEFAULTS : r;
    }
}
