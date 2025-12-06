package alom.notification;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

@WebListener
public class NotificationListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        try {
            NotificationService.start(); // démarre TCP + Kafka consumers
            System.out.println("NotificationService démarré via Tomcat");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        NotificationService.stop();
        System.out.println("NotificationService arrêté via Tomcat");
    }
}
