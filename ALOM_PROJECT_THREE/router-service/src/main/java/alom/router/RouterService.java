package alom.router;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

@Path("/") // combiné avec /api/router/* du web.xml
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RouterService {

    @POST
    @Path("/chat/send")
    public Response chatSend(String body) {
        String targetUrl = "http://localhost:8080/notification-service/api/notification/messages";
        boolean ok = forwardPOST(targetUrl, body);
        if (ok) {
            return Response.ok("{\"status\":\"Message forwarded\"}").build();
        } else {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("{\"error\":\"Forwarding failed to " + targetUrl + "\"}")
                    .build();
        }
    }

    @POST
    @Path("/send")
    public Response privateSend(String body) {
        String targetUrl = "http://localhost:8080/private-service/api/private/send";
        boolean ok = forwardPOST(targetUrl, body);
        if (ok) {
            return Response.ok("{\"status\":\"Private message forwarded\"}").build();
        } else {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("{\"error\":\"Forwarding failed to " + targetUrl + "\"}")
                    .build();
        }
    }

    @POST
    @Path("/channel/join")
    public Response channelJoin(String body) {
        String targetUrl = "http://localhost:8080/channel-service/api/channel/join";
        boolean ok = forwardPOST(targetUrl, body);
        if (ok) {
            return Response.ok("{\"status\":\"joined\"}").build();
        } else {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("{\"error\":\"Forwarding failed to " + targetUrl + "\"}")
                    .build();
        }
    }

    @POST
    @Path("/channel/leave")
    public Response channelLeave(String body) {
        String targetUrl = "http://localhost:8080/channel-service/api/channel/leave";
        boolean ok = forwardPOST(targetUrl, body);
        if (ok) {
            return Response.ok("{\"status\":\"left\"}").build();
        } else {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("{\"error\":\"Forwarding failed to " + targetUrl + "\"}")
                    .build();
        }
    }

    @POST
    @Path("/channel/send")
    public Response channelSend(String body) {
        String targetUrl = "http://localhost:8080/channel-service/api/channel/send";
        boolean ok = forwardPOST(targetUrl, body);
        if (ok) {
            return Response.ok("{\"status\":\"Channel message forwarded\"}").build();
        } else {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("{\"error\":\"Forwarding failed to " + targetUrl + "\"}")
                    .build();
        }
    }

    // --- utilitaire HTTP identique à avant ---

    private boolean forwardPOST(String targetUrl, String body) {
        try {
            URL url = new URL(targetUrl);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = con.getOutputStream()) {
                os.write(body.getBytes());
            }

            int code = con.getResponseCode();
            con.disconnect();
            return code >= 200 && code < 300;

        } catch (IOException e) {
            System.err.println("Erreur de forwarding vers " + targetUrl + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
