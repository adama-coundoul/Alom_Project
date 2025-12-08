#  Projet ALOM — Serveur de Chat Distribué

Ce projet implémente une architecture **microservices distribuée** permettant :

- **Jersey (REST)** pour les communications client → serveur  
- **Kafka** pour transporter les messages entre microservices  
- **Socket TCP** pour envoyer les messages en temps réel aux clients  
- **Tomcat** comme serveur de déploiement  

---

# Architecture Générale

Le projet est composé de 5 microservices :

### **1. UserService**
- Création d’utilisateur  
- Login + génération de token  
- Synchronisation du token vers :  
  - NotificationService  
  - PrivateService  
  - ChannelService  

### **2. PrivateService**
- Envoi de messages privés  
- Publie les messages dans Kafka (`user.<nickname>`)  

### **3. ChannelService**
- Rejoindre / quitter un channel  
- Envoyer des messages à tous les membres du channel  
- Publie chaque message dans Kafka pour chaque membre  

### **4. NotificationService**
- Service “retour”  
- Les clients s’y connectent en TCP  
- Authentifie via token  
- Pour chaque utilisateur :  
  - écoute Kafka  
  - pousse les messages directement via la socket  

### **5. RouterService**
- Service "aller"
- Point d’entrée unique HTTP  
- Redirige vers les bons microservices

---

#  Fonctionnement Global

##  1. Inscription & Authentification

### **POST /register**
Crée un utilisateur.

### **POST /login**
- Vérifie le mot de passe  
- Génère un token UUID  
- Synchronise le token dans : NotificationService, PrivateService, ChannelService  

Réponse :
```json
{
  "token": "xxxx-xxxx-xxxx",
  "nickname": "alice"
}
```

---

##  2. Envoi d’un Message Privé

1. Client → `/private/send`  
2. PrivateService valide le token  
3. Formate le message  
4. Publie dans Kafka `user.<destinataire>`  
5. NotificationService transmet en temps réel

---

##  3. Channels

### Rejoindre
`POST /channel/join`

### Envoyer un message
`POST /channel/send`

Message transmis :
```
[Channel sport] [From alice] match ce soir ?
```

### Quitter
`POST /channel/leave`

---

# 4. Réception Temps Réel (TCP)

1. Connexion TCP sur : `localhost:9090`  
2. Le client envoie son token  
3. Le serveur retrouve le nickname  
4. Kafka Consumer dédié  
5. Messages envoyés instantanément

---

#  Installation

1. Lancer Kafka et Zookeeper  
2. Déployer les services sur Tomcat  
3. Se connecter en TCP avec `nc` ou Putty  


## Endpoints (Postman)
### Ajout d’un utilisateur  
POST  
```
http://localhost:8080/user-service/api/users/register
```

```
{"nickname":"alice","password":"pwd"} 
```
```
{"nickname":"bob","password":"pwd"} 
```

###  Login + Synchronisation des tokens  
POST  
```
http://localhost:8080/user-service/api/users/login
```

```
{"nickname":"alice","password":"pwd"} 

```
```
{"nickname":"bob","password":"pwd"} 
```

###  Envoi de message privé  
POST  
```
http://localhost:8080/router-service/api/router/send
```

```
{
  "token": "tokenAlice",
  "to": "bob",
  "content": "salut bob"
}
```

###  Envoi de message public  
POST  
```
http://localhost:8080/router-service/api/router/chat/send
```

```
{
  "to": "all",
  "message": "hello tout le monde"
}
```

###  Rejoindre un channel  
POST  
```
http://localhost:8080/router-service/api/router/channel/join
```

```
{"token":"tokenAlice","channel":"sport"}
```

###  Envoi d’un message au channel  
POST  
```
http://localhost:8080/router-service/api/router/channel/send
```

```
{"token":"tokenAlice","channel":"sport","content":"match ce soir ?"}
```

###  Quitter un channel  
POST  
```
http://localhost:8080/router-service/api/router/channel/leave
```

```
{"token":"tokenAlice","channel":"sport"}
```
