--
-- PostgreSQL database dump
--

\restrict gqxfkF8wPGILBr9f4Rb3afKGmgQoCgbHm8YamZFBnIJSA3EprcmseYTwboOvjPE

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
-- Name: auth_chat_messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.auth_chat_messages (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    thread_id character varying(100) NOT NULL,
    role character varying(10) NOT NULL,
    content text NOT NULL,
    agent_name character varying(50),
    created_at timestamp without time zone DEFAULT now()
);


--
-- Name: auth_chat_messages_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.auth_chat_messages_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: auth_chat_messages_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.auth_chat_messages_id_seq OWNED BY public.auth_chat_messages.id;


--
-- Name: chat_messages_partitioned; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_messages_partitioned (
    id bigint NOT NULL,
    user_id bigint,
    thread_id character varying(100) NOT NULL,
    role character varying(10) NOT NULL,
    content text NOT NULL,
    agent_name character varying(50),
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    session_id character varying(255),
    is_user boolean DEFAULT false,
    target_agent character varying(255),
    turn_count integer,
    metadata text,
    CONSTRAINT chat_messages_partitioned_role_check CHECK (((role)::text = ANY ((ARRAY['user'::character varying, 'assistant'::character varying])::text[])))
)
PARTITION BY RANGE (created_at);


--
-- Name: TABLE chat_messages_partitioned; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.chat_messages_partitioned IS '聊天消息表（按月分区）';


--
-- Name: COLUMN chat_messages_partitioned.session_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_messages_partitioned.session_id IS 'Session ID';


--
-- Name: COLUMN chat_messages_partitioned.is_user; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_messages_partitioned.is_user IS 'Is user message: true=user, false=AI';


--
-- Name: COLUMN chat_messages_partitioned.target_agent; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_messages_partitioned.target_agent IS 'Target agent name';


--
-- Name: COLUMN chat_messages_partitioned.turn_count; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_messages_partitioned.turn_count IS 'Conversation turn count';


--
-- Name: COLUMN chat_messages_partitioned.metadata; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chat_messages_partitioned.metadata IS 'JSON format extra metadata';


--
-- Name: chat_messages; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.chat_messages AS
 SELECT id,
    user_id,
    thread_id,
    role,
    content,
    agent_name,
    created_at,
    session_id,
    is_user,
    target_agent,
    turn_count,
    metadata
   FROM public.chat_messages_partitioned;


--
-- Name: VIEW chat_messages; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON VIEW public.chat_messages IS '聊天消息表兼容视图（指向分区表）';


--
-- Name: chat_messages_partitioned_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.chat_messages_partitioned_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: chat_messages_partitioned_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.chat_messages_partitioned_id_seq OWNED BY public.chat_messages_partitioned.id;


--
-- Name: chat_messages_2026_01; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_messages_2026_01 (
    id bigint DEFAULT nextval('public.chat_messages_partitioned_id_seq'::regclass) CONSTRAINT chat_messages_partitioned_id_not_null NOT NULL,
    user_id bigint,
    thread_id character varying(100) CONSTRAINT chat_messages_partitioned_thread_id_not_null NOT NULL,
    role character varying(10) CONSTRAINT chat_messages_partitioned_role_not_null NOT NULL,
    content text CONSTRAINT chat_messages_partitioned_content_not_null NOT NULL,
    agent_name character varying(50),
    created_at timestamp without time zone DEFAULT now() CONSTRAINT chat_messages_partitioned_created_at_not_null NOT NULL,
    session_id character varying(255),
    is_user boolean DEFAULT false,
    target_agent character varying(255),
    turn_count integer,
    metadata text,
    CONSTRAINT chat_messages_partitioned_role_check CHECK (((role)::text = ANY ((ARRAY['user'::character varying, 'assistant'::character varying])::text[])))
);


--
-- Name: chat_messages_2026_02; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_messages_2026_02 (
    id bigint DEFAULT nextval('public.chat_messages_partitioned_id_seq'::regclass) CONSTRAINT chat_messages_partitioned_id_not_null NOT NULL,
    user_id bigint,
    thread_id character varying(100) CONSTRAINT chat_messages_partitioned_thread_id_not_null NOT NULL,
    role character varying(10) CONSTRAINT chat_messages_partitioned_role_not_null NOT NULL,
    content text CONSTRAINT chat_messages_partitioned_content_not_null NOT NULL,
    agent_name character varying(50),
    created_at timestamp without time zone DEFAULT now() CONSTRAINT chat_messages_partitioned_created_at_not_null NOT NULL,
    session_id character varying(255),
    is_user boolean DEFAULT false,
    target_agent character varying(255),
    turn_count integer,
    metadata text,
    CONSTRAINT chat_messages_partitioned_role_check CHECK (((role)::text = ANY ((ARRAY['user'::character varying, 'assistant'::character varying])::text[])))
);


--
-- Name: chat_messages_2026_03; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_messages_2026_03 (
    id bigint DEFAULT nextval('public.chat_messages_partitioned_id_seq'::regclass) CONSTRAINT chat_messages_partitioned_id_not_null NOT NULL,
    user_id bigint,
    thread_id character varying(100) CONSTRAINT chat_messages_partitioned_thread_id_not_null NOT NULL,
    role character varying(10) CONSTRAINT chat_messages_partitioned_role_not_null NOT NULL,
    content text CONSTRAINT chat_messages_partitioned_content_not_null NOT NULL,
    agent_name character varying(50),
    created_at timestamp without time zone DEFAULT now() CONSTRAINT chat_messages_partitioned_created_at_not_null NOT NULL,
    session_id character varying(255),
    is_user boolean DEFAULT false,
    target_agent character varying(255),
    turn_count integer,
    metadata text,
    CONSTRAINT chat_messages_partitioned_role_check CHECK (((role)::text = ANY ((ARRAY['user'::character varying, 'assistant'::character varying])::text[])))
);


