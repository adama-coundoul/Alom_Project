package alom.user;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Path("/") // combiné avec /api/users/* du web.xml
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserService {

    private static final Map<String, String> users = new ConcurrentHashMap<>();
    private static final Map<String, String> tokens = new ConcurrentHashMap<>();

    @POST
    @Path("/register")
    public Response register(Map<String, String> data) {
        String nickname = data.get("nickname");
        String password = data.get("password");

        if (nickname == null || password == null || users.containsKey(nickname)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid input or user exists"))
                    .build();
        }

        users.put(nickname, password);
        return Response.ok(Map.of("message", "User registered")).build();
    }

    @POST
    @Path("/login")
    public Response login(Map<String, String> data) {
        String nickname = data.get("nickname");
        String password = data.get("password");

        if (nickname == null || password == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Missing fields"))
                    .build();
        }

        String stored = users.get(nickname);
        if (stored == null || !stored.equals(password)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Invalid credentials"))
                    .build();
        }

        String token = UUID.randomUUID().toString();
        tokens.put(token, nickname);

        // synchro vers les autres microservices
        sendTokenToNotificationService(token, nickname);
        sendTokenToPrivateService(token, nickname);
        sendTokenToChannelService(token, nickname);

        return Response.ok(Map.of("token", token, "nickname", nickname)).build();
    }

    // --- appels REST sortants vers les autres microservices ---

    private static void sendTokenToNotificationService(String token, String nickname) {
        sendPOST(
                "http://localhost:8080/notification-service/api/notification/tokens",
                "{\"token\":\"" + token + "\",\"nickname\":\"" + nickname + "\"}"
        );
    }

    private static void sendTokenToPrivateService(String token, String nickname) {
        sendPOST(
                "http://localhost:8080/private-service/api/private/sync/token",
                "{\"token\":\"" + token + "\",\"nickname\":\"" + nickname + "\"}"
        );
    }

    private static void sendTokenToChannelService(String token, String nickname) {
        sendPOST(
                "http://localhost:8080/channel-service/api/channel/sync/token",
                "{\"token\":\"" + token + "\",\"nickname\":\"" + nickname + "\"}"
        );
    }

    private static void sendPOST(String urlStr, String json) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = con.getOutputStream()) {
                os.write(json.getBytes());
            }
            con.getResponseCode();
            con.disconnect();
        } catch (IOException e) {
            System.err.println("Erreur d'appel REST vers : " + urlStr);
            e.printStackTrace();
        }
    }
}
