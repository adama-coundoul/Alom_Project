package alom.channel;

// Importations Jakarta EE (pour Tomcat 10+)
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Importations Kafka
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// Mappe la Servlet au chemin /api/channel/*
@WebServlet("/api/channel/*")
public class ChannelService extends HttpServlet {

    // --- Stockage des Données (Conservez les attributs de classe) ---
    private static final Map<String, Set<String>> CHANNEL_MEMBERS = new ConcurrentHashMap<>();
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();
    
    // ⚠️ Le threadPool et le port ne sont plus nécessaires, mais le producer doit rester
    private KafkaProducer<Long, String> producer;

    // ----------------------------------------------------------------------
    // --- 1. Cycle de Vie Servlet (Remplacement de main() et initKafkaProducer()) ---
    // ----------------------------------------------------------------------

    @Override
    public void init() throws ServletException {
        super.init();
        System.out.println("ChannelServiceServlet : Initialisation du Kafka Producer.");
        // Initialisation de Kafka
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "channel-service");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, LongSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        producer = new KafkaProducer<>(props);
        
        // Note: Le threadPool n'est plus géré ici, Tomcat utilise son propre pool.
    }

    @Override
    public void destroy() {
        super.destroy();
        if (producer != null) {
            producer.close();
            System.out.println("ChannelServiceServlet : Kafka Producer fermé.");
        }
    }


    // ----------------------------------------------------------------------
    // --- 2. Point d'Entrée HTTP (Remplacement de HttpServer.createContext) ---
    // ----------------------------------------------------------------------

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String pathInfo = request.getPathInfo(); // ex: /sync/token ou /join
        String body = new String(request.getInputStream().readAllBytes());
        Map<String, String> data = parseJson(body);

        response.setContentType("application/json");

        if ("/sync/token".equals(pathInfo)) {
            handleTokenSync(data, response);
        } else if ("/join".equals(pathInfo)) {
            handleJoinChannel(data, response);
        } else if ("/leave".equals(pathInfo)) {
            handleLeaveChannel(data, response);
        } else if ("/send".equals(pathInfo)) {
            handleSendToChannel(data, response);
        } else {
            sendJson(response, HttpServletResponse.SC_NOT_FOUND, "{\"error\":\"Endpoint not found\"}");
        }
    }


    // ----------------------------------------------------------------------
    // --- 3. Méthodes de Logique (Adaptées pour utiliser HttpServletResponse) ---
    // ----------------------------------------------------------------------

    private void handleTokenSync(Map<String, String> map, HttpServletResponse response) throws IOException {
        String token = map.get("token");
        String nickname = map.get("nickname");

        if (token != null && nickname != null) {
            TOKENS.put(token, nickname);
            // Renvoie une réponse simple sans contenu
            response.setStatus(HttpServletResponse.SC_OK); 
            response.getWriter().write(""); // Réponse vide car UserService attend -1 bytes
            response.getWriter().flush();
            return;
        }

        sendJson(response, HttpServletResponse.SC_BAD_REQUEST, "{\"error\":\"missing token or nickname\"}");
    }

    private void handleJoinChannel(Map<String, String> json, HttpServletResponse response) throws IOException {
        String channel = json.get("channel");
        String token = json.get("token");

        String nickname = TOKENS.get(token);
        if (channel == null || nickname == null) {
            sendJson(response, HttpServletResponse.SC_BAD_REQUEST, "{\"error\":\"invalid channel or token\"}");
            return;
        }

        CHANNEL_MEMBERS
                .computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet())
                .add(nickname);

        sendJson(response, HttpServletResponse.SC_OK, "{\"status\":\"joined\",\"channel\":\"" + channel + "\"}");
    }

    private void handleLeaveChannel(Map<String, String> json, HttpServletResponse response) throws IOException {
        String channel = json.get("channel");
        String token = json.get("token");

        String nickname = TOKENS.get(token);
        if (channel == null || nickname == null) {
            sendJson(response, HttpServletResponse.SC_BAD_REQUEST, "{\"error\":\"invalid channel or token\"}");
            return;
        }

        Set<String> members = CHANNEL_MEMBERS.get(channel);
        if (members != null) {
            members.remove(nickname);
        }

        sendJson(response, HttpServletResponse.SC_OK, "{\"status\":\"left\",\"channel\":\"" + channel + "\"}");
    }

    private void handleSendToChannel(Map<String, String> json, HttpServletResponse response) throws IOException {
        String channel = json.get("channel");
        String token = json.get("token");
        String content = json.get("content");

        String fromNickname = TOKENS.get(token);

        if (channel == null || fromNickname == null || content == null) {
            sendJson(response, HttpServletResponse.SC_BAD_REQUEST, "{\"error\":\"invalid fields\"}");
            return;
        }

        Set<String> members = CHANNEL_MEMBERS.get(channel);
        if (members == null || members.isEmpty()) {
            sendJson(response, HttpServletResponse.SC_BAD_REQUEST, "{\"error\":\"channel empty or unknown\"}");
            return;
        }

        String finalMessage = "[Channel " + channel + "][From " + fromNickname + "] " + content;

        // Envoi sur Kafka pour chaque membre
        for (String member : members) {
            if (member.equals(fromNickname)) {
                continue; // l’émetteur ne reçoit pas
            }
            String topicName = "user." + member;
            String jsonMsg = String.format(
                "{\"message\":\"%s\",\"to\":\"%s\"}",
                finalMessage, member
            );
            // 💡 Utilise l'attribut de classe 'producer'
            producer.send(new ProducerRecord<>(topicName, System.currentTimeMillis(), jsonMsg));
        }

        sendJson(response, HttpServletResponse.SC_OK, "{\"status\":\"sent\",\"channel\":\"" + channel + "\"}");
    }

    // ----------------------------------------------------------------------
    // --- Utils (Adaptés pour Servlet) ---
    // ----------------------------------------------------------------------

    private static Map<String, String> parseJson(String json) {
        // (Logique parseJson inchangée)
        Map<String, String> map = new HashMap<>();
        json = json.trim().replaceAll("[{}\"]", "");
        if (json.isEmpty()) return map;
        for (String part : json.split(",")) {
            String[] kv = part.split(":", 2);
            if (kv.length == 2)
                map.put(kv[0].trim(), kv[1].trim());
        }
        return map;
    }

    private static void sendJson(HttpServletResponse response, int status, String text) throws IOException {
        response.setStatus(status);
        try (PrintWriter out = response.getWriter()) {
            out.print(text);
            out.flush();
        }
    }
}