package alom.channel;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Path("/")  // combiné avec /api/channel/* du web.xml
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ChannelService {

    private static final Map<String, Set<String>> CHANNEL_MEMBERS = new ConcurrentHashMap<>();
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();
    private static KafkaProducer<Long, String> producer;

    static {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "channel-service");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, LongSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        producer = new KafkaProducer<>(props);
        System.out.println("ChannelService Jersey initialisé (Kafka prêt)");
    }

    @POST
    @Path("/sync/token")
    public Response syncToken(Map<String,String> map) {
        String token = map.get("token");
        String nickname = map.get("nickname");

        if (token != null && nickname != null) {
            TOKENS.put(token, nickname);
            return Response.ok().build();  // vide, comme avant
        }
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error","missing token or nickname"))
                .build();
    }

    @POST
    @Path("/join")
    public Response join(Map<String,String> json) {
        String channel = json.get("channel");
        String token = json.get("token");

        String nickname = TOKENS.get(token);
        if (channel == null || nickname == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error","invalid channel or token"))
                    .build();
        }

        CHANNEL_MEMBERS
                .computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet())
                .add(nickname);

        return Response.ok(Map.of("status","joined","channel",channel)).build();
    }

    @POST
    @Path("/leave")
    public Response leave(Map<String,String> json) {
        String channel = json.get("channel");
        String token = json.get("token");

        String nickname = TOKENS.get(token);
        if (channel == null || nickname == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error","invalid channel or token"))
                    .build();
        }

        Set<String> members = CHANNEL_MEMBERS.get(channel);
        if (members != null) {
            members.remove(nickname);
        }

        return Response.ok(Map.of("status","left","channel",channel)).build();
    }

    @POST
    @Path("/send")
    public Response send(Map<String,String> json) {
        String channel = json.get("channel");
        String token = json.get("token");
        String content = json.get("content");

        String fromNickname = TOKENS.get(token);

        if (channel == null || fromNickname == null || content == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error","invalid fields"))
                    .build();
        }

        Set<String> members = CHANNEL_MEMBERS.get(channel);
        if (members == null || members.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error","channel empty or unknown"))
                    .build();
        }

        String finalMessage = "[Channel " + channel + "][From " + fromNickname + "] " + content;

        for (String member : members) {
            if (member.equals(fromNickname)) continue;
            String topicName = "user." + member;
            String jsonMsg = String.format(
                    "{\"message\":\"%s\",\"to\":\"%s\"}",
                    finalMessage, member
            );
            producer.send(new ProducerRecord<>(topicName, System.currentTimeMillis(), jsonMsg));
        }

        return Response.ok(Map.of("status","sent","channel",channel)).build();
    }
}
