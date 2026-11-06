CREATE TABLE circle (
    id UUID PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    created_by UUID NOT NULL,
    change_seq BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE membership (
    circle_id UUID NOT NULL REFERENCES circle(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('OWNER', 'FAMILY', 'HELPER', 'VIEWER')),
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (circle_id, user_id)
);

CREATE TABLE idempotency_record (
    user_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    circle_id UUID NOT NULL REFERENCES circle(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, idempotency_key)
);
