CREATE TABLE circle_invite (
    id UUID PRIMARY KEY,
    circle_id UUID NOT NULL REFERENCES circle(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_by UUID NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('FAMILY', 'HELPER', 'VIEWER')),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    accepted_at TIMESTAMP WITH TIME ZONE,
    accepted_by UUID,
    CONSTRAINT uq_circle_invite_idempotency UNIQUE (circle_id, created_by, idempotency_key),
    CONSTRAINT ck_circle_invite_acceptance CHECK ((accepted_at IS NULL) = (accepted_by IS NULL)),
    CONSTRAINT ck_circle_invite_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_circle_invite_circle_created ON circle_invite (circle_id, created_at DESC);
