package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ChatSessionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ChatSessionRegistry.class);
    public static final String ATTR_CLIENT_ID = "chatClientId";

    private final ConcurrentHashMap<String, List<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public ChatSessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void registerSession(String userId, WebSocketSession session) {
        registerSession(userId, null, session);
    }

    public void registerSession(String userId, String clientId, WebSocketSession session) {
        String resolvedClientId = normalizeClientId(clientId, session);
        Map<String, Object> attributes = session.getAttributes();
        if (attributes != null) {
            attributes.put(ATTR_CLIENT_ID, resolvedClientId);
        }
        List<WebSocketSession> sessionList = sessions.compute(userId, (k, list) -> {
            if (list == null) {
                list = new CopyOnWriteArrayList<>();
            }
            list.add(session);
            return list;
        });
        logger.info("注册 WebSocket session，用户ID: {}，clientId: {}，会话ID: {}，当前连接数: {}",
                userId, resolvedClientId, session.getId(), sessionList.size());
    }

    public void unregisterSession(String userId, WebSocketSession session) {
        sessions.computeIfPresent(userId, (key, list) -> {
            list.removeIf(s -> Objects.equals(s.getId(), session.getId()));
            logger.info("注销 WebSocket session，用户ID: {}，会话ID: {}，剩余连接数: {}",
                    userId, session.getId(), list.size());
            return list.isEmpty() ? null : list;
        });
    }

    public List<WebSocketSession> getSessions(String userId) {
        List<WebSocketSession> sessionList = sessions.get(userId);
        if (sessionList == null || sessionList.isEmpty()) {
            return List.of();
        }
        // 返回活跃的 session
        return sessionList.stream()
                .filter(WebSocketSession::isOpen)
                .toList();
    }

    public void sendJsonToUser(String userId, Map<String, ?> payload) {
        List<WebSocketSession> sessionList = sessions.get(userId);
        if (sessionList == null || sessionList.isEmpty()) {
            logger.debug("用户 {} 当前没有可用的 WebSocket 会话，跳过发送", userId);
            return;
        }

        String jsonMessage;
        try {
            jsonMessage = objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            logger.error("序列化消息失败: {}", e.getMessage(), e);
            return;
        }

        for (WebSocketSession session : sessionList) {
            try {
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(jsonMessage));
                    }
                }
            } catch (Exception e) {
                logger.warn("发送消息到 session {} 失败: {}", session.getId(), e.getMessage());
                // 不影响其他 session
            }
        }
    }

    public void sendJsonToClient(String userId, String clientId, Map<String, ?> payload) {
        if (clientId == null || clientId.isBlank()) {
            logger.debug("用户 {} 的目标 clientId 为空，跳过定向发送", userId);
            return;
        }

        List<WebSocketSession> sessionList = sessions.get(userId);
        if (sessionList == null || sessionList.isEmpty()) {
            logger.debug("用户 {} 当前没有可用的 WebSocket 会话，跳过定向发送", userId);
            return;
        }

        String jsonMessage;
        try {
            jsonMessage = objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            logger.error("序列化消息失败: {}", e.getMessage(), e);
            return;
        }

        boolean sent = false;
        for (WebSocketSession session : sessionList) {
            if (!clientId.equals(getClientId(session))) {
                continue;
            }
            try {
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(jsonMessage));
                        sent = true;
                    }
                }
            } catch (Exception e) {
                logger.warn("发送消息到 session {} 失败: {}", session.getId(), e.getMessage());
            }
        }

        if (!sent) {
            logger.debug("用户 {} 没有匹配 clientId {} 的可用 WebSocket 会话，跳过发送", userId, clientId);
        }
    }

    public String getClientId(WebSocketSession session) {
        if (session == null) {
            return null;
        }
        Map<String, Object> attributes = session.getAttributes();
        if (attributes == null) {
            return null;
        }
        Object value = attributes.get(ATTR_CLIENT_ID);
        return value instanceof String clientId && !clientId.isBlank() ? clientId : null;
    }

    private String normalizeClientId(String clientId, WebSocketSession session) {
        if (clientId != null && !clientId.isBlank()) {
            return clientId.trim();
        }
        return session.getId();
    }
}
