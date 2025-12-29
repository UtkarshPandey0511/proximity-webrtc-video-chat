package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.*;

@Component
public class SignalingHandler extends TextWebSocketHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    // room -> (playerId -> PlayerState)
    private final Map<String, Map<String, PlayerState>> rooms = new HashMap<>();

    // sessionId -> session
    private final Map<String, WebSocketSession> sessions = new HashMap<>();

    // global lock for message writes
    private final Object sendLock = new Object();

    private static final double PROX_RADIUS = 140.0;
    private static final double EXIT_RADIUS = 160.0;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {

        JsonNode json = mapper.readTree(message.getPayload());
        String type = json.get("type").asText();

        switch (type) {

            case "join" -> handleJoin(session, json);
            case "move" -> handleMove(json);
            case "offer", "answer", "candidate" -> relaySignal(json);
        }
    }

    // ---------------- JOIN ----------------

    private void handleJoin(WebSocketSession session, JsonNode json) throws Exception {

        String room = json.get("room").asText();
        String id = json.get("playerId").asText();
        String name = json.get("name").asText();

        rooms.putIfAbsent(room, new HashMap<>());
        rooms.get(room).put(id, new PlayerState(id, name, room));

        session.getAttributes().put("playerId", id);
        session.getAttributes().put("room", room);

        broadcastPlayers(room);
    }

    // ---------------- MOVE ----------------

    private void handleMove(JsonNode json) throws Exception {

        String room = json.get("room").asText();
        String id = json.get("playerId").asText();

        Map<String, PlayerState> players = rooms.get(room);
        if (players == null) return;

        PlayerState p = players.get(id);
        if (p == null) return;

        p.x = json.get("x").asDouble();
        p.y = json.get("y").asDouble();

        // recompute proximity clusters
        computeClusters(room);

        // ⭐ NEW: broadcast live positions
        Map<String,Object> msg = new HashMap<>();
        msg.put("type", "PLAYERS");
        msg.put("players", players.values());

        sendRoom(room, msg);
    }


    // ---------------- SIGNAL RELAY ----------------

    private void relaySignal(JsonNode json) throws Exception {

        String to = json.get("to").asText();
        String payload = mapper.writeValueAsString(json);

        for (WebSocketSession s : sessions.values()) {

            Object sid = s.getAttributes().get("playerId");

            if (sid != null && sid.equals(to)) {
                safeSend(s, payload);
            }
        }
    }

    // ---------------- BROADCAST PLAYERS ----------------

    private void broadcastPlayers(String room) throws Exception {

        Map<String, PlayerState> players = rooms.get(room);

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "PLAYERS");
        msg.put("players", players.values());

        sendRoom(room, msg);
    }

    // ---------------- CLUSTERING ----------------

    private void computeClusters(String room) throws Exception {

        Map<String, PlayerState> players = rooms.get(room);
        if (players == null) return;

        List<PlayerState> list = new ArrayList<>(players.values());

        Map<String,String> previous = new HashMap<>();
        for (PlayerState p : list) previous.put(p.id, p.clusterId);

        for (PlayerState p : list) p.clusterId = null;

        int idx = 1;

        for (PlayerState p : list) {

            if (p.clusterId != null) continue;

            String cid = "c" + idx++;
            bfsAssign(p, cid, list);

            List<String> members = list.stream()
                    .filter(pl -> cid.equals(pl.clusterId))
                    .map(pl -> pl.id)
                    .toList();

            if (members.size() > 1)
                sendClusterJoin(room, cid, members);
        }

        for (PlayerState p : list) {

            String oldC = previous.get(p.id);

            if (oldC != null && !Objects.equals(oldC, p.clusterId)) {
                sendClusterLeave(room, oldC, p.id);
            }
        }
    }

    private void bfsAssign(PlayerState root, String cid, List<PlayerState> all) {

        Queue<PlayerState> q = new LinkedList<>();
        root.clusterId = cid;
        q.add(root);

        while (!q.isEmpty()) {

            PlayerState a = q.poll();

            for (PlayerState b : all) {

                if (b.clusterId != null) continue;

                double d = Math.hypot(a.x - b.x, a.y - b.y);

                boolean join =
                        (a.clusterId == null && d < PROX_RADIUS) ||
                                (a.clusterId != null && d < EXIT_RADIUS);

                if (join) {
                    b.clusterId = cid;
                    q.add(b);
                }
            }
        }
    }

    // ---------------- CLUSTER EVENTS ----------------

    private void sendClusterJoin(String room, String cid, List<String> members) throws Exception {

        Map<String,Object> msg = new HashMap<>();
        msg.put("type","CLUSTER_JOIN");
        msg.put("clusterId", cid);
        msg.put("members", members);

        sendRoom(room, msg);
    }

    private void sendClusterLeave(String room, String cid, String playerId) throws Exception {

        Map<String,Object> msg = new HashMap<>();
        msg.put("type","CLUSTER_LEAVE");
        msg.put("clusterId", cid);
        msg.put("playerId", playerId);

        sendRoom(room, msg);
    }

    // ---------------- SAFE SEND HELPERS ----------------

    private void safeSend(WebSocketSession session, String payload) {
        synchronized (sendLock) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
            } catch (Exception e) {
                System.err.println("WebSocket send failed: " + e.getMessage());
            }
        }
    }

    private void sendRoom(String room, Object msg) throws Exception {

        String payload = mapper.writeValueAsString(msg);

        for (WebSocketSession s : sessions.values()) {

            if (room.equals(s.getAttributes().get("room"))) {
                safeSend(s, payload);
            }
        }
    }
}
