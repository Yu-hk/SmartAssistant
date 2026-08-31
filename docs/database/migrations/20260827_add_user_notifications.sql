-- Durable, user-scoped notifications for asynchronous workflow recovery.
CREATE TABLE IF NOT EXISTS public.user_notifications (
    id varchar(64) PRIMARY KEY,
    event_id varchar(64) NOT NULL UNIQUE,
    user_id bigint NOT NULL,
    type varchar(64) NOT NULL,
    title varchar(200) NOT NULL,
    content text,
    session_id varchar(100),
    request_id varchar(128),
    status varchar(20) NOT NULL DEFAULT 'UNREAD'
        CHECK (status IN ('UNREAD', 'READ')),
    created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at timestamp without time zone
);

CREATE INDEX IF NOT EXISTS idx_user_notifications_inbox
    ON public.user_notifications (user_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_notifications_request
    ON public.user_notifications (request_id);
