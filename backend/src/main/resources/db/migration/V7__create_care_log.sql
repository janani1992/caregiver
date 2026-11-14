CREATE TABLE care_log_entry (
    id UUID PRIMARY KEY,
    circle_id UUID NOT NULL REFERENCES circle(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES user_profile(user_id),
    body_ciphertext TEXT NOT NULL,
    category VARCHAR(24) NOT NULL CHECK (category IN ('GENERAL', 'MEDICATION', 'APPOINTMENT', 'WELLBEING', 'OTHER')),
    request_hash CHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_care_log_author_key UNIQUE (author_id, idempotency_key)
);

CREATE INDEX ix_care_log_circle_created ON care_log_entry (circle_id, created_at DESC, id DESC);