-- Rebyte — uno schema per servizio (docs/architecture.md §2b).
-- Nessun servizio legge il database di un altro.
--
-- NB: l'utente è hardcoded a 'rebyte' perché gli script in
-- docker-entrypoint-initdb.d non interpolano variabili d'ambiente.
-- MYSQL_USER in .env deve corrispondere.
--
-- I grant sono espliciti perché MYSQL_DATABASE non è impostata nel compose:
-- senza di essi l'utente applicativo non vedrebbe nessuno dei tre schemi.
--
-- Questo script gira SOLO al primo avvio, su volume vuoto.
-- Dopo una modifica: docker compose -f infra/docker-compose.yml down -v

CREATE DATABASE IF NOT EXISTS user_db    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS order_db   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS payment_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

GRANT ALL PRIVILEGES ON user_db.*    TO 'rebyte'@'%';
GRANT ALL PRIVILEGES ON order_db.*   TO 'rebyte'@'%';
GRANT ALL PRIVILEGES ON payment_db.* TO 'rebyte'@'%';

FLUSH PRIVILEGES;

-- Nessuna tabella: le entità le genera Hibernate (ddl-auto: update in sviluppo)
-- quando i servizi esisteranno.
