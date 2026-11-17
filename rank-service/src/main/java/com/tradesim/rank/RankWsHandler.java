package com.tradesim.rank;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 排行榜推送：客户端发 {"contestId":1}，每次快照后推送前 20 名。同一比赛的所有连接共享一次序列化。 */
@Component
public class RankWsHandler extends TextWebSocketHandler {
    private final Map<String, Sub> subs = new ConcurrentHashMap<>();
    private final ObjectMapper om;
    private final RankService ranks;

    public RankWsHandler(ObjectMapper om, @Lazy RankService ranks) {
        this.om = om;
        this.ranks = ranks;
    }

    private record Sub(WebSocketSession session, long contestId) {}

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        long cid = om.readTree(message.getPayload()).path("contestId").asLong(1);
        subs.put(session.getId(), new Sub(new ConcurrentWebSocketSessionDecorator(session, 2000, 256 * 1024), cid));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        subs.remove(session.getId());
    }

    private final java.util.Set<Long> dirty = ConcurrentHashMap.newKeySet();

    /** 标记榜单有变化；实际推送由 flush() 每秒合并一次，避免实时更新流把客户端刷爆。 */
    public void markDirty(long contestId) {
        dirty.add(contestId);
    }

    @Scheduled(fixedDelay = 1000)
    public void flush() {
        for (Long cid : java.util.List.copyOf(dirty)) {
            dirty.remove(cid);
            publish(cid);
        }
    }

    public void publish(long contestId) {
        if (subs.values().stream().noneMatch(s -> s.contestId() == contestId)) return;
        TextMessage msg;
        try {
            msg = new TextMessage(om.writeValueAsString(ranks.board(contestId, 20, null)));
        } catch (Exception e) {
            return;
        }
        subs.values().forEach(s -> {
            if (s.contestId() != contestId) return;
            try {
                s.session().sendMessage(msg);
            } catch (Exception e) {
                subs.remove(s.session().getId());
            }
        });
    }
}
