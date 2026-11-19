-- +goose Up

-- Chats (README §5.19): reports outside a family go to the server's operators. Operators are a token scope, not
-- a stored role, so the server remembers who used an operator token recently; they get `report.created` and the
-- `report` push.
CREATE TABLE chat_operators (
    user_id uuid        PRIMARY KEY REFERENCES users ON DELETE CASCADE,
    seen_at timestamptz NOT NULL DEFAULT now()
);

-- Unread counts and the badge read messages after a member's read position.
CREATE INDEX messages_conversation_person_idx ON messages (conversation_id, id) WHERE kind = 'person' AND deleted_at IS NULL;

-- +goose Down
DROP INDEX messages_conversation_person_idx;
DROP TABLE chat_operators;
