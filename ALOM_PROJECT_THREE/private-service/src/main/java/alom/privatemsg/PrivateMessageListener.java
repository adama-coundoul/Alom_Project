package alom.privatemsg;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

@WebListener
public class PrivateMessageListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        System.out.println("PrivateMessageListener contextInitialized (Tomcat a démarré le microservice privé)");
        // Si tu veux un jour démarrer d'autres ressources (threads, etc.), tu le feras ici.
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        System.out.println("PrivateMessageListener contextDestroyed (Tomcat arrête le microservice privé)");
    }
}
