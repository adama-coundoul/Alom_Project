package alom.privatemsg;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.StringSerializer;

@WebServlet("/api/private/*")
public class PrivateService extends HttpServlet {

    // token -> nickname
    private static final Map<String, String> USERS_TOKENS = new ConcurrentHashMap<>();
    private static KafkaProducer<Long, String> producer;

    @Override
    public void init() throws ServletException {
        super.init();
        initKafkaProducer();
        System.out.println("PrivateService servlet initialisée (Kafka prêt)");
    }

    @Override
    public void destroy() {
        super.destroy();
        if (producer != null) {
            producer.close();
        }
        System.out.println("PrivateService servlet détruite (Kafka fermé)");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        String path = req.getPathInfo(); // ex: /send ou /sync/token
        String body = new String(req.getInputStream().readAllBytes());
        resp.setContentType("application/json");

        if ("/sync/token".equals(path)) {
            handleTokenSync(body, resp);
        } else if ("/send".equals(path)) {
            handleSendPrivate(body, resp);
        } else {
            sendResponse(resp, 404, "{\"error\":\"unknown path\"}");
        }
    }

    // ========== /api/private/sync/token ==========

    private void handleTokenSync(String body, HttpServletResponse resp) throws IOException {
        Map<String, String> map = parseJson(body);

        String token = map.get("token");
        String nickname = map.get("nickname");

        if (token != null && nickname != null) {
            USERS_TOKENS.put(token, nickname);
        }

        sendResponse(resp, 200, "{\"status\":\"token synced\"}");
    }

    // ========== /api/private/send ==========

    private void handleSendPrivate(String body, HttpServletResponse resp) throws IOException {
        Map<String, String> json = parseJson(body);

        String token = json.get("token");
        String content = json.get("content");
        String toNickname = json.get("to");

        if (token == null || content == null || toNickname == null) {
            sendResponse(resp, 400, "{\"error\":\"missing fields\"}");
            return;
        }

        String fromNickname = USERS_TOKENS.get(token);
        if (fromNickname == null) {
            sendResponse(resp, 401, "{\"error\":\"invalid token\"}");
            return;
        }

        String finalMessage = "[Msg de " + fromNickname + "] " + content;

        boolean sent = forwardToNotificationService(finalMessage, toNickname);

        if (!sent) {
            sendResponse(resp, 500, "{\"error\":\"delivery failed\"}");
            return;
        }

        sendResponse(resp, 200, "{\"status\":\"sent\"}");
    }

    // ========== Kafka vers NotificationService ==========

    private boolean forwardToNotificationService(String message, String toNickname) {
        try {
            String json = String.format(
                    "{\"message\":\"%s\",\"to\":\"%s\"}",
                    message, toNickname
            );

            String topicName = "user." + toNickname;   // ex: user.alice, user.bob

            ProducerRecord<Long, String> record =
                    new ProducerRecord<>(topicName, System.currentTimeMillis(), json);

            producer.send(record).get();  // envoie et attend l'ACK
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // ========== Utils ==========

    private Map<String, String> parseJson(String json) {
        Map<String, String> map = new HashMap<>();
        if (json == null) return map;
        json = json.trim().replaceAll("[{}\"]", "");
        if (json.isEmpty()) return map;
        for (String part : json.split(",")) {
            String[] kv = part.split(":", 2);
            if (kv.length == 2)
                map.put(kv[0].trim(), kv[1].trim());
        }
        return map;
    }

    private void sendResponse(HttpServletResponse resp, int status, String text) throws IOException {
        resp.setStatus(status);
        try (PrintWriter out = resp.getWriter()) {
            out.write(text);
            out.flush();
        }
    }

    private void initKafkaProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "private-service");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, LongSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        producer = new KafkaProducer<>(props);
    }
}
