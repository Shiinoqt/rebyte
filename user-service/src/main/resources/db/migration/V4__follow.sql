-- V4: the follow graph (D-41; SOCL-01, SOCL-02, SOCL-05).
--
-- follower_sub / followed_sub: the same VARCHAR(64) ascii shape as every
-- other identifier in this schema (D-01 AMENDED) — both are Cognito `sub`
-- values, one for the follower, one for the followed. The pair IS the
-- primary key (composite, via @EmbeddedId on the Java side): ch. 10.6 gives
-- `Follow` an external composite identifier (`follower` + `followed`, both
-- {id1}), and a composite key here makes that identifier visible in the DDL
-- rather than hiding it behind a surrogate ch. 10.6 also licenses but does
-- not require.
--
-- The composite key absorbs the UNIQUENESS half of [V.Follow.noSelfFollow]
-- for free: two rows with the same (follower_sub, followed_sub) pair cannot
-- coexist, because that pair IS the primary key. Only the NON-REFLEXIVITY
-- half needs a separate mechanism, which is the CHECK constraint below.
--
-- chk_follow_no_self: enforces follower_sub <> followed_sub at the schema
-- level — the backstop for any write that bypasses FollowService's own
-- service-layer check (T-05-04's disposition, threat model). This is the
-- forcing function behind D-63's choice of Flyway over `ddl-auto`: Hibernate
-- schema generation has no annotation that produces a table-level CHECK
-- constraint, so this row could not exist without a versioned SQL file.
--
-- idx_follow_followed_sub: the followers query (SOCL-05, D-40) filters on
-- followed_sub and orders/ranges on follower_sub. A single-column index on
-- followed_sub is enough for both: InnoDB secondary indexes always append
-- the table's primary-key columns to each index entry for the row lookup,
-- and since follower_sub is part of the primary key, this index is
-- physically (followed_sub, follower_sub) on disk — exactly what the
-- keyset query `WHERE followed_sub = ? AND follower_sub > ? ORDER BY
-- follower_sub` needs. The reverse ("following") direction is already
-- index-free through the primary key's own leading column (follower_sub),
-- with no index added for it here (D-43 — no requirement asks for it yet).
CREATE TABLE follow (
    follower_sub VARCHAR(64) CHARACTER SET ascii NOT NULL,
    followed_sub VARCHAR(64) CHARACTER SET ascii NOT NULL,
    instant      DATETIME(6) NOT NULL,
    PRIMARY KEY (follower_sub, followed_sub),
    CONSTRAINT chk_follow_no_self CHECK (follower_sub <> followed_sub),
    INDEX idx_follow_followed_sub (followed_sub)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
