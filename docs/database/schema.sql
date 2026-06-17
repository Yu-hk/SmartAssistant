--
-- PostgreSQL database dump
--


-- Dumped from database version 18.0
-- Dumped by pg_dump version 18.0

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: hstore; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS hstore WITH SCHEMA public;


--
-- Name: EXTENSION hstore; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION hstore IS 'data type for storing sets of (key, value) pairs';


--
-- Name: pg_stat_statements; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_stat_statements WITH SCHEMA public;


--
-- Name: EXTENSION pg_stat_statements; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pg_stat_statements IS 'track planning and execution statistics of all SQL statements executed';


--
-- Name: uuid-ossp; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA public;


--
-- Name: EXTENSION "uuid-ossp"; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION "uuid-ossp" IS 'generate universally unique identifiers (UUIDs)';


--
-- Name: vector; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;


--
-- Name: EXTENSION vector; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION vector IS 'vector data type and ivfflat and hnsw access methods';


--
-- Name: auto_create_partitions(integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.auto_create_partitions(p_months_ahead integer DEFAULT 3) RETURNS text
    LANGUAGE plpgsql
    AS $$
DECLARE
    current_month DATE;
    target_month DATE;
    partition_name TEXT;
    start_date DATE;
    end_date DATE;
    created_count INTEGER := 0;
BEGIN
    current_month := DATE_TRUNC('month', CURRENT_DATE);
    
    FOR i IN 0..p_months_ahead-1 LOOP
        target_month := current_month + (i || ' months')::INTERVAL;
        partition_name := 'chat_messages_' || TO_CHAR(target_month, 'YYYY_MM');
        start_date := target_month;
        end_date := target_month + INTERVAL '1 month';
        
        -- 检查分区是否已存在
        IF NOT EXISTS (
            SELECT 1 FROM pg_class WHERE relname = partition_name
        ) THEN
            -- 创建分区
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF chat_messages_partitioned FOR VALUES FROM (%L) TO (%L)',
                partition_name, start_date, end_date
            );
            
            created_count := created_count + 1;
            RAISE NOTICE 'Created partition: % (%)', partition_name, start_date;
        ELSE
            RAISE NOTICE 'Partition already exists: %', partition_name;
        END IF;
    END LOOP;
    
    RETURN format('Created %s new partitions', created_count::TEXT);
END;
$$;


--
-- Name: FUNCTION auto_create_partitions(p_months_ahead integer); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.auto_create_partitions(p_months_ahead integer) IS '自动创建未来N个月的分区';


--
-- Name: auto_create_two_level_partitions(integer, integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.auto_create_two_level_partitions(p_months_ahead integer DEFAULT 3, p_hash_partitions integer DEFAULT 4) RETURNS text
    LANGUAGE plpgsql
    AS $$
DECLARE
    current_month DATE;
    target_month DATE;
    month_partition TEXT;
    start_date DATE;
    end_date DATE;
    created_months INTEGER := 0;
    created_subpartitions INTEGER := 0;
BEGIN
    current_month := DATE_TRUNC('month', CURRENT_DATE);
    
    FOR i IN 0..p_months_ahead-1 LOOP
        target_month := current_month + (i || ' months')::INTERVAL;
        month_partition := 'chat_messages_' || TO_CHAR(target_month, 'YYYY_MM');
        start_date := target_month;
        end_date := target_month + INTERVAL '1 month';
        
        -- 检查月度分区是否已存在
        IF NOT EXISTS (
            SELECT 1 FROM pg_class WHERE relname = month_partition
        ) THEN
            -- 创建月度分区（支持二级分区）
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF chat_messages_partitioned 
                 FOR VALUES FROM (%L) TO (%L)
                 PARTITION BY HASH (user_id)',
                month_partition, start_date, end_date
            );
            
            created_months := created_months + 1;
            RAISE NOTICE 'Created monthly partition: %', month_partition;
            
            -- 创建哈希子分区
            FOR j IN 0..p_hash_partitions-1 LOOP
                EXECUTE format(
                    'CREATE TABLE %I_p%s PARTITION OF %I 
                     FOR VALUES WITH (MODULUS %s, REMAINDER %s)',
                    month_partition, j, month_partition, p_hash_partitions, j
                );
                
                -- 为子分区创建索引
                EXECUTE format(
                    'CREATE INDEX idx_%s_p%s_thread ON %I_p%s(thread_id, created_at DESC)',
                    month_partition, j, month_partition, j
                );
                
                EXECUTE format(
                    'CREATE INDEX idx_%s_p%s_created ON %I_p%s(created_at)',
                    month_partition, j, month_partition, j
                );
                
                EXECUTE format(
                    'CREATE INDEX idx_%s_p%s_user ON %I_p%s(user_id)',
                    month_partition, j, month_partition, j
                );
                
                created_subpartitions := created_subpartitions + 1;
            END LOOP;
            
            RAISE NOTICE 'Created % subpartitions for %', p_hash_partitions, month_partition;
        ELSE
            RAISE NOTICE 'Monthly partition already exists: %', month_partition;
        END IF;
    END LOOP;
    
    RETURN format('Created %d monthly partitions, %d subpartitions', created_months, created_subpartitions);
