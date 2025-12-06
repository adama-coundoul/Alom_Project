package alom.notification;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.logging.Logger;

@WebServlet("/api/notification/*")
public class NotificationHttpServlet extends HttpServlet {

    private static final Logger logger = Logger.getLogger(NotificationHttpServlet.class.getName());

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        String path = req.getPathInfo(); // /tokens ou /messages
        String body = new String(req.getInputStream().readAllBytes());
        resp.setContentType("application/json");

        if ("/tokens".equals(path)) {
            handleTokens(body, resp);
        } else if ("/messages".equals(path)) {
            handleMessages(body, resp);
        } else {
            sendJSON(resp, 404, "{\"error\":\"unknown path\"}");
        }
    }

    private void handleTokens(String body, HttpServletResponse resp) throws IOException {
        logger.info("Reçu token : " + body);

        Map<String, String> map = NotificationService.parseJson(body);
        String token = map.get("token");
        String nickname = map.get("nickname");

        if (token != null && nickname != null) {
            NotificationService.TOKENS.put(token, nickname);
            logger.info("Token sauvegardé pour : " + nickname);
        }

        sendJSON(resp, 200, "{\"status\":\"token saved\"}");
    }

    private void handleMessages(String body, HttpServletResponse resp) throws IOException {
        logger.info("Reçu message prêt : " + body);

        Map<String, String> map = NotificationService.parseJson(body);
        String toNickname = map.get("to");
        String finalMessage = map.get("message");

        if (toNickname == null || finalMessage == null) {
            sendJSON(resp, 400, "{\"error\":\"missing fields\"}");
            return;
        }

        if ("all".equalsIgnoreCase(toNickname)) {
            logger.info("Broadcast public : " + finalMessage);
            for (PrintWriter client : NotificationService.CONNECTED_CLIENTS.values()) {
                client.println(finalMessage);
            }
            sendJSON(resp, 200, "{\"status\":\"broadcasted\"}");
            return;
        }

        NotificationService.MESSAGE_QUEUE
                .computeIfAbsent(toNickname, k -> new ArrayList<>())
                .add(finalMessage);

        logger.info("Message privé stocké pour " + toNickname + " : " + finalMessage);

        PrintWriter out = NotificationService.CONNECTED_CLIENTS.get(toNickname);
        if (out != null) {
            out.println(finalMessage);
            NotificationService.MESSAGE_QUEUE.get(toNickname).remove(finalMessage);
            logger.info("Message privé envoyé immédiatement à " + toNickname);
        }

        sendJSON(resp, 200, "{\"status\":\"queued\"}");
    }

    private void sendJSON(HttpServletResponse resp, int status, String json) throws IOException {
        resp.setStatus(status);
        try (PrintWriter out = resp.getWriter()) {
            out.write(json);
            out.flush();
        }
    }
}
