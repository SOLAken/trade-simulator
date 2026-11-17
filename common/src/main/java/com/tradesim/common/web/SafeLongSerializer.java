package com.tradesim.common.web;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;

/**
 * 超过 JS 安全整数（2^53）的 long 输出为字符串，其余仍为数字。
 * 雪花 ID 约 60 位，直接输出数字会被浏览器截断成错误的订单号；价格、金额等小数值不受影响。
 * Jackson 反序列化默认接受字符串形式的数字，下游 Java/Flink 消费者无需改动。
 */
public class SafeLongSerializer extends StdSerializer<Long> {
    private static final long MAX_SAFE = (1L << 53) - 1;

    public SafeLongSerializer() {
        super(Long.class);
    }

    @Override
    public void serialize(Long value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        if (value > MAX_SAFE || value < -MAX_SAFE) gen.writeString(value.toString());
        else gen.writeNumber(value);
    }
}
