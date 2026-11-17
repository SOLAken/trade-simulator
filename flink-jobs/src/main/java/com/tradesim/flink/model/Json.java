package com.tradesim.flink.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;

public final class Json {
    public static final ObjectMapper OM = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Json() {}

    public static <T> T read(String s, Class<T> type) {
        try {
            return OM.readValue(s, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String write(Object o) {
        try {
            return OM.writeValueAsString(o);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
