# Projet ALOM — Serveur de Chat Distribué

> **Auteurs :** Maya - Adama - Maggy

Ce projet met en œuvre un serveur de chat modulaire reposant sur une architecture **microservices**. Il combine des communications synchrones (REST) pour la gestion et asynchrones (Kafka) pour la diffusion des messages, avec une restitution en temps réel via Sockets TCP.

![Architecture Diagram](Diagramme_sans_nom.drawio.png)
*(Architecture globale du système)*

---

## 1. Objectif et Architecture Globale

L’objectif est de découpler la réception des messages (REST) de leur diffusion (TCP) pour garantir la scalabilité et la résilience. L’architecture repose sur trois piliers technologiques :

1.  **WebServices REST (Interface "Aller") :** Pour les interactions explicites du client (Login, Envoi de message, Rejoindre un groupe).
2.  **Apache Kafka (Middleware) :** Pour le transport asynchrone, le découplage des services et le buffering des messages.
3.  **Sockets TCP (Interface "Retour") :** Pour le push de données en temps réel vers les clients connectés.

---

## 2. Architecture Logicielle (Microservices)

Le système est décomposé en 5 microservices autonomes respectant le principe de responsabilité unique (SRP).

* **RouterService (Gateway) :** Point d'entrée unique HTTP. Il ne contient aucune logique métier mais route les requêtes vers les services appropriés. **Stateless (Pas de Base de données).**
* **UserService (Auth) :** Gère l'inscription et le login. Il est responsable de la **synchronisation du token** vers tous les autres services lors de la connexion.
* **PrivateService (Métier 1-to-1) :** Gère les messages privés. Il valide le token et publie directement dans le topic Kafka du destinataire (`user.{destinataire}`).
* **ChannelService (Métier Groupe) :** Gère les salons. Il effectue un **"Fan-out"** : pour un message entrant, il génère N messages Kafka (un par membre du groupe).
* **NotificationService (Push) :** Gère les connexions TCP. Il associe dynamiquement un consommateur Kafka à chaque client connecté pour lui délivrer ses messages.

---

## 3. Architecture de Code & Persistance

Chaque microservice est un projet Java distinct. Pour garantir l'autonomie des services (Pattern *Database per Service*), chaque projet gère sa propre persistance via un **Singleton Java en mémoire**.

| Projet (Microservice) | Package & Database | Description Technique |
| :--- | :--- | :--- |
| **`router-service`** | **`alom.router`**<br>*(Aucune DB)* | **API Gateway**. Utilise `HttpURLConnection` pour relayer les payloads JSON. Masque la topologie interne au client. |
| **`user-service`** | **`alom.user`**<br>`UserDatabase` | **Auth**. Le Singleton `UserDatabase` stocke les `users` (Login/Pwd) et les `tokens` générés. Lors du login, il orchestre la synchronisation asynchrone vers les autres services. |
| **`private-service`** | **`alom.privatemsg`**<br>`PrivateServiceDatabase` | **Logique Privée**. Sa base locale stocke une copie des tokens valides pour authentifier les requêtes sans appeler le UserService. Contient le `KafkaProducer`. |
| **`channel-service`** | **`alom.channel`**<br>`ChannelServiceDatabase` | **Logique Groupe**. Sa base stocke la Map `CHANNEL_MEMBERS` (Quel user est dans quel channel) et les tokens. Lors d'un envoi, il itère sur les membres pour produire les messages Kafka. |
| **`notification-service`** | **`alom.notification`**<br>`NotificationDatabase` | **Moteur Temps Réel**. Sa base est critique : elle stocke les `tokens`, les sockets des `connectedClients`, et une `messageQueue` pour les messages en attente (utilisateurs hors-ligne). |

---

## 4. Scénarios de Fonctionnement

Ces scénarios décrivent le cycle de vie de la donnée à travers le système.

### 4.1 Inscription, Login et Synchronisation
1.  Le client appelle `POST /register`. `UserService` enregistre l’utilisateur dans `UserDatabase`.
2.  Le client appelle `POST /login`. `UserService` génère un token UUID.
3.  **Synchronisation :** Le token est envoyé aux endpoints `/sync/token` des autres services pour mettre à jour leurs bases locales (`PrivateServiceDatabase`, `ChannelServiceDatabase`, `NotificationDatabase`).