END;
$$;


--
-- Name: FUNCTION auto_create_two_level_partitions(p_months_ahead integer, p_hash_partitions integer); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.auto_create_two_level_partitions(p_months_ahead integer, p_hash_partitions integer) IS '自动创建两级分区（按月 + 按用户ID哈希）';


--
-- Name: create_monthly_partition(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.create_monthly_partition() RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
    next_month DATE;
    partition_name TEXT;
    start_date DATE;
    end_date DATE;
BEGIN
    -- 计算下个月
    next_month := DATE_TRUNC('month', CURRENT_DATE + INTERVAL '1 month');
    partition_name := 'chat_messages_' || TO_CHAR(next_month, 'YYYY_MM');
    start_date := next_month;
    end_date := next_month + INTERVAL '1 month';
    
    -- 检查分区是否已存在
    IF NOT EXISTS (
        SELECT 1 FROM pg_class WHERE relname = partition_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF chat_messages_partitioned FOR VALUES FROM (%L) TO (%L)',
            partition_name, start_date, end_date
        );
        
        RAISE NOTICE 'Created partition: %', partition_name;
    ELSE
        RAISE NOTICE 'Partition already exists: %', partition_name;
    END IF;
END;
$$;


--
-- Name: refresh_materialized_views(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.refresh_materialized_views() RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- 刷新用户统计（并发刷新，不阻塞查询）
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_user_daily_stats;
    
    -- 刷新聊天消息统计
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_chat_message_daily_stats;
    
    -- 刷新路由调用统计
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_routing_agent_stats;
    
    -- 刷新城市景点统计
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_city_attraction_stats;
    
    RAISE NOTICE 'Materialized views refreshed successfully';
END;
$$;


--
-- Name: update_updated_at_column(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_updated_at_column() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: ab_test_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ab_test_events (
    id bigint NOT NULL,
    user_id character varying(100) NOT NULL,
    test_group character varying(50) NOT NULL,
    restaurant_id character varying(100),
    event_type character varying(20) NOT NULL,
    "position" integer,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_event_type CHECK (((event_type)::text = ANY ((ARRAY['impression'::character varying, 'click'::character varying, 'conversion'::character varying])::text[]))),
    CONSTRAINT chk_position CHECK ((("position" >= 0) AND ("position" < 20)))
);


--
-- Name: TABLE ab_test_events; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.ab_test_events IS 'A/B 测试事件表 - 记录用户与推荐结果的交互';


--
-- Name: COLUMN ab_test_events.test_group; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.ab_test_events.test_group IS '测试组: control=纯RAG, variant_a=混合60/40, variant_b=混合40/60';


--
-- Name: COLUMN ab_test_events.event_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.ab_test_events.event_type IS '事件类型: impression=曝光, click=点击, conversion=转化';


--
-- Name: COLUMN ab_test_events."position"; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.ab_test_events."position" IS '推荐位置，用于分析位置偏见';


--
-- Name: ab_test_events_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ab_test_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ab_test_events_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ab_test_events_id_seq OWNED BY public.ab_test_events.id;


--
-- Name: attraction_highlights; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.attraction_highlights (
    attraction_id bigint NOT NULL,
    highlight character varying(255)
);


--
-- Name: attraction_tags; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.attraction_tags (
    attraction_id bigint NOT NULL,
    tag character varying(255)
);


--
-- Name: conversation_feedback; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.conversation_feedback (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    session_id character varying(64),
    agent_name character varying(32),
    rating integer,
    feedback_text text,
    metadata jsonb,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT conversation_feedback_rating_check CHECK (((rating >= 1) AND (rating <= 5)))
);


--
-- Name: conversation_feedback_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.conversation_feedback_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: conversation_feedback_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.conversation_feedback_id_seq OWNED BY public.conversation_feedback.id;


--
-- Name: tourist_attractions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tourist_attractions (
    id bigint NOT NULL,
    city character varying(100) NOT NULL,
    created_at timestamp(6) without time zone,
    description text,
    latitude double precision,
    level character varying(20),
    longitude double precision,
    name character varying(200) NOT NULL,
    open_time character varying(100),
    province character varying(100) NOT NULL,
    suggest_duration integer,
    ticket_price double precision,
    updated_at timestamp(6) without time zone
);


--
-- Name: mv_city_attraction_stats; Type: MATERIALIZED VIEW; Schema: public; Owner: -
--

CREATE MATERIALIZED VIEW public.mv_city_attraction_stats AS
 SELECT city,
    province,
    count(*) AS attraction_count,
    count(*) FILTER (WHERE ((level)::text = '5A'::text)) AS level_5a_count,
    count(*) FILTER (WHERE ((level)::text = '4A'::text)) AS level_4a_count,
    round((avg(ticket_price))::numeric, 2) AS avg_price,
    min(ticket_price) AS min_price,
    max(ticket_price) AS max_price
   FROM public.tourist_attractions
  GROUP BY city, province
  ORDER BY (count(*)) DESC
  WITH NO DATA;


--
-- Name: routing_call_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.routing_call_log (
    id bigint NOT NULL,
    session_id character varying(100),
    user_input text NOT NULL,
    routed_agent character varying(100),
    route_method character varying(20) NOT NULL,
    match_score numeric(5,4),
    matched_rule_id bigint,
    llm_received_question text,
    response_summary text,
    latency_ms bigint,
    status character varying(20) DEFAULT 'SUCCESS'::character varying,
    error_message text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: TABLE routing_call_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.routing_call_log IS '路由调用日志表 - 记录每一次路由决策';


--
-- Name: COLUMN routing_call_log.session_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.routing_call_log.session_id IS '会话 ID';


--
-- Name: COLUMN routing_call_log.user_input; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.routing_call_log.user_input IS '用户原始输入';


--
-- Name: COLUMN routing_call_log.routed_agent; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.routing_call_log.routed_agent IS '路由到的 Agent 名称';


--
-- Name: COLUMN routing_call_log.route_method; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.routing_call_log.route_method IS '路由方式: keyword_match / semantic / llm_fallback';


--
-- Name: COLUMN routing_call_log.match_score; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.routing_call_log.match_score IS '语义匹配分数';


--
-- Name: COLUMN routing_call_log.matched_rule_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.routing_call_log.matched_rule_id IS '命中的规则 ID';


--
-- Name: COLUMN routing_call_log.llm_received_question; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.routing_call_log.llm_received_question IS 'LLM 实际接收到的 question（用于 debug）';


--
-- Name: COLUMN routing_call_log.response_summary; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.routing_call_log.response_summary IS '响应摘要（前500字符）';


--
-- Name: COLUMN routing_call_log.latency_ms; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.routing_call_log.latency_ms IS '耗时（毫秒）';


--
-- Name: COLUMN routing_call_log.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.routing_call_log.status IS '状态: SUCCESS / FAILED / TIMEOUT';


--
-- Name: COLUMN routing_call_log.error_message; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.routing_call_log.error_message IS '错误信息';


--
-- Name: COLUMN routing_call_log.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.routing_call_log.created_at IS '创建时间';


--
-- Name: mv_routing_agent_stats; Type: MATERIALIZED VIEW; Schema: public; Owner: -
--

CREATE MATERIALIZED VIEW public.mv_routing_agent_stats AS
 SELECT routed_agent AS agent_name,
    count(*) AS total_calls,
    count(*) FILTER (WHERE ((status)::text = 'SUCCESS'::text)) AS success_calls,
    count(*) FILTER (WHERE ((status)::text = 'FAILED'::text)) AS failed_calls,
    round(((100.0 * (count(*) FILTER (WHERE ((status)::text = 'SUCCESS'::text)))::numeric) / (NULLIF(count(*), 0))::numeric), 2) AS success_rate,
    max(created_at) AS last_call_time
   FROM public.routing_call_log
  GROUP BY routed_agent
  ORDER BY (count(*)) DESC
  WITH NO DATA;


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id bigint NOT NULL,
    username character varying(50) NOT NULL,
    password character varying(255) NOT NULL,
    email character varying(100),
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    role character varying(32) DEFAULT 'ROLE_USER'::character varying NOT NULL
);


--
-- Name: mv_user_daily_stats; Type: MATERIALIZED VIEW; Schema: public; Owner: -
--

CREATE MATERIALIZED VIEW public.mv_user_daily_stats AS
 SELECT date(created_at) AS stat_date,
    count(*) AS new_users,
    count(*) FILTER (WHERE (email IS NOT NULL)) AS users_with_email
   FROM public.users
  GROUP BY (date(created_at))
  ORDER BY (date(created_at)) DESC
  WITH NO DATA;


--
-- Name: restaurant_reviews_vector; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.restaurant_reviews_vector (
    id bigint NOT NULL,
    restaurant_id character varying(100) NOT NULL,
    restaurant_name character varying(200) NOT NULL,
    city character varying(50) NOT NULL,
    cuisine_type character varying(100),
    address text,
    avg_price numeric(10,2),
    rating numeric(3,2),
    review_text text NOT NULL,
    review_tags text[],
    embedding public.vector(1024),
    user_id character varying(100),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_rating CHECK (((rating >= (0)::numeric) AND (rating <= (5)::numeric)))
);


--
-- Name: TABLE restaurant_reviews_vector; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.restaurant_reviews_vector IS '餐厅评论向量表 - 支持语义搜索餐厅';


--
-- Name: COLUMN restaurant_reviews_vector.embedding; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.restaurant_reviews_vector.embedding IS '评论文本的向量表示（bge-m3, 1024维）';


--
-- Name: restaurant_reviews_vector_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.restaurant_reviews_vector_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: restaurant_reviews_vector_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.restaurant_reviews_vector_id_seq OWNED BY public.restaurant_reviews_vector.id;


--
-- Name: routing_call_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.routing_call_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: routing_call_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.routing_call_log_id_seq OWNED BY public.routing_call_log.id;


--
-- Name: routing_rules_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.routing_rules_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tourist_attractions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.tourist_attractions ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.tourist_attractions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: travel_note_chunks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.travel_note_chunks (
    id bigint NOT NULL,
    note_id bigint NOT NULL,
    chunk_text text NOT NULL,
    chunk_index integer NOT NULL,
    embedding public.vector(1024),
    location_keywords text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: travel_note_chunks_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.travel_note_chunks_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: travel_note_chunks_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.travel_note_chunks_id_seq OWNED BY public.travel_note_chunks.id;


--
-- Name: user_favorited_attractions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_favorited_attractions (
    profile_id bigint NOT NULL,
    attraction_name character varying(255) NOT NULL
);


--
-- Name: user_preferred_cities; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_preferred_cities (
    profile_id bigint NOT NULL,
    city character varying(255) NOT NULL
);


--
-- Name: user_preferred_levels; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_preferred_levels (
    profile_id bigint NOT NULL,
    level character varying(255) NOT NULL
);


--
-- Name: user_preferred_tags; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_preferred_tags (
    profile_id bigint NOT NULL,
    tag character varying(255) NOT NULL
);


--
-- Name: user_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_sessions (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    token_id character varying(100) NOT NULL,
    device_info jsonb,
    ip_address character varying(50),
    user_agent character varying(500),
    is_active boolean DEFAULT true,
    is_revoked boolean DEFAULT false,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    last_active_at timestamp without time zone,
    expires_at timestamp without time zone,
    revoked_at timestamp without time zone
);


--
-- Name: TABLE user_sessions; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.user_sessions IS '用户会话表 - 存储会话历史用于审计和追踪';


--
-- Name: COLUMN user_sessions.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_sessions.user_id IS '用户 ID';


--
-- Name: COLUMN user_sessions.token_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_sessions.token_id IS 'JWT Token ID (jti)';


--
-- Name: COLUMN user_sessions.device_info; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_sessions.device_info IS '设备信息 JSON';


--
-- Name: COLUMN user_sessions.ip_address; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_sessions.ip_address IS 'IP 地址';


--
-- Name: COLUMN user_sessions.user_agent; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_sessions.user_agent IS 'User Agent';


--
-- Name: COLUMN user_sessions.is_active; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_sessions.is_active IS '是否活跃';


--
-- Name: COLUMN user_sessions.is_revoked; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_sessions.is_revoked IS '是否已撤销';


--
-- Name: COLUMN user_sessions.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_sessions.created_at IS '创建时间';


--
-- Name: COLUMN user_sessions.last_active_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_sessions.last_active_at IS '最后活跃时间';


--
-- Name: COLUMN user_sessions.expires_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_sessions.expires_at IS '过期时间';


--
-- Name: COLUMN user_sessions.revoked_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_sessions.revoked_at IS '撤销时间';


--
-- Name: user_sessions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.user_sessions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_sessions_id_seq1; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.user_sessions_id_seq1
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_sessions_id_seq1; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.user_sessions_id_seq1 OWNED BY public.user_sessions.id;


--
-- Name: user_travel_notes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_travel_notes (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    title character varying(255),
    content text,
    source_type character varying(50) DEFAULT 'text'::character varying,
    location character varying(255),
    tags text,
    status character varying(20) DEFAULT 'active'::character varying,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    source_url text
);


--
-- Name: user_travel_notes_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.user_travel_notes_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_travel_notes_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.user_travel_notes_id_seq OWNED BY public.user_travel_notes.id;


--
-- Name: user_viewed_attractions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_viewed_attractions (
    profile_id bigint NOT NULL,
    attraction_name character varying(255) NOT NULL
);


--
-- Name: users_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.users ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.users_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ab_test_events id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ab_test_events ALTER COLUMN id SET DEFAULT nextval('public.ab_test_events_id_seq'::regclass);


--
-- Name: conversation_feedback id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversation_feedback ALTER COLUMN id SET DEFAULT nextval('public.conversation_feedback_id_seq'::regclass);


--
-- Name: restaurant_reviews_vector id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.restaurant_reviews_vector ALTER COLUMN id SET DEFAULT nextval('public.restaurant_reviews_vector_id_seq'::regclass);


--
-- Name: routing_call_log id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.routing_call_log ALTER COLUMN id SET DEFAULT nextval('public.routing_call_log_id_seq'::regclass);


--
-- Name: travel_note_chunks id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.travel_note_chunks ALTER COLUMN id SET DEFAULT nextval('public.travel_note_chunks_id_seq'::regclass);


--
-- Name: user_sessions id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_sessions ALTER COLUMN id SET DEFAULT nextval('public.user_sessions_id_seq1'::regclass);


--
-- Name: user_travel_notes id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_travel_notes ALTER COLUMN id SET DEFAULT nextval('public.user_travel_notes_id_seq'::regclass);


--
-- Name: ab_test_events ab_test_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ab_test_events
    ADD CONSTRAINT ab_test_events_pkey PRIMARY KEY (id);


--
-- Name: conversation_feedback conversation_feedback_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversation_feedback
    ADD CONSTRAINT conversation_feedback_pkey PRIMARY KEY (id);


--
-- Name: restaurant_reviews_vector restaurant_reviews_vector_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.restaurant_reviews_vector
    ADD CONSTRAINT restaurant_reviews_vector_pkey PRIMARY KEY (id);


--
-- Name: routing_call_log routing_call_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.routing_call_log
    ADD CONSTRAINT routing_call_log_pkey PRIMARY KEY (id);


--
-- Name: tourist_attractions tourist_attractions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tourist_attractions
    ADD CONSTRAINT tourist_attractions_pkey PRIMARY KEY (id);


--
-- Name: travel_note_chunks travel_note_chunks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.travel_note_chunks
    ADD CONSTRAINT travel_note_chunks_pkey PRIMARY KEY (id);


--
-- Name: user_favorited_attractions user_favorited_attractions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_favorited_attractions
    ADD CONSTRAINT user_favorited_attractions_pkey PRIMARY KEY (profile_id, attraction_name);


--
-- Name: user_preferred_cities user_preferred_cities_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_preferred_cities
    ADD CONSTRAINT user_preferred_cities_pkey PRIMARY KEY (profile_id, city);


--
-- Name: user_preferred_levels user_preferred_levels_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_preferred_levels
    ADD CONSTRAINT user_preferred_levels_pkey PRIMARY KEY (profile_id, level);


--
-- Name: user_preferred_tags user_preferred_tags_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_preferred_tags
    ADD CONSTRAINT user_preferred_tags_pkey PRIMARY KEY (profile_id, tag);


--
-- Name: user_sessions user_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_sessions
    ADD CONSTRAINT user_sessions_pkey PRIMARY KEY (id);


--
-- Name: user_travel_notes user_travel_notes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_travel_notes
    ADD CONSTRAINT user_travel_notes_pkey PRIMARY KEY (id);


--
-- Name: user_viewed_attractions user_viewed_attractions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_viewed_attractions
    ADD CONSTRAINT user_viewed_attractions_pkey PRIMARY KEY (profile_id, attraction_name);


--
-- Name: idx_ab_events_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ab_events_created_at ON public.ab_test_events USING btree (created_at DESC);


--
-- Name: idx_ab_events_group_type_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ab_events_group_type_time ON public.ab_test_events USING btree (test_group, event_type, created_at);


--
-- Name: idx_ab_events_restaurant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ab_events_restaurant_id ON public.ab_test_events USING btree (restaurant_id);


--
-- Name: idx_ab_events_test_group; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ab_events_test_group ON public.ab_test_events USING btree (test_group);


--
-- Name: idx_ab_events_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ab_events_user_id ON public.ab_test_events USING btree (user_id);


--
-- Name: idx_attractions_city_level; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_attractions_city_level ON public.tourist_attractions USING btree (city, level);


--
-- Name: idx_attractions_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_attractions_created_at ON public.tourist_attractions USING btree (created_at);


--
-- Name: idx_attractions_ticket_price; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_attractions_ticket_price ON public.tourist_attractions USING btree (ticket_price);


--
-- Name: idx_city; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_city ON public.tourist_attractions USING btree (city);


--
-- Name: idx_feedback_agent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_feedback_agent ON public.conversation_feedback USING btree (agent_name);


--
-- Name: idx_feedback_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_feedback_created ON public.conversation_feedback USING btree (created_at);


--
-- Name: idx_feedback_rating; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_feedback_rating ON public.conversation_feedback USING btree (rating);


--
-- Name: idx_feedback_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_feedback_user ON public.conversation_feedback USING btree (user_id);


--
-- Name: idx_level; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_level ON public.tourist_attractions USING btree (level);


--
-- Name: idx_mv_city_attraction_stats_city; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_mv_city_attraction_stats_city ON public.mv_city_attraction_stats USING btree (city, province);


--
-- Name: idx_mv_city_attraction_stats_count; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mv_city_attraction_stats_count ON public.mv_city_attraction_stats USING btree (attraction_count DESC);


--
-- Name: idx_mv_routing_agent_stats_agent; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_mv_routing_agent_stats_agent ON public.mv_routing_agent_stats USING btree (agent_name);


--
-- Name: idx_mv_user_daily_stats_date; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_mv_user_daily_stats_date ON public.mv_user_daily_stats USING btree (stat_date);


--
-- Name: idx_province; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_province ON public.tourist_attractions USING btree (province);


--
-- Name: idx_reviews_city_cuisine; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reviews_city_cuisine ON public.restaurant_reviews_vector USING btree (city, cuisine_type);


--
-- Name: idx_reviews_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reviews_created_at ON public.restaurant_reviews_vector USING btree (created_at DESC);


--
-- Name: idx_reviews_embedding_hnsw; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reviews_embedding_hnsw ON public.restaurant_reviews_vector USING hnsw (embedding public.vector_cosine_ops) WITH (m='16', ef_construction='64');


--
-- Name: idx_reviews_rating; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reviews_rating ON public.restaurant_reviews_vector USING btree (rating DESC);


--
-- Name: idx_routing_call_log_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_routing_call_log_created_at ON public.routing_call_log USING btree (created_at);


--
-- Name: idx_routing_call_log_routed_agent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_routing_call_log_routed_agent ON public.routing_call_log USING btree (routed_agent);


--
-- Name: idx_routing_call_log_session_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_routing_call_log_session_id ON public.routing_call_log USING btree (session_id);


--
-- Name: idx_routing_call_log_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_routing_call_log_status ON public.routing_call_log USING btree (status);


--
-- Name: idx_travel_chunks_embedding; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_travel_chunks_embedding ON public.travel_note_chunks USING ivfflat (embedding public.vector_cosine_ops);


--
-- Name: idx_travel_chunks_location; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_travel_chunks_location ON public.travel_note_chunks USING gin (to_tsvector('simple'::regconfig, location_keywords));


--
-- Name: idx_travel_chunks_note_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_travel_chunks_note_id ON public.travel_note_chunks USING btree (note_id);


--
-- Name: idx_travel_notes_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_travel_notes_user_id ON public.user_travel_notes USING btree (user_id);


--
-- Name: idx_user_sessions_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_sessions_created_at ON public.user_sessions USING btree (created_at);


--
-- Name: idx_user_sessions_is_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_sessions_is_active ON public.user_sessions USING btree (is_active);


--
-- Name: idx_user_sessions_token_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_sessions_token_id ON public.user_sessions USING btree (token_id);


--
-- Name: idx_user_sessions_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_sessions_user_id ON public.user_sessions USING btree (user_id);


--
-- Name: idx_users_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_created_at ON public.users USING btree (created_at);


--
-- Name: idx_users_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_role ON public.users USING btree (role);


--
-- Name: idx_users_username; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_username ON public.users USING btree (username);


--
-- Name: restaurant_reviews_vector trg_reviews_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_reviews_updated_at BEFORE UPDATE ON public.restaurant_reviews_vector FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: attraction_highlights fk1xfm66h16d9kdi3jt531yq1yg; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.attraction_highlights
    ADD CONSTRAINT fk1xfm66h16d9kdi3jt531yq1yg FOREIGN KEY (attraction_id) REFERENCES public.tourist_attractions(id);


--
-- Name: attraction_tags fk6qkxqpa03dv5badi42x96nlo1; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.attraction_tags
    ADD CONSTRAINT fk6qkxqpa03dv5badi42x96nlo1 FOREIGN KEY (attraction_id) REFERENCES public.tourist_attractions(id);


--
-- Name: travel_note_chunks travel_note_chunks_note_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.travel_note_chunks
    ADD CONSTRAINT travel_note_chunks_note_id_fkey FOREIGN KEY (note_id) REFERENCES public.user_travel_notes(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--


