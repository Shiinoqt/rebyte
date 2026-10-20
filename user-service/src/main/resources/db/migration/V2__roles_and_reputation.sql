-- V2: roles and reputation (D-08, D-09, D-10, D-11, D-47, D-48; IDENT-04,
-- USER-04, USER-05). Written once with all three tables even though `admin`
-- and `developer_profile` are plan 01-03 Task 3's entities/services — see
-- that task's action text: "the admin and developer_profile tables are Task
-- 3's, in this same migration file, so write the file once with all three."
--
-- admin: self-healing row created just-in-time on the first authenticated
-- request carrying ROLE_ADMIN (D-10) — the identical mechanism as `user`.
-- Keyed by the same `sub` as the user row (D-01, D-08), same VARCHAR(64)
-- ascii shape and for the same reason: Cognito does not guarantee `sub` is
-- RFC-UUID shaped. Declares no password column: Cognito ADMIN group
-- membership grants access, this row is NEVER an authorization source and
-- is NEVER deleted, even on demotion (D-09), because Moderation.admin is a
-- mandatory [1..1] reference the binding model requires.
--
-- Deliberately NO foreign key to `user`: the admin row can be created by a
-- request whose authenticated user has not necessarily had its own `user`
-- row provisioned by that same request yet (JIT provisioning of the two
-- rows is not ordered relative to each other), so a FK here would be a
-- race, not a real constraint the model requires — ch. 9.1 line 798 ("Admin
-- non specializza nulla") already says Admin is not a specialization of
-- User; only the identifier VALUE is shared by convention (D-08).
CREATE TABLE admin (
    id VARCHAR(64) CHARACTER SET ascii NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- developer_profile: its own table, `user_sub` as both primary and foreign
-- key (D-11) — the literal relational reading of ch. 10.6's external
-- identifier for this class. A nullable column on `user` would erase a
-- class from the binding model. `activated_at` is set once, at first
-- activation (USER-05); activation is idempotent, so this column is never
-- updated after insert. Unlike `admin`, the FK is safe here because
-- activation always runs after the owner's own `user` row is guaranteed to
-- exist in the same request (see UserController#activateDeveloperProfile).
CREATE TABLE developer_profile (
    user_sub     VARCHAR(64) CHARACTER SET ascii NOT NULL,
    activated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_sub),
    CONSTRAINT fk_developer_profile_user FOREIGN KEY (user_sub) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- seller_review: local projection of review.created events (D-47, D-48;
-- USER-04), populated by plan 01-04's listener — this plan only creates and
-- reads it. Keyed by the review's own id, so at-least-once delivery is a
-- no-op by construction with no separate dedup table. ratingAvg/ratingCount
-- are NEVER stored columns anywhere in this schema: they are AVG/COUNT
-- queries over this table indexed by seller_sub, evaluated per request
-- (D-48) — the recompute-per-request semantics ch. 10.1 requires; only the
-- data's location changed, forced by service isolation.
CREATE TABLE seller_review (
    review_id  VARCHAR(64) CHARACTER SET ascii NOT NULL,
    seller_sub VARCHAR(64) CHARACTER SET ascii NOT NULL,
    rating     TINYINT NOT NULL,
    instant    DATETIME(6) NOT NULL,
    PRIMARY KEY (review_id),
    INDEX idx_seller_review_seller_sub (seller_sub)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
