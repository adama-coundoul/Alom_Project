package alom.user;

// Imports Jakarta EE
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@WebServlet("/api/users/*") 
public class UserService extends HttpServlet { 
    
    private static final Map<String, String> users = new ConcurrentHashMap<>();
    private static final Map<String, String> tokens = new ConcurrentHashMap<>();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String pathInfo = request.getPathInfo();
        String body = new String(request.getInputStream().readAllBytes());
        Map<String, String> data = parseJson(body);

        response.setContentType("application/json");

        if ("/register".equals(pathInfo)) {
            handleRegister(data, response);
        } else if ("/login".equals(pathInfo)) {
            handleLogin(data, response);
        } else {
            sendResponse(response, HttpServletResponse.SC_NOT_FOUND, "{\"error\":\"Endpoint not found\"}");
        }
    }
    
    private void handleRegister(Map<String, String> data, HttpServletResponse response) throws IOException {
        // Logique Register ... (inchangée)
        String nickname = data.get("nickname");
        String password = data.get("password");

        if (nickname == null || password == null || users.containsKey(nickname)) {
            sendResponse(response, HttpServletResponse.SC_BAD_REQUEST, "{\"error\":\"Invalid input or user exists\"}");
            return;
        }

        users.put(nickname, password);
        sendResponse(response, HttpServletResponse.SC_OK, "{\"message\":\"User registered\"}");
    }

    private void handleLogin(Map<String, String> data, HttpServletResponse response) throws IOException {
        // Logique Login ... (inchangée)
        String nickname = data.get("nickname");
        String password = data.get("password");

        if (nickname == null || password == null) {
            sendResponse(response, HttpServletResponse.SC_BAD_REQUEST, "{\"error\":\"Missing fields\"}");
            return;
        }

        String stored = users.get(nickname);
        if (stored == null || !stored.equals(password)) {
            sendResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "{\"error\":\"Invalid credentials\"}");
            return;
        }

        String token = UUID.randomUUID().toString();
        tokens.put(token, nickname);

        // Appels sortants (CORRIGÉS)
        sendTokenToNotificationService(token, nickname); 
        sendTokenToPrivateService(token, nickname); 
        sendTokenToChannelService(token, nickname); 

        String json = "{\"token\":\"" + token + "\",\"nickname\":\"" + nickname + "\"}";
        sendResponse(response, HttpServletResponse.SC_OK, json);
    }

    // --- Utilitaires ---

    private void sendResponse(HttpServletResponse response, int code, String responseBody) throws IOException {
        response.setStatus(code);
        try (PrintWriter out = response.getWriter()) {
            out.print(responseBody);
            out.flush();
        }
    }

    private static Map<String, String> parseJson(String json) {
        // Logique parseJson ... (inchangée)
        Map<String, String> map = new HashMap<>();
        if (json == null) return map;
        json = json.trim().replaceAll("[{}\"]", "");
        for (String pair : json.split(",")) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                map.put(kv[0].trim(), kv[1].trim());
            }
        }
        return map;
    }

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