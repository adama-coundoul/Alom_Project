package alom.router;

// Importations Jakarta EE (pour Tomcat 10+)
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/**
 * Servlet agissant comme un proxy/routeur pour les différents microservices.
 */
@WebServlet("/api/router/*")
public class RouterService extends HttpServlet { 
    
    // ----------------------------------------------------------------------
    // --- Point d'Entrée HTTP (doPost) ---
    // ----------------------------------------------------------------------

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String pathInfo = request.getPathInfo();
        
        // Lire le corps de la requête
        String body = new String(request.getInputStream().readAllBytes());
        response.setContentType("application/json");

        if (pathInfo == null) {
            sendJSON(response, HttpServletResponse.SC_NOT_FOUND, "{\"error\":\"Endpoint not found\"}");
            return;
        }

        boolean ok = false;
        String targetUrl = null;
        String successMessage = null;

        // 🎯 CORRECTION DES PORTS ET DES CONTEXTES WEB
        // Nous utilisons http://localhost:8080/NOM_DU_SERVICE/api/...
        
        if (pathInfo.startsWith("/chat/send")) {
            targetUrl = "http://localhost:8080/notification-service/api/notification/messages";
            successMessage = "Message forwarded";

        } else if (pathInfo.startsWith("/private/send")) {
            targetUrl = "http://localhost:8080/private-service/api/private/send";
            successMessage = "Private message forwarded";

        } else if (pathInfo.startsWith("/channel/join")) {
            targetUrl = "http://localhost:8080/channel-service/api/channel/join";
            successMessage = "joined";

        } else if (pathInfo.startsWith("/channel/leave")) {
            targetUrl = "http://localhost:8080/channel-service/api/channel/leave";
            successMessage = "left";

        } else if (pathInfo.startsWith("/channel/send")) {
            targetUrl = "http://localhost:8080/channel-service/api/channel/send";
            successMessage = "Channel message forwarded";
        

        } else {
            sendJSON(response, HttpServletResponse.SC_NOT_FOUND, "{\"error\":\"Route not defined\"}");
            return;
        }

        // Exécuter le forward
        ok = forwardPOST(targetUrl, body);

        if (ok) {
            sendJSON(response, HttpServletResponse.SC_OK, "{\"status\":\"" + successMessage + "\"}");
        } else {
            // Utiliser 502 Bad Gateway si le service cible est injoignable
            sendJSON(response, HttpServletResponse.SC_BAD_GATEWAY, "{\"error\":\"Forwarding failed to " + targetUrl + "\"}"); 
        }
    }
    
    // ----------------------------------------------------------------------
    // --- Utilitaires (Inchangs) ---
    // ----------------------------------------------------------------------

    /** Forward la requete POST vers l'URL cible et retourne TRUE si 2xx. */
    private boolean forwardPOST(String targetUrl, String body) {
        try {
            URL url = new URL(targetUrl);
            
            // ... (reste du code forwardPOST)
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

        } catch (Exception e) {
            System.err.println("Erreur de forwarding vers " + targetUrl + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /** Envoie une rponse JSON  l'utilisateur. */
    private void sendJSON(HttpServletResponse response, int status, String json) throws IOException {
        response.setStatus(status);
        try (PrintWriter out = response.getWriter()) {
            out.write(json);
            out.flush();
        }
    }
}