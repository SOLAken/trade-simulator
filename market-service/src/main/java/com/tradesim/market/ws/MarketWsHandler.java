package com.tradesim.market.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradesim.common.mq.Topics;
import com.tradesim.market.sim.MarketSimulator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行情推送。每个 market 实例用独立 consumer group 消费 market.tick（广播语义），
 * 因此 WebSocket 连接落在任意实例都能收到全量行情，实例可水平扩展。
 * <p>
 * 协议：客户端发 {"op":"sub","symbols":["SIM001"]}，"*" 表示全部。
 * 慢客户端：ConcurrentWebSocketSessionDecorator 限制发送缓冲 512KB / 2s，超限断开，避免拖垮推送线程。
 */
@Component
public class MarketWsHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(MarketWsHandler.class);

    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final ObjectMapper om;
    private final StringRedisTemplate redis;

    public MarketWsHandler(ObjectMapper om, StringRedisTemplate redis) {
        this.om = om;
        this.redis = redis;
    }

    private record Client(WebSocketSession session, Set<String> subs) {}

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        clients.put(session.getId(), new Client(
                new ConcurrentWebSocketSessionDecorator(session, 2000, 512 * 1024), ConcurrentHashMap.newKeySet()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Client c = clients.get(session.getId());
        if (c == null) return;
        JsonNode n = om.readTree(message.getPayload());
        if (!"sub".equals(n.path("op").asText())) return;
        c.subs().clear();
        n.path("symbols").forEach(s -> c.subs().add(s.asText()));
        // 订阅后立即推一次快照，前端不必等下一个 tick
        if (!c.subs().contains("*")) {
            var snaps = redis.opsForValue().multiGet(c.subs().stream().map(s -> MarketSimulator.LAST_KEY + s).toList());
            if (snaps != null) for (String s : snaps) if (s != null) send(c, s);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        clients.remove(session.getId());
    }

    @KafkaListener(topics = Topics.MARKET_TICK, groupId = "market-ws-#{T(java.util.UUID).randomUUID()}")
    public void onTick(ConsumerRecord<String, String> rec) {
        String symbol = Objects.requireNonNull(rec.key());
        for (Client c : clients.values()) {
            if (c.subs().contains("*") || c.subs().contains(symbol)) send(c, rec.value());
        }
    }

    private void send(Client c, String payload) {
        try {
            c.session().sendMessage(new TextMessage(payload));
        } catch (Exception e) {
            log.debug("drop ws client {}: {}", c.session().getId(), e.toString());
            clients.remove(c.session().getId());
            try { c.session().close(CloseStatus.SESSION_NOT_RELIABLE); } catch (Exception ignored) { }
        }
    }
}
