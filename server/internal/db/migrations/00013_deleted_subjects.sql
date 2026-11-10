-- +goose Up

-- Sign-in subjects of deleted accounts, hashed, for as long as their access tokens can still be valid: a token issued
-- before the deletion must not create a new, empty account. A sign-in after the deletion still can.
CREATE TABLE deleted_subjects (
    sub_hash   bytea PRIMARY KEY,
    deleted_at timestamptz NOT NULL DEFAULT now()
);

-- +goose Down
DROP TABLE deleted_subjects;
