-- V6: per-pair follow transition counter (D-17, D-19).
--
-- One row per (follower, followed) pair, retained when the pair is
-- unfollowed: the follow row disappears on unfollow but the counter must
-- keep growing so a later re-follow is still ordered after the unfollow.
-- That is why there is no foreign key to follow. The row doubles as the
-- lock that serializes concurrent transitions of one pair.
CREATE TABLE follow_transition_sequence (
    follower_sub        VARCHAR(64) CHARACTER SET ascii NOT NULL,
    followed_sub        VARCHAR(64) CHARACTER SET ascii NOT NULL,
    transition_sequence BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (follower_sub, followed_sub)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