--
-- Name: chat_messages_2026_04; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_messages_2026_04 (
    id bigint DEFAULT nextval('public.chat_messages_partitioned_id_seq'::regclass) CONSTRAINT chat_messages_partitioned_id_not_null NOT NULL,
    user_id bigint,
    thread_id character varying(100) CONSTRAINT chat_messages_partitioned_thread_id_not_null NOT NULL,
    role character varying(10) CONSTRAINT chat_messages_partitioned_role_not_null NOT NULL,
    content text CONSTRAINT chat_messages_partitioned_content_not_null NOT NULL,
    agent_name character varying(50),
    created_at timestamp without time zone DEFAULT now() CONSTRAINT chat_messages_partitioned_created_at_not_null NOT NULL,
    session_id character varying(255),
    is_user boolean DEFAULT false,
    target_agent character varying(255),
    turn_count integer,
    metadata text,
    CONSTRAINT chat_messages_partitioned_role_check CHECK (((role)::text = ANY ((ARRAY['user'::character varying, 'assistant'::character varying])::text[])))
);


--
-- Name: chat_messages_2026_05; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_messages_2026_05 (
    id bigint DEFAULT nextval('public.chat_messages_partitioned_id_seq'::regclass) CONSTRAINT chat_messages_partitioned_id_not_null NOT NULL,
    user_id bigint,
    thread_id character varying(100) CONSTRAINT chat_messages_partitioned_thread_id_not_null NOT NULL,
    role character varying(10) CONSTRAINT chat_messages_partitioned_role_not_null NOT NULL,
    content text CONSTRAINT chat_messages_partitioned_content_not_null NOT NULL,
    agent_name character varying(50),
    created_at timestamp without time zone DEFAULT now() CONSTRAINT chat_messages_partitioned_created_at_not_null NOT NULL,
    session_id character varying(255),
    is_user boolean DEFAULT false,
    target_agent character varying(255),
    turn_count integer,
    metadata text,
    CONSTRAINT chat_messages_partitioned_role_check CHECK (((role)::text = ANY ((ARRAY['user'::character varying, 'assistant'::character varying])::text[])))
);


--
-- Name: chat_messages_2026_06; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_messages_2026_06 (
    id bigint DEFAULT nextval('public.chat_messages_partitioned_id_seq'::regclass) CONSTRAINT chat_messages_partitioned_id_not_null NOT NULL,
    user_id bigint,
    thread_id character varying(100) CONSTRAINT chat_messages_partitioned_thread_id_not_null NOT NULL,
    role character varying(10) CONSTRAINT chat_messages_partitioned_role_not_null NOT NULL,
    content text CONSTRAINT chat_messages_partitioned_content_not_null NOT NULL,
    agent_name character varying(50),
    created_at timestamp without time zone DEFAULT now() CONSTRAINT chat_messages_partitioned_created_at_not_null NOT NULL,
    session_id character varying(255),
    is_user boolean DEFAULT false,
    target_agent character varying(255),
    turn_count integer,
    metadata text,
    CONSTRAINT chat_messages_partitioned_role_check CHECK (((role)::text = ANY ((ARRAY['user'::character varying, 'assistant'::character varying])::text[])))
);


--
-- Name: chat_messages_2026_07; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_messages_2026_07 (
    id bigint DEFAULT nextval('public.chat_messages_partitioned_id_seq'::regclass) CONSTRAINT chat_messages_partitioned_id_not_null NOT NULL,
    user_id bigint,
    thread_id character varying(100) CONSTRAINT chat_messages_partitioned_thread_id_not_null NOT NULL,
    role character varying(10) CONSTRAINT chat_messages_partitioned_role_not_null NOT NULL,
    content text CONSTRAINT chat_messages_partitioned_content_not_null NOT NULL,
    agent_name character varying(50),
    created_at timestamp without time zone DEFAULT now() CONSTRAINT chat_messages_partitioned_created_at_not_null NOT NULL,
    session_id character varying(255),
    is_user boolean DEFAULT false,
    target_agent character varying(255),
    turn_count integer,
    metadata text,
    CONSTRAINT chat_messages_partitioned_role_check CHECK (((role)::text = ANY ((ARRAY['user'::character varying, 'assistant'::character varying])::text[])))
);


--
-- Name: chat_messages_2026_08; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_messages_2026_08 (
    id bigint DEFAULT nextval('public.chat_messages_partitioned_id_seq'::regclass) CONSTRAINT chat_messages_partitioned_id_not_null NOT NULL,
    user_id bigint,
    thread_id character varying(100) CONSTRAINT chat_messages_partitioned_thread_id_not_null NOT NULL,
    role character varying(10) CONSTRAINT chat_messages_partitioned_role_not_null NOT NULL,
    content text CONSTRAINT chat_messages_partitioned_content_not_null NOT NULL,
    agent_name character varying(50),
    created_at timestamp without time zone DEFAULT now() CONSTRAINT chat_messages_partitioned_created_at_not_null NOT NULL,
    session_id character varying(255),
    is_user boolean DEFAULT false,
    target_agent character varying(255),
    turn_count integer,
    metadata text,
    CONSTRAINT chat_messages_partitioned_role_check CHECK (((role)::text = ANY ((ARRAY['user'::character varying, 'assistant'::character varying])::text[])))
);


--
-- Name: chat_messages_2026_09; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_messages_2026_09 (
    id bigint DEFAULT nextval('public.chat_messages_partitioned_id_seq'::regclass) CONSTRAINT chat_messages_partitioned_id_not_null NOT NULL,
    user_id bigint,
    thread_id character varying(100) CONSTRAINT chat_messages_partitioned_thread_id_not_null NOT NULL,
    role character varying(10) CONSTRAINT chat_messages_partitioned_role_not_null NOT NULL,
    content text CONSTRAINT chat_messages_partitioned_content_not_null NOT NULL,
    agent_name character varying(50),
    created_at timestamp without time zone DEFAULT now() CONSTRAINT chat_messages_partitioned_created_at_not_null NOT NULL,
    session_id character varying(255),
    is_user boolean DEFAULT false,
    target_agent character varying(255),
    turn_count integer,
    metadata text,
    CONSTRAINT chat_messages_partitioned_role_check CHECK (((role)::text = ANY ((ARRAY['user'::character varying, 'assistant'::character varying])::text[])))
);


--
-- Name: chat_messages_backup_before_migration; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_messages_backup_before_migration (
    id bigint,
    user_id bigint,
    thread_id character varying(100),
    role character varying(10),
    content text,
    agent_name character varying(50),
    created_at timestamp without time zone
);


