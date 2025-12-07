package alom.privatemsg;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Path("/") // avec le mapping /api/private/* -> /api/private/sync/token, /api/private/send
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PrivateService {

    // token -> nickname
    private static final Map<String, String> USERS_TOKENS = new ConcurrentHashMap<>();
    private static KafkaProducer<Long, String> producer;

    static {
        initKafkaProducer();
        System.out.println("PrivateService Jersey initialisé (Kafka prêt)");
    }

    // ========= /api/private/sync/token =========

    @GET
    @Path("/ping")
    public String ping() {
        System.out.println("PING PRIVATE appelé");
        return "OK PRIVATE";
    }
    
    @POST
    @Path("/sync/token")
    public Response syncToken(Map<String, String> body) {
        String token = body.get("token");
        String nickname = body.get("nickname");

        if (token != null && nickname != null) {
            USERS_TOKENS.put(token, nickname);
        }

        return Response.ok(Map.of("status", "token synced")).build();
    }

    // ========= /api/private/send =========

    @POST
    @Path("/send")
    public Response sendPrivate(Map<String, String> json) {
        String token = json.get("token");
        String content = json.get("content");
        String toNickname = json.get("to");

        if (token == null || content == null || toNickname == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "missing fields"))
                    .build();
        }

        String fromNickname = USERS_TOKENS.get(token);
        if (fromNickname == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "invalid token"))
                    .build();
        }

        String finalMessage = "[Msg de " + fromNickname + "] " + content;

        boolean sent = forwardToNotificationService(finalMessage, toNickname);

        if (!sent) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "delivery failed"))
                    .build();
        }

        return Response.ok(Map.of("status", "sent")).build();
    }

    // ========= Kafka vers NotificationService =========

    private static boolean forwardToNotificationService(String message, String toNickname) {
        try {
            String json = String.format(
                    "{\"message\":\"%s\",\"to\":\"%s\"}",
                    message, toNickname
            );

            String topicName = "user." + toNickname;   // ex: user.alice

            ProducerRecord<Long, String> record =
                    new ProducerRecord<>(topicName, System.currentTimeMillis(), json);

            producer.send(record).get();  // envoie et attend l'ACK
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static void initKafkaProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "private-service");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, LongSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        producer = new KafkaProducer<>(props);
    }
}
