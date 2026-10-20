# Rebyte

Marketplace di hardware e software di seconda mano: annunci, ricerca, messaggistica,
acquisto e consegna. L'hardware si spedisce, il software si scarica subito tramite licenza.
Backend a microservizi Spring Boot, frontend Angular.

## Stack

| | |
|---|---|
| Linguaggio | Java 26 |
| Framework | Spring Boot 4.1.1, Spring Cloud 2025.1.3 |
| Build | Maven multi-modulo (wrapper incluso) |
| Discovery / Gateway | Eureka, Spring Cloud Gateway |
| Persistenza | MySQL 8.4 (user, order, payment) · MongoDB 7 (catalog, messaging) |
| Altro | Redis, RabbitMQ, Meilisearch |

Perché la persistenza è poliglotta, perché MongoDB deve girare come replica set e come
funziona la saga di checkout: `docs/architecture.md`.

## Prerequisiti

- Docker con Compose
- Un JDK 26

Maven **non** serve: `./mvnw` scarica da solo la distribuzione che gli occorre.

## Avvio rapido

```bash
# 1. configurazione locale: sostituire le password e inserire le chiavi richieste
cp infra/.env.example infra/.env

# 2. datastore (MySQL, MongoDB replica set, Redis, RabbitMQ, Meilisearch)
docker compose -f infra/docker-compose.yml up -d --build

# 3. tutti i servizi Spring in container
docker compose -f infra/docker-compose.yml -f infra/docker-compose.services.yml up -d --build --wait
```

In alternativa un singolo servizio si avvia dal sorgente: `./mvnw -pl <modulo> spring-boot:run`.

`infra/docker-compose.services.yml` rifiuta di partire se mancano le variabili obbligatorie
(bucket media, chiavi IAM dedicate di messaging-service, segreto di disiscrizione).
La consegna eventi SES resta spenta finche `SES_EVENTS_ENABLED` non e `true`.

Build completa e test: `./mvnw clean install` (gli integration test usano Testcontainers,
quindi serve Docker).

## Porte

| Servizio | Porta | | Datastore | Porta |
|---|---|---|---|---|
| discovery-server | 8761 | | MySQL | 3306 |
| api-gateway | 8080 | | MongoDB | 27017 |
| user-service | 8081 | | Redis | 6379 |
| catalog-service | 8082 | | RabbitMQ | 5672 / 15672 |
| messaging-service | 8083 | | Meilisearch | 7700 |
| order-service | 8084 | | | |
| payment-service | 8085 | | | |

> **27017 non è rimappabile.** Il membro del replica set è dichiarato come
> `localhost:27017` e mongod si riconosce come membro solo se quella porta coincide con
> quella su cui ascolta. Se un altro progetto la occupa, va fermato quello. Le altre porte
> si cambiano da `infra/.env`.

## Stato dei moduli

| Modulo | Stato |
|---|---|
| `discovery-server` | ✅ registry Eureka standalone |
| `api-gateway` | ✅ routing, rate limiting su Redis, resource server OAuth2 (`cognito` / `local-auth`), WebSocket `/ws` |
| `it.rebyte.commons` | ✅ errori RFC 9457, sicurezza, media, eventi e pattern di licenza condivisi |
| `user-service` | ✅ identita Cognito, profili, avatar, follow, ruoli admin e developer |
| `catalog-service` | ✅ annunci con attributi per categoria, ricerca Meilisearch, preferiti, software e versioni |
| `order-service` | ✅ checkout, offerte, spedizione, licenze, problemi e recensioni |
| `payment-service` | ✅ PayPal Orders v2 sandbox con webhook, rimborsi |
| `messaging-service` | 🟡 conversazioni via REST e STOMP, notifiche, email SES; manca l'accettazione live (fase 5) |
| `web` (Angular) | ⬜ da fare (fase 6) |

L'ordine di costruzione è quello di `docs/architecture.md` §12. Il parent `pom.xml` elenca
un modulo per volta: una `<module>` che punta a una directory senza `pom.xml` fa fallire
Maven alla lettura del reactor.

## Convenzioni

- Package base `it.rebyte.<service>`.
- Nomi di dominio in inglese, come nel modello ristrutturato: `License`, non `Entitlement`.
- Layering `controller → service → repository`. DTO in ingresso e in uscita, entità mai
  esposte.
- Semplicità sopra astrazione: niente factory o strategy se non esiste una seconda
  implementazione reale.
- **Nessun servizio legge il database di un altro.** Le letture sincrone fra servizi
  passano da OpenFeign, il resto da eventi RabbitMQ.
- Gli endpoint `/internal/**` sono chiamate fra servizi e non sono esposti dal gateway.

## Documentazione

- `docs/rebyte_progettazione.md` — modello concettuale ristrutturato. **Vincolante**: in
  caso di conflitto con l'architettura, vince questo.
- `docs/architecture.md` — architettura a microservizi, saga di checkout, deployment.
- `docs/testing-conventions.md` — convenzioni per unit e integration test.
- `docs/phase-03-sandbox-runbook.md`, `docs/phase-05-live-runbook.md` — passi manuali per
  PayPal sandbox, tunnel webhook, AWS SES/SQS.
- Le collection Postman e gli strumenti operativi opzionali sono mantenuti nel
  repository di sviluppo privato.
- La configurazione operativa AWS e Atlas resta nel repository privato.
- `docs/*.vpp` — progetti Visual Paradigm dei diagrammi UML.