### 4.2 Connexion TCP et Réception
1.  Le client lance `nc localhost 9090`.
2.  `NotificationService` accepte la connexion.
3.  Le client envoie son token, validé via `NotificationDatabase`.
4.  Le service lance un Thread avec un `KafkaConsumer` sur le topic `user.{nickname}`.
5.  **Gestion Hors-Ligne :** Si la `messageQueue` de la DB contient des messages en attente, ils sont envoyés immédiatement.

### 4.3 Envoi Message Privé (Flux Rose)
1.  Client -> `RouterService` -> `PrivateService`.
2.  `PrivateService` publie le message JSON sur Kafka dans le topic `user.<destinataire>`.
3.  Le `NotificationService` consomme le message et l'écrit sur le socket TCP via la référence stockée dans `NotificationDatabase`.

### 4.4 Envoi Message Channel (Flux Vert)
1.  Client -> `RouterService` -> `ChannelService`.
2.  `ChannelService` récupère les membres via `ChannelServiceDatabase`.
3.  Il publie N messages Kafka distincts (un par membre).
4.  `NotificationService` délivre les messages à chacun.

---

## 5. Installation et Tests

### Pré-requis
* Java 17+, Maven, Tomcat 10+.
* **Apache Kafka & Zookeeper** démarrés :
    ```bash
    bin/zookeeper-server-start.sh config/zookeeper.properties
    bin/kafka-server-start.sh config/server.properties
    ```

### Scénario de Test "Happy Path"

**1. Création des utilisateurs (Alice et Bob)**
```bash
# Alice
curl -X POST http://localhost:8080/user-service/api/users/register -d '{"nickname":"alice","password":"pwd"}' -H "Content-Type: application/json"
# Bob
curl -X POST http://localhost:8080/user-service/api/users/register -d '{"nickname":"bob","password":"pwd"}' -H "Content-Type: application/json"
````

**2. Login (Récupération des Tokens)**

```bash
# Login Alice (Copiez le token retourné -> TOKEN_ALICE)
curl -X POST http://localhost:8080/user-service/api/users/login -d '{"nickname":"alice","password":"pwd"}' -H "Content-Type: application/json"

# Login Bob (Copiez le token retourné -> TOKEN_BOB)
curl -X POST http://localhost:8080/user-service/api/users/login -d '{"nickname":"bob","password":"pwd"}' -H "Content-Type: application/json"
```

**3. Connexion Réception (Terminal Bob)**

```bash
nc localhost 9090
# Le serveur demande le token. Collez TOKEN_BOB.
# Le serveur répond "Authentifié comme bob".
```

**4. Envoi de Message (Terminal Alice ou via Curl)**

```bash
curl -X POST http://localhost:8080/router-service/api/router/send \
-H "Content-Type: application/json" \
-d '{
  "token": "TOKEN_ALICE",
  "to": "bob",
  "content": "Salut Bob, tu reçois ?"
}'
```

> **Résultat :** Le message apparaît instantanément dans le terminal `nc` de Bob.

-----

## 6\. Difficultés et Pistes d'Amélioration

### Difficultés Rencontrées

1.  **Complexité Architecturale :** La difficulté majeure a été de coordonner les trois paradigmes de communication (REST, Kafka, TCP). La réalisation du schéma d'architecture a été l'étape critique pour figer les interactions avant le code.
2.  **Infrastructure & Configuration :** Problèmes liés aux contextes Tomcat (`ContextNotFound`), aux conflits de versions (Jakarta EE vs Javax) et à la stabilité de l'environnement Kafka local.

### Pistes de Refactoring (Dette Technique)

Nous sommes conscients que le code actuel privilégie la fonctionnalité et pourrait être optimisé :

  * **Séparation Métier / Infrastructure :** Dans les services `Private` et `Channel`, le code technique Kafka est mélangé au code métier. L'utilisation d'interfaces permettrait de mieux isoler ces couches.
