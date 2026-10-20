-- user_db `user` table (D-01 AMENDED, D-02, D-03, D-05, D-07, D-58, D-60, D-63).
--
-- `user` e parola riservata in MySQL 8.4: l'identificatore va quotato con i
-- backtick in ogni riferimento.
--
-- id: il claim Cognito `sub` memorizzato verbatim, MAI parsato come UUID
-- (D-01, AMENDED — AWS documenta esplicitamente che il formato di `sub` non
-- e garantito RFC UUID). VARCHAR(64) con charset ascii: 64 da margine oltre
-- i 36 caratteri di uno UUID canonico nel caso Cognito prefissi il valore
-- (come accade con gli identity provider federati), ascii recupera gran
-- parte del costo di densita dell'indice rispetto a un BINARY(16).
--
-- username: unicita case- e accent-insensitive dichiarata esplicitamente
-- (D-58) invece di ereditare il default di MySQL 8.4 — un handle
-- case-sensitive renderebbe "Seller" e "seller" indistinguibili per un
-- acquirente che decide a chi inviare denaro. 30 e il limite superiore di
-- D-60.
--
-- registration: instant di inserimento (D-07), mai aggiornato dopo.
--
-- location/bio: nullable per ch. 12; le lunghezze sono a discrezione del
-- planner e sono rispecchiate dalle annotazioni @Size su User (D-62,
-- verificato a runtime da SchemaValidationDriftIT nel piano 01-02).
--
-- avatar: chiave oggetto S3 (D-33), non un URL — sopravvive a un cambio di
-- distribuzione/dominio in Phase 7.
--
-- Nessuna colonna `password`: Cognito possiede le credenziali (D-02).
-- Nessuna colonna `email`: vive solo in Cognito e nel token (D-03).
CREATE TABLE `user` (
    id            VARCHAR(64) CHARACTER SET ascii NOT NULL,
    username      VARCHAR(30) COLLATE utf8mb4_0900_ai_ci NOT NULL,
    registration  DATETIME(6) NOT NULL,
    location      VARCHAR(80) NULL,
    bio           VARCHAR(500) NULL,
    avatar        VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
