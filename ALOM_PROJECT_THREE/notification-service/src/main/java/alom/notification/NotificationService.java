package alom.notification;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

public class NotificationService {
    private static final Logger logger = Logger.getLogger(NotificationService.class.getName());

    // token -> nickname
    static final Map<String, String> TOKENS = new ConcurrentHashMap<>();
    // nickname -> PrintWriter TCP
    static final Map<String, PrintWriter> CONNECTED_CLIENTS = new ConcurrentHashMap<>();
    // nickname -> messages en attente
    static final Map<String, List<String>> MESSAGE_QUEUE = new ConcurrentHashMap<>();

    private static final int TCP_PORT = 9090;

    private static ExecutorService threadPool = Executors.newCachedThreadPool();

    public static void start() {
        startTcpServer();
    }

    public static void stop() {
        // TODO : fermer proprement les sockets / consumers si besoin
    }

    // ========== TCP : clients + consumers Kafka par user ==========

    public static void startTcpServer() {
        Thread tcpThread = new Thread(() -> {
            try {
                ServerSocket serverSocket = new ServerSocket(TCP_PORT);
                logger.info("NotificationService TCP démarré sur port " + TCP_PORT);

                while (true) {
                    Socket socket = serverSocket.accept();
                    threadPool.submit(() -> handleClient(socket));
                }
            } catch (IOException e) {
                logger.severe("Erreur startTcpServer: " + e.getMessage());
            }
        }, "tcp-server-thread");

        tcpThread.setDaemon(true);
        tcpThread.start();
    }

    private static void handleClient(Socket socket) {
        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            out.println("Bienvenue ! Entrez votre token :");
            String token = in.readLine();

            logger.info("Token reçu du client TCP : " + token);
            if (token == null) return;

            String nickname = TOKENS.get(token.trim());
            if (nickname == null) {
                out.println("Token invalide. Déconnexion...");
                logger.warning("Token invalide reçu: " + token);
                return;
            }

            out.println("Authentifié comme " + nickname);
            logger.info("Client authentifié : " + nickname);

            CONNECTED_CLIENTS.put(nickname, out);

            // Consumer Kafka pour ce user : topic user.<nickname>
            startUserTopicConsumer(nickname, out);

            // Messages déjà en attente
            if (MESSAGE_QUEUE.containsKey(nickname)) {
                for (String msg : MESSAGE_QUEUE.get(nickname)) {
                    out.println(msg);
                    logger.info("Message en file envoyé à " + nickname + " : " + msg);
                }
                MESSAGE_QUEUE.remove(nickname);
            }

            String line;
            while ((line = in.readLine()) != null) {
                if ("quit".equalsIgnoreCase(line.trim())) {
                    out.println("Au revoir !");
                    break;
                }
            }

            CONNECTED_CLIENTS.remove(nickname);
            logger.info("Client déconnecté : " + nickname);

        } catch (IOException e) {
            logger.severe("Erreur connexion client TCP: " + e.getMessage());
        }
    }

    // Un consumer Kafka par user connecté, sur topic user.<nickname>
    private static void startUserTopicConsumer(String nickname, PrintWriter out) {
        String topicName = "user." + nickname;

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "notif-user-" + nickname);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, LongDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        KafkaConsumer<Long, String> userConsumer = new KafkaConsumer<>(props);
        userConsumer.subscribe(Collections.singletonList(topicName));

        Thread t = new Thread(() -> {
            logger.info("Kafka consumer started for user " + nickname + " on topic " + topicName);
            while (true) {
                ConsumerRecords<Long, String> records =
                        userConsumer.poll(java.time.Duration.ofMillis(1000));
                for (ConsumerRecord<Long, String> record : records) {
                    String body = record.value(); // {"message":"...","to":"nickname"}
                    logger.info("Message Kafka pour " + nickname + " : " + body);
                    Map<String, String> map = parseJson(body);
                    String finalMessage = map.get("message");
                    if (finalMessage != null) {
                        out.println(finalMessage);
                    }
                }
            }
        }, "user-topic-" + nickname);

        t.setDaemon(true);
        t.start();
    }

    // ========== Utils ==========

    static Map<String, String> parseJson(String json) {
        Map<String, String> map = new HashMap<>();
        if (json == null) return map;
        json = json.trim().replaceAll("[{}\"]", "");
        if (json.isEmpty()) return map;
        for (String part : json.split(",")) {
            String[] kv = part.split(":", 2);
            if (kv.length == 2) {
                map.put(kv[0].trim(), kv[1].trim());
            }
        }
        return map;
    }
}
