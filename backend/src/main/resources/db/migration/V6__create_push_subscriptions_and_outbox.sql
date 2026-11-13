CREATE TABLE push_subscription (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_profile(user_id) ON DELETE CASCADE,
    endpoint_hash CHAR(64) NOT NULL UNIQUE,
    endpoint_ciphertext TEXT NOT NULL,
    p256dh_key VARCHAR(256) NOT NULL,
    auth_secret VARCHAR(256) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    disabled_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX ix_push_subscription_user_active ON push_subscription (user_id, disabled_at);

CREATE TABLE notification_outbox (
    id UUID PRIMARY KEY,
    circle_id UUID NOT NULL REFERENCES circle(id) ON DELETE CASCADE,
    request_id UUID NOT NULL REFERENCES urgent_request(id) ON DELETE CASCADE,
    recipient_id UUID NOT NULL REFERENCES user_profile(user_id) ON DELETE CASCADE,
    subscription_id UUID NOT NULL REFERENCES push_subscription(id),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'ACCEPTED', 'SIMULATED', 'FAILED', 'CANCELLED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_at TIMESTAMP WITH TIME ZONE,
    accepted_at TIMESTAMP WITH TIME ZONE,
    last_error_code VARCHAR(80),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_notification_outbox_request_subscription UNIQUE (request_id, subscription_id)
);

CREATE INDEX ix_notification_outbox_pending ON notification_outbox (status, next_attempt_at, created_at);
CREATE INDEX ix_notification_outbox_circle_request ON notification_outbox (circle_id, request_id, created_at);
CREATE INDEX ix_notification_outbox_recipient ON notification_outbox (recipient_id, status, created_at DESC);