--
-- Name: chat_messages_old_table; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_messages_old_table (
    id bigint CONSTRAINT chat_messages_id_not_null NOT NULL,
    user_id bigint,
    thread_id character varying(100) CONSTRAINT chat_messages_thread_id_not_null NOT NULL,
    role character varying(10) CONSTRAINT chat_messages_role_not_null NOT NULL,
    content text CONSTRAINT chat_messages_content_not_null NOT NULL,
    agent_name character varying(50),
    created_at timestamp without time zone DEFAULT now(),
    CONSTRAINT chat_messages_role_check CHECK (((role)::text = ANY (ARRAY[('user'::character varying)::text, ('assistant'::character varying)::text])))
);


--
-- Name: food_culture_vector; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.food_culture_vector (
    id bigint NOT NULL,
    dish_name character varying(200) NOT NULL,
    region character varying(100),
    category character varying(50),
    content text NOT NULL,
    related_dishes text[],
    embedding public.vector(1024),
    source character varying(200),
    credibility_score numeric(3,2),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: TABLE food_culture_vector; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.food_culture_vector IS '美食文化知识库 - 提供菜品背后的文化故事';


--
-- Name: COLUMN food_culture_vector.embedding; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.food_culture_vector.embedding IS '文化内容的向量表示';


--
-- Name: food_culture_vector_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.food_culture_vector_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: food_culture_vector_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.food_culture_vector_id_seq OWNED BY public.food_culture_vector.id;


--
-- Name: mv_chat_message_daily_stats; Type: MATERIALIZED VIEW; Schema: public; Owner: -
--

CREATE MATERIALIZED VIEW public.mv_chat_message_daily_stats AS
 SELECT date(created_at) AS stat_date,
    agent_name,
    count(*) AS message_count,
    count(*) FILTER (WHERE ((role)::text = 'user'::text)) AS user_messages,
    count(*) FILTER (WHERE ((role)::text = 'assistant'::text)) AS assistant_messages
   FROM public.chat_messages_old_table
  GROUP BY (date(created_at)), agent_name
  ORDER BY (date(created_at)) DESC, (count(*)) DESC
  WITH NO DATA;


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
-- Name: recipes_vector; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.recipes_vector (
    id bigint NOT NULL,
    recipe_name character varying(200) NOT NULL,
    difficulty character varying(20),
    cook_time_minutes integer,
    calories_per_serving integer,
    ingredients text[],
    steps text,
    tags text[],
    dietary_info text[],
    embedding public.vector(1024),
    source character varying(100),
    view_count integer DEFAULT 0,
    favorite_count integer DEFAULT 0,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: TABLE recipes_vector; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.recipes_vector IS '菜谱向量表 - 支持基于食材和需求的菜谱推荐';


--
-- Name: COLUMN recipes_vector.embedding; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.recipes_vector.embedding IS '菜谱描述的向量表示';


--
-- Name: recipes_vector_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.recipes_vector_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: recipes_vector_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.recipes_vector_id_seq OWNED BY public.recipes_vector.id;


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
-- Name: user_food_preferences_vector; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_food_preferences_vector (
    id bigint NOT NULL,
    user_id character varying(100) NOT NULL,
    preferred_cuisines text[],
    disliked_ingredients text[],
    dietary_restrictions text[],
    budget_range character varying(20),
    preference_embedding public.vector(1024),
    last_updated timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    interaction_count integer DEFAULT 0
);


--
-- Name: TABLE user_food_preferences_vector; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.user_food_preferences_vector IS '用户饮食偏好向量表 - 用于个性化推荐';


--
-- Name: COLUMN user_food_preferences_vector.preference_embedding; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_food_preferences_vector.preference_embedding IS '用户偏好的向量表示';


--
-- Name: user_food_preferences_vector_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.user_food_preferences_vector_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_food_preferences_vector_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.user_food_preferences_vector_id_seq OWNED BY public.user_food_preferences_vector.id;


--
-- Name: user_preference_vectors; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_preference_vectors (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    vector_type character varying(50) NOT NULL,
    content text NOT NULL,
    embedding_id character varying(100) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: user_preference_vectors_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.user_preference_vectors_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_preference_vectors_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.user_preference_vectors_id_seq OWNED BY public.user_preference_vectors.id;


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
-- Name: user_profiles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_profiles (
    id bigint NOT NULL,
    user_id character varying(100),
    preferred_locations text[],
    food_preferences text[],
    travel_preferences text[],
    dietary_restrictions text[],
    budget_range character varying(20),
    additional_preferences jsonb,
    total_queries integer DEFAULT 0,
    last_query_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    preferred_budget_max double precision,
    preferred_budget_min double precision,
    preferred_duration integer,
    total_favorites integer,
    total_views integer,
    username character varying(50),
    preference_weights text DEFAULT '{}'::text
);


--
-- Name: COLUMN user_profiles.preference_weights; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.user_profiles.preference_weights IS '偏好权重 JSON 格式，示例: {"川菜": 10, "火锅": 8, "辣": 5}';


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
-- Name: vector_store; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.vector_store (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    content text,
    metadata json,
    embedding public.vector(1024)
);


--
-- Name: chat_messages_2026_01; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages_partitioned ATTACH PARTITION public.chat_messages_2026_01 FOR VALUES FROM ('2026-01-01 00:00:00') TO ('2026-02-01 00:00:00');


--
-- Name: chat_messages_2026_02; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages_partitioned ATTACH PARTITION public.chat_messages_2026_02 FOR VALUES FROM ('2026-02-01 00:00:00') TO ('2026-03-01 00:00:00');


--
-- Name: chat_messages_2026_03; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages_partitioned ATTACH PARTITION public.chat_messages_2026_03 FOR VALUES FROM ('2026-03-01 00:00:00') TO ('2026-04-01 00:00:00');


--
-- Name: chat_messages_2026_04; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages_partitioned ATTACH PARTITION public.chat_messages_2026_04 FOR VALUES FROM ('2026-04-01 00:00:00') TO ('2026-05-01 00:00:00');


--
-- Name: chat_messages_2026_05; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages_partitioned ATTACH PARTITION public.chat_messages_2026_05 FOR VALUES FROM ('2026-05-01 00:00:00') TO ('2026-06-01 00:00:00');


--
-- Name: chat_messages_2026_06; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages_partitioned ATTACH PARTITION public.chat_messages_2026_06 FOR VALUES FROM ('2026-06-01 00:00:00') TO ('2026-07-01 00:00:00');


--
-- Name: chat_messages_2026_07; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages_partitioned ATTACH PARTITION public.chat_messages_2026_07 FOR VALUES FROM ('2026-07-01 00:00:00') TO ('2026-08-01 00:00:00');


--
-- Name: chat_messages_2026_08; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages_partitioned ATTACH PARTITION public.chat_messages_2026_08 FOR VALUES FROM ('2026-08-01 00:00:00') TO ('2026-09-01 00:00:00');


--
-- Name: chat_messages_2026_09; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages_partitioned ATTACH PARTITION public.chat_messages_2026_09 FOR VALUES FROM ('2026-09-01 00:00:00') TO ('2026-10-01 00:00:00');


--
-- Name: ab_test_events id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ab_test_events ALTER COLUMN id SET DEFAULT nextval('public.ab_test_events_id_seq'::regclass);


--
-- Name: auth_chat_messages id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_chat_messages ALTER COLUMN id SET DEFAULT nextval('public.auth_chat_messages_id_seq'::regclass);


--
-- Name: chat_messages_partitioned id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages_partitioned ALTER COLUMN id SET DEFAULT nextval('public.chat_messages_partitioned_id_seq'::regclass);


--
-- Name: food_culture_vector id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.food_culture_vector ALTER COLUMN id SET DEFAULT nextval('public.food_culture_vector_id_seq'::regclass);


--
-- Name: recipes_vector id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recipes_vector ALTER COLUMN id SET DEFAULT nextval('public.recipes_vector_id_seq'::regclass);


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
-- Name: user_food_preferences_vector id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_food_preferences_vector ALTER COLUMN id SET DEFAULT nextval('public.user_food_preferences_vector_id_seq'::regclass);


--
-- Name: user_preference_vectors id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_preference_vectors ALTER COLUMN id SET DEFAULT nextval('public.user_preference_vectors_id_seq'::regclass);


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
-- Name: auth_chat_messages auth_chat_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_chat_messages
    ADD CONSTRAINT auth_chat_messages_pkey PRIMARY KEY (id);


--
-- Name: chat_messages_partitioned chat_messages_partitioned_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages_partitioned
    ADD CONSTRAINT chat_messages_partitioned_pkey PRIMARY KEY (id, created_at);


--
-- Name: chat_messages_2026_01 chat_messages_2026_01_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages_2026_01
    ADD CONSTRAINT chat_messages_2026_01_pkey PRIMARY KEY (id, created_at);


--
-- Name: chat_messages_2026_02 chat_messages_2026_02_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages_2026_02
    ADD CONSTRAINT chat_messages_2026_02_pkey PRIMARY KEY (id, created_at);


--
-- Name: chat_messages_2026_03 chat_messages_2026_03_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages_2026_03
    ADD CONSTRAINT chat_messages_2026_03_pkey PRIMARY KEY (id, created_at);


--
-- Name: chat_messages_2026_04 chat_messages_2026_04_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages_2026_04
    ADD CONSTRAINT chat_messages_2026_04_pkey PRIMARY KEY (id, created_at);


--
-- Name: chat_messages_2026_05 chat_messages_2026_05_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages_2026_05
    ADD CONSTRAINT chat_messages_2026_05_pkey PRIMARY KEY (id, created_at);


--
-- Name: chat_messages_2026_06 chat_messages_2026_06_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages_2026_06
    ADD CONSTRAINT chat_messages_2026_06_pkey PRIMARY KEY (id, created_at);


--
-- Name: chat_messages_2026_07 chat_messages_2026_07_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages_2026_07
    ADD CONSTRAINT chat_messages_2026_07_pkey PRIMARY KEY (id, created_at);


--
-- Name: chat_messages_2026_08 chat_messages_2026_08_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages_2026_08
    ADD CONSTRAINT chat_messages_2026_08_pkey PRIMARY KEY (id, created_at);


--
-- Name: chat_messages_2026_09 chat_messages_2026_09_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages_2026_09
    ADD CONSTRAINT chat_messages_2026_09_pkey PRIMARY KEY (id, created_at);


--
-- Name: food_culture_vector food_culture_vector_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.food_culture_vector
    ADD CONSTRAINT food_culture_vector_pkey PRIMARY KEY (id);


--
-- Name: recipes_vector recipes_vector_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recipes_vector
    ADD CONSTRAINT recipes_vector_pkey PRIMARY KEY (id);


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
-- Name: user_preference_vectors uk_user_vector_type; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_preference_vectors
    ADD CONSTRAINT uk_user_vector_type UNIQUE (user_id, vector_type);


--
-- Name: user_favorited_attractions user_favorited_attractions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_favorited_attractions
    ADD CONSTRAINT user_favorited_attractions_pkey PRIMARY KEY (profile_id, attraction_name);


--
-- Name: user_food_preferences_vector user_food_preferences_vector_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_food_preferences_vector
    ADD CONSTRAINT user_food_preferences_vector_pkey PRIMARY KEY (id);


--
-- Name: user_food_preferences_vector user_food_preferences_vector_user_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_food_preferences_vector
    ADD CONSTRAINT user_food_preferences_vector_user_id_key UNIQUE (user_id);


--
-- Name: user_preference_vectors user_preference_vectors_embedding_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_preference_vectors
    ADD CONSTRAINT user_preference_vectors_embedding_id_key UNIQUE (embedding_id);


--
-- Name: user_preference_vectors user_preference_vectors_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_preference_vectors
    ADD CONSTRAINT user_preference_vectors_pkey PRIMARY KEY (id);


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
-- Name: user_profiles user_profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_profiles
    ADD CONSTRAINT user_profiles_pkey PRIMARY KEY (id);


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
-- Name: vector_store vector_store_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vector_store
    ADD CONSTRAINT vector_store_pkey PRIMARY KEY (id);


--
-- Name: idx_chat_msg_part_agent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_msg_part_agent ON ONLY public.chat_messages_partitioned USING btree (agent_name);


--
-- Name: chat_messages_2026_01_agent_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_01_agent_name_idx ON public.chat_messages_2026_01 USING btree (agent_name);


--
-- Name: idx_chat_messages_is_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_messages_is_user ON ONLY public.chat_messages_partitioned USING btree (is_user);


--
-- Name: chat_messages_2026_01_is_user_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_01_is_user_idx ON public.chat_messages_2026_01 USING btree (is_user);


--
-- Name: idx_chat_msg_part_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_msg_part_role ON ONLY public.chat_messages_partitioned USING btree (role);


--
-- Name: chat_messages_2026_01_role_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_01_role_idx ON public.chat_messages_2026_01 USING btree (role);


--
-- Name: idx_chat_messages_session_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_messages_session_id ON ONLY public.chat_messages_partitioned USING btree (session_id);


--
-- Name: chat_messages_2026_01_session_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_01_session_id_idx ON public.chat_messages_2026_01 USING btree (session_id);


--
-- Name: idx_chat_messages_target_agent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_messages_target_agent ON ONLY public.chat_messages_partitioned USING btree (target_agent);


--
-- Name: chat_messages_2026_01_target_agent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_01_target_agent_idx ON public.chat_messages_2026_01 USING btree (target_agent);


--
-- Name: idx_chat_msg_part_thread; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_msg_part_thread ON ONLY public.chat_messages_partitioned USING btree (thread_id, created_at DESC);


--
-- Name: chat_messages_2026_01_thread_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_01_thread_id_created_at_idx ON public.chat_messages_2026_01 USING btree (thread_id, created_at DESC);


--
-- Name: idx_chat_msg_part_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_msg_part_user ON ONLY public.chat_messages_partitioned USING btree (user_id);


--
-- Name: chat_messages_2026_01_user_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_01_user_id_idx ON public.chat_messages_2026_01 USING btree (user_id);


--
-- Name: chat_messages_2026_02_agent_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_02_agent_name_idx ON public.chat_messages_2026_02 USING btree (agent_name);


--
-- Name: chat_messages_2026_02_is_user_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_02_is_user_idx ON public.chat_messages_2026_02 USING btree (is_user);


--
-- Name: chat_messages_2026_02_role_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_02_role_idx ON public.chat_messages_2026_02 USING btree (role);


--
-- Name: chat_messages_2026_02_session_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_02_session_id_idx ON public.chat_messages_2026_02 USING btree (session_id);


--
-- Name: chat_messages_2026_02_target_agent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_02_target_agent_idx ON public.chat_messages_2026_02 USING btree (target_agent);


--
-- Name: chat_messages_2026_02_thread_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_02_thread_id_created_at_idx ON public.chat_messages_2026_02 USING btree (thread_id, created_at DESC);


--
-- Name: chat_messages_2026_02_user_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_02_user_id_idx ON public.chat_messages_2026_02 USING btree (user_id);


--
-- Name: chat_messages_2026_03_agent_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_03_agent_name_idx ON public.chat_messages_2026_03 USING btree (agent_name);


--
-- Name: chat_messages_2026_03_is_user_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_03_is_user_idx ON public.chat_messages_2026_03 USING btree (is_user);


--
-- Name: chat_messages_2026_03_role_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_03_role_idx ON public.chat_messages_2026_03 USING btree (role);


--
-- Name: chat_messages_2026_03_session_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_03_session_id_idx ON public.chat_messages_2026_03 USING btree (session_id);


--
-- Name: chat_messages_2026_03_target_agent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_03_target_agent_idx ON public.chat_messages_2026_03 USING btree (target_agent);


--
-- Name: chat_messages_2026_03_thread_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_03_thread_id_created_at_idx ON public.chat_messages_2026_03 USING btree (thread_id, created_at DESC);


--
-- Name: chat_messages_2026_03_user_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_03_user_id_idx ON public.chat_messages_2026_03 USING btree (user_id);


--
-- Name: chat_messages_2026_04_agent_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_04_agent_name_idx ON public.chat_messages_2026_04 USING btree (agent_name);


--
-- Name: chat_messages_2026_04_is_user_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_04_is_user_idx ON public.chat_messages_2026_04 USING btree (is_user);


--
-- Name: chat_messages_2026_04_role_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_04_role_idx ON public.chat_messages_2026_04 USING btree (role);


--
-- Name: chat_messages_2026_04_session_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_04_session_id_idx ON public.chat_messages_2026_04 USING btree (session_id);


--
-- Name: chat_messages_2026_04_target_agent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_04_target_agent_idx ON public.chat_messages_2026_04 USING btree (target_agent);


--
-- Name: chat_messages_2026_04_thread_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_04_thread_id_created_at_idx ON public.chat_messages_2026_04 USING btree (thread_id, created_at DESC);


--
-- Name: chat_messages_2026_04_user_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_04_user_id_idx ON public.chat_messages_2026_04 USING btree (user_id);


--
-- Name: chat_messages_2026_05_agent_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_05_agent_name_idx ON public.chat_messages_2026_05 USING btree (agent_name);


--
-- Name: chat_messages_2026_05_is_user_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_05_is_user_idx ON public.chat_messages_2026_05 USING btree (is_user);


--
-- Name: chat_messages_2026_05_role_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_05_role_idx ON public.chat_messages_2026_05 USING btree (role);


--
-- Name: chat_messages_2026_05_session_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_05_session_id_idx ON public.chat_messages_2026_05 USING btree (session_id);


--
-- Name: chat_messages_2026_05_target_agent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_05_target_agent_idx ON public.chat_messages_2026_05 USING btree (target_agent);


--
-- Name: chat_messages_2026_05_thread_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_05_thread_id_created_at_idx ON public.chat_messages_2026_05 USING btree (thread_id, created_at DESC);


--
-- Name: chat_messages_2026_05_user_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_05_user_id_idx ON public.chat_messages_2026_05 USING btree (user_id);


--
-- Name: chat_messages_2026_06_agent_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_06_agent_name_idx ON public.chat_messages_2026_06 USING btree (agent_name);


--
-- Name: chat_messages_2026_06_is_user_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_06_is_user_idx ON public.chat_messages_2026_06 USING btree (is_user);


--
-- Name: chat_messages_2026_06_role_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_06_role_idx ON public.chat_messages_2026_06 USING btree (role);


--
-- Name: chat_messages_2026_06_session_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_06_session_id_idx ON public.chat_messages_2026_06 USING btree (session_id);


--
-- Name: chat_messages_2026_06_target_agent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_06_target_agent_idx ON public.chat_messages_2026_06 USING btree (target_agent);


--
-- Name: chat_messages_2026_06_thread_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_06_thread_id_created_at_idx ON public.chat_messages_2026_06 USING btree (thread_id, created_at DESC);


--
-- Name: chat_messages_2026_06_user_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_06_user_id_idx ON public.chat_messages_2026_06 USING btree (user_id);


--
-- Name: chat_messages_2026_07_agent_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_07_agent_name_idx ON public.chat_messages_2026_07 USING btree (agent_name);


--
-- Name: chat_messages_2026_07_is_user_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_07_is_user_idx ON public.chat_messages_2026_07 USING btree (is_user);


--
-- Name: chat_messages_2026_07_role_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_07_role_idx ON public.chat_messages_2026_07 USING btree (role);


--
-- Name: chat_messages_2026_07_session_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_07_session_id_idx ON public.chat_messages_2026_07 USING btree (session_id);


--
-- Name: chat_messages_2026_07_target_agent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_07_target_agent_idx ON public.chat_messages_2026_07 USING btree (target_agent);


--
-- Name: chat_messages_2026_07_thread_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_07_thread_id_created_at_idx ON public.chat_messages_2026_07 USING btree (thread_id, created_at DESC);


--
-- Name: chat_messages_2026_07_user_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_07_user_id_idx ON public.chat_messages_2026_07 USING btree (user_id);


--
-- Name: chat_messages_2026_08_agent_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_08_agent_name_idx ON public.chat_messages_2026_08 USING btree (agent_name);


--
-- Name: chat_messages_2026_08_is_user_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_08_is_user_idx ON public.chat_messages_2026_08 USING btree (is_user);


--
-- Name: chat_messages_2026_08_role_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_08_role_idx ON public.chat_messages_2026_08 USING btree (role);


--
-- Name: chat_messages_2026_08_session_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_08_session_id_idx ON public.chat_messages_2026_08 USING btree (session_id);


--
-- Name: chat_messages_2026_08_target_agent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_08_target_agent_idx ON public.chat_messages_2026_08 USING btree (target_agent);


--
-- Name: chat_messages_2026_08_thread_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_08_thread_id_created_at_idx ON public.chat_messages_2026_08 USING btree (thread_id, created_at DESC);


--
-- Name: chat_messages_2026_08_user_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_08_user_id_idx ON public.chat_messages_2026_08 USING btree (user_id);


--
-- Name: chat_messages_2026_09_agent_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_09_agent_name_idx ON public.chat_messages_2026_09 USING btree (agent_name);


--
-- Name: chat_messages_2026_09_is_user_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_09_is_user_idx ON public.chat_messages_2026_09 USING btree (is_user);


--
-- Name: chat_messages_2026_09_role_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_09_role_idx ON public.chat_messages_2026_09 USING btree (role);


--
-- Name: chat_messages_2026_09_session_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_09_session_id_idx ON public.chat_messages_2026_09 USING btree (session_id);


--
-- Name: chat_messages_2026_09_target_agent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_09_target_agent_idx ON public.chat_messages_2026_09 USING btree (target_agent);


--
-- Name: chat_messages_2026_09_thread_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_09_thread_id_created_at_idx ON public.chat_messages_2026_09 USING btree (thread_id, created_at DESC);


--
-- Name: chat_messages_2026_09_user_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_2026_09_user_id_idx ON public.chat_messages_2026_09 USING btree (user_id);


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
-- Name: idx_chat_messages_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_messages_created_at ON public.chat_messages_old_table USING btree (created_at);


--
-- Name: idx_chat_messages_thread_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_messages_thread_id ON public.chat_messages_old_table USING btree (thread_id);


--
-- Name: idx_city; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_city ON public.tourist_attractions USING btree (city);


--
-- Name: idx_culture_dish_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_culture_dish_name ON public.food_culture_vector USING btree (dish_name);


--
-- Name: idx_culture_embedding_hnsw; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_culture_embedding_hnsw ON public.food_culture_vector USING hnsw (embedding public.vector_cosine_ops) WITH (m='16', ef_construction='64');


--
-- Name: idx_culture_region; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_culture_region ON public.food_culture_vector USING btree (region);


--
-- Name: idx_level; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_level ON public.tourist_attractions USING btree (level);


--
-- Name: idx_mv_chat_msg_daily_stats; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_mv_chat_msg_daily_stats ON public.mv_chat_message_daily_stats USING btree (stat_date, agent_name);


--
-- Name: idx_mv_chat_msg_daily_stats_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mv_chat_msg_daily_stats_date ON public.mv_chat_message_daily_stats USING btree (stat_date);


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
-- Name: idx_recipes_embedding_hnsw; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_recipes_embedding_hnsw ON public.recipes_vector USING hnsw (embedding public.vector_cosine_ops) WITH (m='16', ef_construction='64');


--
-- Name: idx_recipes_ingredients_gin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_recipes_ingredients_gin ON public.recipes_vector USING gin (ingredients);


--
-- Name: idx_recipes_tags_gin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_recipes_tags_gin ON public.recipes_vector USING gin (tags);


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
-- Name: idx_upv_embedding_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_upv_embedding_id ON public.user_preference_vectors USING btree (embedding_id);


--
-- Name: idx_upv_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_upv_user_id ON public.user_preference_vectors USING btree (user_id);


--
-- Name: idx_upv_vector_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_upv_vector_type ON public.user_preference_vectors USING btree (vector_type);


--
-- Name: idx_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_id ON public.user_profiles USING btree (user_id);


--
-- Name: idx_user_pref_embedding_hnsw; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_pref_embedding_hnsw ON public.user_food_preferences_vector USING hnsw (preference_embedding public.vector_cosine_ops) WITH (m='16', ef_construction='64');


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
-- Name: spring_ai_vector_index; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX spring_ai_vector_index ON public.vector_store USING hnsw (embedding public.vector_cosine_ops);


--
-- Name: chat_messages_2026_01_agent_name_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_agent ATTACH PARTITION public.chat_messages_2026_01_agent_name_idx;


--
-- Name: chat_messages_2026_01_is_user_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_is_user ATTACH PARTITION public.chat_messages_2026_01_is_user_idx;


--
-- Name: chat_messages_2026_01_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.chat_messages_partitioned_pkey ATTACH PARTITION public.chat_messages_2026_01_pkey;


--
-- Name: chat_messages_2026_01_role_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_role ATTACH PARTITION public.chat_messages_2026_01_role_idx;


--
-- Name: chat_messages_2026_01_session_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_session_id ATTACH PARTITION public.chat_messages_2026_01_session_id_idx;


--
-- Name: chat_messages_2026_01_target_agent_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_target_agent ATTACH PARTITION public.chat_messages_2026_01_target_agent_idx;


--
-- Name: chat_messages_2026_01_thread_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_thread ATTACH PARTITION public.chat_messages_2026_01_thread_id_created_at_idx;


--
-- Name: chat_messages_2026_01_user_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_user ATTACH PARTITION public.chat_messages_2026_01_user_id_idx;


--
-- Name: chat_messages_2026_02_agent_name_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_agent ATTACH PARTITION public.chat_messages_2026_02_agent_name_idx;


--
-- Name: chat_messages_2026_02_is_user_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_is_user ATTACH PARTITION public.chat_messages_2026_02_is_user_idx;


--
-- Name: chat_messages_2026_02_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.chat_messages_partitioned_pkey ATTACH PARTITION public.chat_messages_2026_02_pkey;


--
-- Name: chat_messages_2026_02_role_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_role ATTACH PARTITION public.chat_messages_2026_02_role_idx;


--
-- Name: chat_messages_2026_02_session_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_session_id ATTACH PARTITION public.chat_messages_2026_02_session_id_idx;


--
-- Name: chat_messages_2026_02_target_agent_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_target_agent ATTACH PARTITION public.chat_messages_2026_02_target_agent_idx;


--
-- Name: chat_messages_2026_02_thread_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_thread ATTACH PARTITION public.chat_messages_2026_02_thread_id_created_at_idx;


--
-- Name: chat_messages_2026_02_user_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_user ATTACH PARTITION public.chat_messages_2026_02_user_id_idx;


--
-- Name: chat_messages_2026_03_agent_name_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_agent ATTACH PARTITION public.chat_messages_2026_03_agent_name_idx;


--
-- Name: chat_messages_2026_03_is_user_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_is_user ATTACH PARTITION public.chat_messages_2026_03_is_user_idx;


--
-- Name: chat_messages_2026_03_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.chat_messages_partitioned_pkey ATTACH PARTITION public.chat_messages_2026_03_pkey;


--
-- Name: chat_messages_2026_03_role_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_role ATTACH PARTITION public.chat_messages_2026_03_role_idx;


--
-- Name: chat_messages_2026_03_session_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_session_id ATTACH PARTITION public.chat_messages_2026_03_session_id_idx;


--
-- Name: chat_messages_2026_03_target_agent_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_target_agent ATTACH PARTITION public.chat_messages_2026_03_target_agent_idx;


--
-- Name: chat_messages_2026_03_thread_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_thread ATTACH PARTITION public.chat_messages_2026_03_thread_id_created_at_idx;


--
-- Name: chat_messages_2026_03_user_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_user ATTACH PARTITION public.chat_messages_2026_03_user_id_idx;


--
-- Name: chat_messages_2026_04_agent_name_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_agent ATTACH PARTITION public.chat_messages_2026_04_agent_name_idx;


--
-- Name: chat_messages_2026_04_is_user_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_is_user ATTACH PARTITION public.chat_messages_2026_04_is_user_idx;


--
-- Name: chat_messages_2026_04_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.chat_messages_partitioned_pkey ATTACH PARTITION public.chat_messages_2026_04_pkey;


--
-- Name: chat_messages_2026_04_role_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_role ATTACH PARTITION public.chat_messages_2026_04_role_idx;


--
-- Name: chat_messages_2026_04_session_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_session_id ATTACH PARTITION public.chat_messages_2026_04_session_id_idx;


--
-- Name: chat_messages_2026_04_target_agent_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_target_agent ATTACH PARTITION public.chat_messages_2026_04_target_agent_idx;


--
-- Name: chat_messages_2026_04_thread_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_thread ATTACH PARTITION public.chat_messages_2026_04_thread_id_created_at_idx;


--
-- Name: chat_messages_2026_04_user_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_user ATTACH PARTITION public.chat_messages_2026_04_user_id_idx;


--
-- Name: chat_messages_2026_05_agent_name_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_agent ATTACH PARTITION public.chat_messages_2026_05_agent_name_idx;


--
-- Name: chat_messages_2026_05_is_user_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_is_user ATTACH PARTITION public.chat_messages_2026_05_is_user_idx;


--
-- Name: chat_messages_2026_05_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.chat_messages_partitioned_pkey ATTACH PARTITION public.chat_messages_2026_05_pkey;


--
-- Name: chat_messages_2026_05_role_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_role ATTACH PARTITION public.chat_messages_2026_05_role_idx;


--
-- Name: chat_messages_2026_05_session_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_session_id ATTACH PARTITION public.chat_messages_2026_05_session_id_idx;


--
-- Name: chat_messages_2026_05_target_agent_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_target_agent ATTACH PARTITION public.chat_messages_2026_05_target_agent_idx;


--
-- Name: chat_messages_2026_05_thread_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_thread ATTACH PARTITION public.chat_messages_2026_05_thread_id_created_at_idx;


--
-- Name: chat_messages_2026_05_user_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_user ATTACH PARTITION public.chat_messages_2026_05_user_id_idx;


--
-- Name: chat_messages_2026_06_agent_name_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_agent ATTACH PARTITION public.chat_messages_2026_06_agent_name_idx;


--
-- Name: chat_messages_2026_06_is_user_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_is_user ATTACH PARTITION public.chat_messages_2026_06_is_user_idx;


--
-- Name: chat_messages_2026_06_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.chat_messages_partitioned_pkey ATTACH PARTITION public.chat_messages_2026_06_pkey;


--
-- Name: chat_messages_2026_06_role_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_role ATTACH PARTITION public.chat_messages_2026_06_role_idx;


--
-- Name: chat_messages_2026_06_session_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_session_id ATTACH PARTITION public.chat_messages_2026_06_session_id_idx;


--
-- Name: chat_messages_2026_06_target_agent_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_target_agent ATTACH PARTITION public.chat_messages_2026_06_target_agent_idx;


--
-- Name: chat_messages_2026_06_thread_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_thread ATTACH PARTITION public.chat_messages_2026_06_thread_id_created_at_idx;


--
-- Name: chat_messages_2026_06_user_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_user ATTACH PARTITION public.chat_messages_2026_06_user_id_idx;


--
-- Name: chat_messages_2026_07_agent_name_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_agent ATTACH PARTITION public.chat_messages_2026_07_agent_name_idx;


--
-- Name: chat_messages_2026_07_is_user_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_is_user ATTACH PARTITION public.chat_messages_2026_07_is_user_idx;


--
-- Name: chat_messages_2026_07_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.chat_messages_partitioned_pkey ATTACH PARTITION public.chat_messages_2026_07_pkey;


--
-- Name: chat_messages_2026_07_role_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_role ATTACH PARTITION public.chat_messages_2026_07_role_idx;


--
-- Name: chat_messages_2026_07_session_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_session_id ATTACH PARTITION public.chat_messages_2026_07_session_id_idx;


--
-- Name: chat_messages_2026_07_target_agent_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_target_agent ATTACH PARTITION public.chat_messages_2026_07_target_agent_idx;


--
-- Name: chat_messages_2026_07_thread_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_thread ATTACH PARTITION public.chat_messages_2026_07_thread_id_created_at_idx;


--
-- Name: chat_messages_2026_07_user_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_user ATTACH PARTITION public.chat_messages_2026_07_user_id_idx;


--
-- Name: chat_messages_2026_08_agent_name_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_agent ATTACH PARTITION public.chat_messages_2026_08_agent_name_idx;


--
-- Name: chat_messages_2026_08_is_user_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_is_user ATTACH PARTITION public.chat_messages_2026_08_is_user_idx;


--
-- Name: chat_messages_2026_08_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.chat_messages_partitioned_pkey ATTACH PARTITION public.chat_messages_2026_08_pkey;


--
-- Name: chat_messages_2026_08_role_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_role ATTACH PARTITION public.chat_messages_2026_08_role_idx;


--
-- Name: chat_messages_2026_08_session_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_session_id ATTACH PARTITION public.chat_messages_2026_08_session_id_idx;


--
-- Name: chat_messages_2026_08_target_agent_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_target_agent ATTACH PARTITION public.chat_messages_2026_08_target_agent_idx;


--
-- Name: chat_messages_2026_08_thread_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_thread ATTACH PARTITION public.chat_messages_2026_08_thread_id_created_at_idx;


--
-- Name: chat_messages_2026_08_user_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_user ATTACH PARTITION public.chat_messages_2026_08_user_id_idx;


--
-- Name: chat_messages_2026_09_agent_name_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_agent ATTACH PARTITION public.chat_messages_2026_09_agent_name_idx;


--
-- Name: chat_messages_2026_09_is_user_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_is_user ATTACH PARTITION public.chat_messages_2026_09_is_user_idx;


--
-- Name: chat_messages_2026_09_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.chat_messages_partitioned_pkey ATTACH PARTITION public.chat_messages_2026_09_pkey;


--
-- Name: chat_messages_2026_09_role_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_role ATTACH PARTITION public.chat_messages_2026_09_role_idx;


--
-- Name: chat_messages_2026_09_session_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_session_id ATTACH PARTITION public.chat_messages_2026_09_session_id_idx;


--
-- Name: chat_messages_2026_09_target_agent_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_messages_target_agent ATTACH PARTITION public.chat_messages_2026_09_target_agent_idx;


--
-- Name: chat_messages_2026_09_thread_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_thread ATTACH PARTITION public.chat_messages_2026_09_thread_id_created_at_idx;


--
-- Name: chat_messages_2026_09_user_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_chat_msg_part_user ATTACH PARTITION public.chat_messages_2026_09_user_id_idx;


--
-- Name: food_culture_vector trg_culture_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_culture_updated_at BEFORE UPDATE ON public.food_culture_vector FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: recipes_vector trg_recipes_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_recipes_updated_at BEFORE UPDATE ON public.recipes_vector FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: restaurant_reviews_vector trg_reviews_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_reviews_updated_at BEFORE UPDATE ON public.restaurant_reviews_vector FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: user_food_preferences_vector trg_user_pref_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_user_pref_updated_at BEFORE UPDATE ON public.user_food_preferences_vector FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: attraction_highlights fk1xfm66h16d9kdi3jt531yq1yg; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.attraction_highlights
    ADD CONSTRAINT fk1xfm66h16d9kdi3jt531yq1yg FOREIGN KEY (attraction_id) REFERENCES public.tourist_attractions(id);


--
-- Name: user_viewed_attractions fk3asulklu5xjxjoa8yj4sokq6w; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_viewed_attractions
    ADD CONSTRAINT fk3asulklu5xjxjoa8yj4sokq6w FOREIGN KEY (profile_id) REFERENCES public.user_profiles(id);


--
-- Name: attraction_tags fk6qkxqpa03dv5badi42x96nlo1; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.attraction_tags
    ADD CONSTRAINT fk6qkxqpa03dv5badi42x96nlo1 FOREIGN KEY (attraction_id) REFERENCES public.tourist_attractions(id);


--
-- Name: user_preferred_cities fkcpvq62c8av23mgbxaumnre1jg; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_preferred_cities
    ADD CONSTRAINT fkcpvq62c8av23mgbxaumnre1jg FOREIGN KEY (profile_id) REFERENCES public.user_profiles(id);


--
-- Name: user_favorited_attractions fkgaaacrlacxa8qkeus1cxn5cpy; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_favorited_attractions
    ADD CONSTRAINT fkgaaacrlacxa8qkeus1cxn5cpy FOREIGN KEY (profile_id) REFERENCES public.user_profiles(id);


--
-- Name: user_preferred_tags fkgwxla4828s1r3i3piroehkc1q; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_preferred_tags
    ADD CONSTRAINT fkgwxla4828s1r3i3piroehkc1q FOREIGN KEY (profile_id) REFERENCES public.user_profiles(id);


--
-- Name: user_preferred_levels fkk7o0orxmou367reswwdj8557h; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_preferred_levels
    ADD CONSTRAINT fkk7o0orxmou367reswwdj8557h FOREIGN KEY (profile_id) REFERENCES public.user_profiles(id);


--
-- Name: travel_note_chunks travel_note_chunks_note_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.travel_note_chunks
    ADD CONSTRAINT travel_note_chunks_note_id_fkey FOREIGN KEY (note_id) REFERENCES public.user_travel_notes(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--

\unrestrict gqxfkF8wPGILBr9f4Rb3afKGmgQoCgbHm8YamZFBnIJSA3EprcmseYTwboOvjPE

