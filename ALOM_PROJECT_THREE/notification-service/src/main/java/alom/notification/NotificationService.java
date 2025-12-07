package alom.notification;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

public class NotificationService {
    private static final Logger logger = Logger.getLogger(NotificationService.class.getName());

    static final Map<String, String> TOKENS = new ConcurrentHashMap<>();
    static final Map<String, PrintWriter> CONNECTED_CLIENTS = new ConcurrentHashMap<>();
    static final Map<String, List<String>> MESSAGE_QUEUE = new ConcurrentHashMap<>();

    private static final int TCP_PORT = 9090;

    private static ExecutorService threadPool = Executors.newCachedThreadPool();

    // nouvelles références :
    private static volatile boolean running = true;
    private static ServerSocket serverSocket;
    private static final List<KafkaConsumer<Long, String>> CONSUMERS = new CopyOnWriteArrayList<>();

    public static void start() {
        running = true;
        startTcpServer();
    }

    public static void stop() {
        running = false;
        // fermer le serveur TCP pour casser accept()
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            logger.warning("Erreur à la fermeture du ServerSocket: " + e.getMessage());
        }

        // fermer les consumers Kafka
        for (KafkaConsumer<Long, String> c : CONSUMERS) {
            try {
                c.wakeup(); // ou c.close();
            } catch (Exception ignored) {}
        }

        // arrêter le pool de threads
        threadPool.shutdownNow();
        logger.info("NotificationService arrêté proprement.");
    }

    // ========== TCP : serveur + clients ==========
    public static void startTcpServer() {
        Thread tcpThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(TCP_PORT);
                logger.info("NotificationService TCP démarré sur port " + TCP_PORT);

                while (running) {
                    try {
                        Socket socket = serverSocket.accept();
                        threadPool.submit(() -> handleClient(socket));
                    } catch (SocketException se) {
                        // arrive quand on ferme serverSocket dans stop()
                        if (!running) {
                            logger.info("ServerSocket fermé, arrêt de la boucle accept().");
                        } else {
                            logger.severe("Erreur accept(): " + se.getMessage());
                        }
                    }
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

            startUserTopicConsumer(nickname, out);

            if (MESSAGE_QUEUE.containsKey(nickname)) {
                for (String msg : MESSAGE_QUEUE.get(nickname)) {
                    out.println(msg);
                    logger.info("Message en file envoyé à " + nickname + " : " + msg);
                }
                MESSAGE_QUEUE.remove(nickname);
            }

            String line;
            while (running && (line = in.readLine()) != null) {
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

    // ========== Kafka consumer par user ==========
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
        CONSUMERS.add(userConsumer);

        Thread t = new Thread(() -> {
            logger.info("Kafka consumer started for user " + nickname + " on topic " + topicName);
            try {
                while (running) {
                    ConsumerRecords<Long, String> records =
                            userConsumer.poll(java.time.Duration.ofMillis(1000));
                    for (ConsumerRecord<Long, String> record : records) {
                        String body = record.value();
                        logger.info("Message Kafka pour " + nickname + " : " + body);
                        Map<String, String> map = parseJson(body);
                        String finalMessage = map.get("message");
                        if (finalMessage != null) {
                            out.println(finalMessage);
                        }
                    }
                }
            } catch (WakeupException we) {
                // normal lors de stop()
                logger.info("Consumer " + nickname + " réveillé pour arrêt.");
            } finally {
                userConsumer.close();
                logger.info("Consumer fermé pour " + nickname);
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
