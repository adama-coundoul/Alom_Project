#  Projet ALOM Server de Chat

## Architecture du Projet
Ce projet implémente une architecture **microservices distribuée** permettant :

- Authentification des utilisateurs  
- Envoi de messages privés  
- Envoi de messages dans des channels (groupes)  
- Réception des messages en temps réel via **TCP + Kafka**

---

## Microservices utilisés
- **UserService** : Authentifie les utilisateurs, génère les tokens et les synchronise avec les autres services.  
- **PrivateService** : Gère les messages privés.  
- **ChannelService** : Gère les messages de channel et les interactions Kafka.  
- **RouterService** : Point d’entrée HTTP unique (reverse proxy).  
- **NotificationService** : Maintient une connexion TCP avec chaque client et transmet en temps réel les messages provenant de Kafka.

---

##  Flux Fonctionnels

### Authentification
1. Le client envoie son **nickname** et **password** sur `/login`.  
2. Le RouterService transmet la requête au UserService, qui :  
   - vérifie les identifiants,  
   - génère un token UUID,  
   - synchronise ce token avec PrivateService, ChannelService et NotificationService.  
3. Le client reçoit :  
```
{ "token": "<uuid>", "nickname": "<nickname>" }
```
---

### Message Privé
1. Le client envoie `/private/send` avec : token, destinataire, contenu.  
2. RouterService → PrivateService.  
3. PrivateService identifie l’expéditeur via le token et publie le message dans Kafka : `user.<destinataire>`.  
4. Format :  
```
[Msg de <expéditeur>] <contenu>
```

---

###  Message de Channel
1. Le client envoie `/channel/send` avec : token, channel, contenu.  
2. RouterService → ChannelService.  
3. ChannelService retrouve l’expéditeur et les membres du channel.  
4. Chaque membre reçoit un message Kafka dans `user.<membre>` :  
[Channel <nom>] [From <expéditeur>] <contenu>

---

###  Réception des messages (TCP + Kafka)
1. Le client ouvre une connexion TCP :  
```
localhost:9090
```  
2. Il envoie son token.  
3. NotificationService :  
   - valide le token,  
   - récupère le nickname,  
   - s’abonne au topic Kafka `user.<nickname>`.  
4. Chaque message reçu est envoyé instantanément via la connexion TCP. fileciteturn0file0

---

##  Installation

1. Démarrer **Zookeeper** et **Kafka**  
2. Cloner le projet  
3. Configurer **Tomcat**  
4. Déployer les microservices :  
   - router-service  
   - user-service  
   - private-service  
   - channel-service  
   - notification-service

---

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
{"nickname":"alice","password":"pwd"} 
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
http://localhost:8080/router-service/api/router/private/send
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
