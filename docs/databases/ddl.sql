CREATE TABLE users (
                       id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户唯一标识',
                       username VARCHAR(255) NOT NULL UNIQUE COMMENT '用户名，唯一',
                       password VARCHAR(255) NOT NULL COMMENT '加密后的密码',
                       role ENUM('GUEST', 'USER', 'ADMIN') NOT NULL DEFAULT 'USER' COMMENT '用户角色',
                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                       updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                       INDEX idx_username (username) COMMENT '用户名索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

CREATE TABLE file_upload (
                             id           BIGINT           NOT NULL AUTO_INCREMENT COMMENT '主键',
                             file_md5     VARCHAR(32)      NOT NULL COMMENT '文件 MD5',
                             file_name    VARCHAR(255)     NOT NULL COMMENT '文件名称',
                             total_size   BIGINT           NOT NULL COMMENT '文件大小',
                             status       TINYINT          NOT NULL DEFAULT 0 COMMENT '上传状态：0上传中 1已完成 2合并中',
                             user_id      VARCHAR(64)      NOT NULL COMMENT '用户 ID',
                             paper_title  VARCHAR(255)     DEFAULT NULL COMMENT '论文标题',
                             authors      VARCHAR(1000)    DEFAULT NULL COMMENT '论文作者',
                             publication_year INT          DEFAULT NULL COMMENT '发表年份',
                             venue        VARCHAR(255)     DEFAULT NULL COMMENT '发表会议或期刊',
                             abstract_text TEXT            DEFAULT NULL COMMENT '论文摘要',
                             doi          VARCHAR(255)     DEFAULT NULL COMMENT 'DOI',
                             arxiv_id     VARCHAR(255)     DEFAULT NULL COMMENT 'arXiv ID',
                             retrieval_indexed_token_count BIGINT DEFAULT NULL COMMENT '词法索引 Token 数',
                             retrieval_indexed_location_count INT DEFAULT NULL COMMENT '词法索引 Location 数',
                             vectorization_status VARCHAR(32) DEFAULT 'PENDING' COMMENT '解析/向量化流水线状态',
                             vectorization_error_message VARCHAR(1000) DEFAULT NULL COMMENT '解析/向量化错误信息',
                             created_at   TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                             merged_at    TIMESTAMP        NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '合并时间',
                             PRIMARY KEY (id),
                             UNIQUE KEY uk_md5_user (file_md5, user_id),
                             INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件上传记录';

CREATE TABLE IF NOT EXISTS paper_publications (
    paper_id VARCHAR(32) NOT NULL COMMENT '全局发布的论文 ID',
    published_by VARCHAR(64) NOT NULL COMMENT '发布管理员 ID',
    published_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
    PRIMARY KEY (paper_id),
    INDEX idx_paper_publications_published_by (published_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员发布的全局论文';

CREATE TABLE chunk_info (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '分块记录唯一标识',
                            file_md5 VARCHAR(32) NOT NULL COMMENT '关联的文件MD5值',
                            chunk_index INT NOT NULL COMMENT '分块序号',
                            chunk_md5 VARCHAR(32) NOT NULL COMMENT '分块的MD5值',
                            storage_path VARCHAR(255) NOT NULL COMMENT '分块在存储系统中的路径',
                            UNIQUE KEY uk_file_md5_chunk_index (file_md5, chunk_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件分块信息表';

-- PaperLoom Reading Model 持久化闭环
CREATE TABLE IF NOT EXISTS paper_parser_artifacts (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    paper_id VARCHAR(32) NOT NULL COMMENT '论文 ID，对应 file_upload.file_md5',
    artifact_type VARCHAR(64) NOT NULL COMMENT 'Parser artifact 类型',
    parser_name VARCHAR(64) DEFAULT NULL COMMENT 'Parser 名称',
    parser_version VARCHAR(64) DEFAULT NULL COMMENT 'Parser 版本',
    object_key VARCHAR(500) NOT NULL COMMENT '对象存储 key',
    content_type VARCHAR(128) DEFAULT NULL COMMENT '内容类型',
    size_bytes BIGINT DEFAULT NULL COMMENT '文件大小',
    sha256 VARCHAR(64) DEFAULT NULL COMMENT '内容 SHA-256',
    user_id VARCHAR(64) DEFAULT NULL COMMENT '上传用户 ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_parser_artifact_paper (paper_id),
    INDEX idx_parser_artifact_type (artifact_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='论文 parser 原始产物';

CREATE TABLE IF NOT EXISTS paper_reading_models (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    paper_id VARCHAR(32) NOT NULL COMMENT '论文 ID，对应 file_upload.file_md5',
    model_version VARCHAR(64) NOT NULL COMMENT 'Reading Model 版本',
    model_status VARCHAR(64) NOT NULL COMMENT 'Reading Model 状态',
    is_current BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否当前版本',
    parser_name VARCHAR(64) DEFAULT NULL COMMENT 'Parser 名称',
    parser_version VARCHAR(64) DEFAULT NULL COMMENT 'Parser 版本',
    page_count INT DEFAULT NULL COMMENT '物理页数',
    readable_page_count INT DEFAULT NULL COMMENT '有可读文本的页数',
    readable_char_count INT DEFAULT NULL COMMENT '可读字符数',
    failure_reason VARCHAR(1000) DEFAULT NULL COMMENT '构建失败原因',
    retrieval_index_status VARCHAR(32) DEFAULT 'PENDING' COMMENT 'PENDING/BUILDING/READY/REBUILDING/FAILED',
    retrieval_index_job_id VARCHAR(64) DEFAULT NULL COMMENT '当前索引任务 ID',
    retrieval_index_contract VARCHAR(255) DEFAULT NULL COMMENT '词法检索索引合同',
    retrieval_indexed_location_count INT DEFAULT NULL COMMENT '已索引位置数量',
    retrieval_index_started_at TIMESTAMP NULL DEFAULT NULL COMMENT '索引任务开始时间',
    retrieval_indexed_at TIMESTAMP NULL DEFAULT NULL COMMENT '索引完成时间',
    retrieval_index_error_type VARCHAR(128) DEFAULT NULL COMMENT '索引错误类型',
    retrieval_index_error_message VARCHAR(1000) DEFAULT NULL COMMENT '索引错误信息',
    diagnostics_json TEXT DEFAULT NULL COMMENT '构建诊断 JSON',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_paper_reading_models_paper_current (paper_id, is_current),
    INDEX idx_paper_reading_models_paper_version (paper_id, model_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='论文 Reading Model 版本';

CREATE TABLE IF NOT EXISTS paper_retrieval_control (
    control_name VARCHAR(64) NOT NULL COMMENT '维护任务名称',
    full_rebuild_status VARCHAR(32) NOT NULL COMMENT 'IDLE/RUNNING/SUCCEEDED/FAILED',
    job_id VARCHAR(64) DEFAULT NULL COMMENT '当前全量任务 ID',
    requested_by VARCHAR(64) DEFAULT NULL COMMENT '发起管理员 ID',
    snapshot_paper_count INT NOT NULL DEFAULT 0 COMMENT '任务论文快照数量',
    completed_paper_count INT NOT NULL DEFAULT 0 COMMENT '成功数量',
    failed_paper_count INT NOT NULL DEFAULT 0 COMMENT '失败数量',
    started_at TIMESTAMP NULL DEFAULT NULL COMMENT '开始时间',
    finished_at TIMESTAMP NULL DEFAULT NULL COMMENT '结束时间',
    last_error VARCHAR(1000) DEFAULT NULL COMMENT '最近错误',
    active_index_contract VARCHAR(255) DEFAULT NULL COMMENT '当前词法检索索引合同',
    lexical_average_document_length DOUBLE DEFAULT NULL COMMENT 'BM25 平均文档长度快照',
    PRIMARY KEY (control_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='论文检索全量维护状态';

INSERT IGNORE INTO paper_retrieval_control (control_name, full_rebuild_status)
VALUES ('QDRANT_FULL_REBUILD', 'IDLE');

CREATE TABLE IF NOT EXISTS paper_pages (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    paper_id VARCHAR(32) NOT NULL COMMENT '论文 ID，对应 file_upload.file_md5',
    model_version VARCHAR(64) NOT NULL COMMENT 'Reading Model 版本',
    page_number INT NOT NULL COMMENT '1-based PDF 页码',
    page_text TEXT NOT NULL COMMENT '页面可读文本，文本页可为空字符串',
    text_hash VARCHAR(64) NOT NULL COMMENT '页面文本 SHA-256',
    char_count INT NOT NULL COMMENT '页面文本字符数',
    text_status VARCHAR(32) NOT NULL COMMENT 'READABLE/TEXTLESS/PARSER_MISSING',
    source_span_json LONGTEXT NOT NULL COMMENT '页面 source span JSON',
    parser_name VARCHAR(64) DEFAULT NULL COMMENT 'Parser 名称',
    parser_version VARCHAR(64) DEFAULT NULL COMMENT 'Parser 版本',
    user_id VARCHAR(64) NOT NULL COMMENT '上传用户 ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_paper_pages_paper_model_page (paper_id, model_version, page_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='论文物理页';

CREATE TABLE IF NOT EXISTS paper_sections (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    paper_id VARCHAR(32) NOT NULL COMMENT '论文 ID，对应 file_upload.file_md5',
    model_version VARCHAR(64) NOT NULL COMMENT 'Reading Model 版本',
    section_id VARCHAR(96) NOT NULL COMMENT '产品侧 section ID',
    section_title VARCHAR(500) NOT NULL COMMENT '章节标题',
    section_level INT DEFAULT NULL COMMENT '章节层级',
    page_number_from INT NOT NULL COMMENT '起始页码',
    page_number_to INT NOT NULL COMMENT '结束页码',
    reading_order_from INT DEFAULT NULL COMMENT '起始 reading order',
    reading_order_to INT DEFAULT NULL COMMENT '结束 reading order',
    display_order INT NOT NULL COMMENT '展示顺序',
    section_text TEXT NOT NULL COMMENT '章节聚合文本',
    text_hash VARCHAR(64) NOT NULL COMMENT '章节文本 SHA-256',
    char_count INT NOT NULL COMMENT '章节文本字符数',
    source_span_json LONGTEXT NOT NULL COMMENT '章节 source span JSON',
    parser_name VARCHAR(64) DEFAULT NULL COMMENT 'Parser 名称',
    parser_version VARCHAR(64) DEFAULT NULL COMMENT 'Parser 版本',
    user_id VARCHAR(64) NOT NULL COMMENT '上传用户 ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_paper_sections_paper_model_page (paper_id, model_version, page_number_from),
    INDEX idx_paper_sections_paper_model_section (paper_id, model_version, section_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='论文章节聚合块';

CREATE TABLE IF NOT EXISTS paper_passages (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    passage_ref VARCHAR(96) NOT NULL COMMENT '对外 opaque Passage ref',
    paper_id VARCHAR(32) NOT NULL COMMENT '论文 ID，对应 file_upload.file_md5',
    model_version VARCHAR(64) NOT NULL COMMENT 'Reading Model 版本',
    parent_section_id VARCHAR(96) DEFAULT NULL COMMENT '所属 Section ID',
    parent_section_ref VARCHAR(96) DEFAULT NULL COMMENT '所属 Section Location ref',
    section_title VARCHAR(500) DEFAULT NULL COMMENT '所属 Section 标题',
    page_number_from INT NOT NULL COMMENT '起始页码',
    page_number_to INT NOT NULL COMMENT '结束页码',
    reading_order_from INT NOT NULL COMMENT '起始阅读顺序',
    reading_order_to INT NOT NULL COMMENT '结束阅读顺序',
    document_ordinal INT NOT NULL COMMENT '全文顺序',
    section_ordinal INT DEFAULT NULL COMMENT '章节内顺序',
    content_text LONGTEXT NOT NULL COMMENT '规范正文',
    index_text LONGTEXT NOT NULL COMMENT '检索正文，允许附加章节标题',
    content_hash CHAR(64) NOT NULL COMMENT '规范正文 SHA-256',
    estimated_token_count INT NOT NULL COMMENT '估算 Token 数',
    source_span_json LONGTEXT NOT NULL COMMENT 'Passage source span JSON',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_paper_passages_ref (passage_ref),
    UNIQUE KEY uk_paper_passages_document_ordinal (paper_id, model_version, document_ordinal),
    INDEX idx_paper_passages_section_ordinal (paper_id, model_version, parent_section_id, section_ordinal),
    INDEX idx_paper_passages_section_order (paper_id, model_version, parent_section_id, reading_order_from),
    INDEX idx_paper_passages_page_range (paper_id, model_version, page_number_from, page_number_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='论文确定性 Passage 检索层';

CREATE TABLE IF NOT EXISTS paper_locations (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    location_ref VARCHAR(96) NOT NULL COMMENT '对外 opaque location ref',
    paper_id VARCHAR(32) NOT NULL COMMENT '论文 ID，对应 file_upload.file_md5',
    model_version VARCHAR(64) NOT NULL COMMENT 'Reading Model 版本',
    location_type VARCHAR(32) NOT NULL COMMENT 'PAGE/SECTION/PASSAGE/TABLE/FIGURE',
    page_number INT NOT NULL COMMENT '起始页码',
    page_end_number INT DEFAULT NULL COMMENT '结束页码',
    section_title VARCHAR(500) DEFAULT NULL COMMENT '章节标题',
    source_object_id VARCHAR(96) DEFAULT NULL COMMENT '目标对象 ID：SECTION 为 sectionId，PASSAGE 为 passageRef，TABLE/FIGURE 为 readingElementId',
    display_order INT DEFAULT NULL COMMENT '展示顺序',
    source_span_json LONGTEXT NOT NULL COMMENT 'location source span JSON',
    content_kind VARCHAR(64) NOT NULL COMMENT 'PAGE_TEXT/PAGE_SURFACE/SECTION_TEXT/PASSAGE_TEXT/TABLE/FIGURE',
    user_id VARCHAR(64) NOT NULL COMMENT '上传用户 ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_paper_locations_ref (location_ref),
    INDEX idx_paper_locations_ref (location_ref),
    INDEX idx_paper_locations_paper_model (paper_id, model_version),
    INDEX idx_paper_locations_paper_model_page (paper_id, model_version, page_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='论文阅读导航坐标';

CREATE TABLE IF NOT EXISTS paper_reading_elements (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    paper_id VARCHAR(32) NOT NULL COMMENT '论文 ID，对应 file_upload.file_md5',
    model_version VARCHAR(64) NOT NULL COMMENT 'Reading Model 版本',
    reading_element_id VARCHAR(96) NOT NULL COMMENT '产品侧 reading element ID',
    content_list_index INT DEFAULT NULL COMMENT 'MinerU content_list 顺序',
    parser_element_id VARCHAR(96) DEFAULT NULL COMMENT 'Parser 元素 ID',
    source_object_id VARCHAR(96) DEFAULT NULL COMMENT 'Parser typed source ID',
    element_type VARCHAR(32) NOT NULL COMMENT 'TITLE/HEADING/PARAGRAPH/TABLE/IMAGE/CHART/FORMULA 等',
    page_number INT DEFAULT NULL COMMENT '1-based PDF 页码',
    reading_order INT DEFAULT NULL COMMENT 'Parser reading order',
    section_title VARCHAR(500) DEFAULT NULL COMMENT 'Parser section title',
    parent_reading_element_id VARCHAR(96) DEFAULT NULL COMMENT '父 reading element ID',
    attachment_role VARCHAR(64) DEFAULT NULL COMMENT '附件角色，如 PANEL_LABEL/TABLE_CAPTION',
    association_status VARCHAR(32) NOT NULL COMMENT 'SELF/ATTACHED/AMBIGUOUS/UNATTACHED',
    location_ref VARCHAR(96) DEFAULT NULL COMMENT '自身结构 location ref',
    location_type VARCHAR(32) DEFAULT NULL COMMENT '自身结构 location type',
    location_not_created_reason VARCHAR(64) DEFAULT NULL COMMENT '未创建自身 location 的原因',
    caption_text TEXT DEFAULT NULL COMMENT 'caption 文本',
    body_text TEXT DEFAULT NULL COMMENT '正文/表格/公式可读文本',
    searchable_text TEXT DEFAULT NULL COMMENT '可检索文本',
    caption_source VARCHAR(64) DEFAULT NULL COMMENT 'caption 来源',
    parser_image_path VARCHAR(500) DEFAULT NULL COMMENT 'Parser img_path',
    bbox_json TEXT DEFAULT NULL COMMENT 'bbox JSON',
    source_span_json TEXT DEFAULT NULL COMMENT 'element source span JSON',
    structured_payload_json TEXT DEFAULT NULL COMMENT '结构化 parser payload JSON',
    raw_attributes_json TEXT DEFAULT NULL COMMENT 'Parser 原始属性 JSON',
    parser_name VARCHAR(64) DEFAULT NULL COMMENT 'Parser 名称',
    parser_version VARCHAR(64) DEFAULT NULL COMMENT 'Parser 版本',
    user_id VARCHAR(64) NOT NULL COMMENT '上传用户 ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_paper_reading_elements_paper_model_page (paper_id, model_version, page_number),
    INDEX idx_paper_reading_elements_paper_model_type (paper_id, model_version, element_type),
    INDEX idx_paper_reading_elements_source (paper_id, model_version, source_object_id),
    INDEX idx_paper_reading_elements_parent (paper_id, model_version, parent_reading_element_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='论文 Reading Model 元素库存';

CREATE TABLE IF NOT EXISTS paper_source_quotes (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    source_quote_ref VARCHAR(96) NOT NULL COMMENT 'opaque Source Quote ref',
    paper_id VARCHAR(32) NOT NULL COMMENT '论文 ID，对应 file_upload.file_md5',
    model_version VARCHAR(64) NOT NULL COMMENT 'Reading Model 版本',
    location_ref VARCHAR(96) NOT NULL COMMENT '输入 reading location ref',
    location_type VARCHAR(32) NOT NULL COMMENT 'PAGE/SECTION/TABLE/FIGURE',
    page_number INT DEFAULT NULL COMMENT '起始页码',
    page_end_number INT DEFAULT NULL COMMENT '结束页码',
    section_title VARCHAR(500) DEFAULT NULL COMMENT '章节标题',
    content_kind VARCHAR(64) NOT NULL COMMENT 'TEXT/TABLE/FIGURE_CAPTION',
    content TEXT NOT NULL COMMENT 'Source Quote 原文内容',
    content_hash VARCHAR(64) NOT NULL COMMENT 'Source Quote 内容 hash',
    split_policy_version VARCHAR(64) NOT NULL COMMENT '内部 split policy 版本',
    split_index INT NOT NULL COMMENT '同一 location 下的 split 序号',
    source_span_json LONGTEXT NOT NULL COMMENT 'source span JSON',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_paper_source_quotes_ref (source_quote_ref),
    UNIQUE KEY uk_paper_source_quotes_idempotency (
        paper_id, model_version, location_ref, split_policy_version, split_index, content_hash
    ),
    INDEX idx_paper_source_quotes_paper_model (paper_id, model_version),
    INDEX idx_paper_source_quotes_location (location_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='论文 Source Quote';

CREATE TABLE IF NOT EXISTS conversation_source_quotes (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    conversation_id VARCHAR(64) NOT NULL COMMENT '会话 ID',
    source_quote_ref VARCHAR(96) NOT NULL COMMENT 'Source Quote ref',
    first_seen_turn_id VARCHAR(64) NOT NULL COMMENT '首次进入会话的 generation/turn ID',
    user_id VARCHAR(64) DEFAULT NULL COMMENT '用户 ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_source_quotes_conversation_ref (conversation_id, source_quote_ref),
    INDEX idx_conversation_source_quotes_ref (source_quote_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话 Source Quote 引用注册表';

-- Conversations revision metadata for answer retry/regenerate.
-- Existing installations should apply docs/databases/migrations/2026-07-26-conversation-answer-revisions.sql.
ALTER TABLE conversations
    ADD COLUMN generation_id VARCHAR(64) NULL,
    ADD COLUMN answer_slot_id BIGINT NULL,
    ADD COLUMN answer_revision INT NOT NULL DEFAULT 1,
    ADD COLUMN current_revision BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN forked_from_conversation_record_id BIGINT NULL,
    ADD COLUMN retry_kind VARCHAR(64) NULL,
    ADD COLUMN retry_reason VARCHAR(255) NULL,
    ADD COLUMN retry_of_generation_id VARCHAR(64) NULL;

CREATE INDEX idx_conversations_answer_slot
    ON conversations(answer_slot_id, answer_revision);

CREATE INDEX idx_conversations_current_revision
    ON conversations(user_id, conversation_id, current_revision, timestamp);

CREATE INDEX idx_conversations_generation
    ON conversations(generation_id);

CREATE TABLE IF NOT EXISTS paper_visual_assets (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    paper_id VARCHAR(32) NOT NULL COMMENT '论文 ID，对应 file_upload.file_md5',
    asset_type VARCHAR(64) NOT NULL COMMENT 'PAGE_SCREENSHOT/TABLE_CROP/FIGURE_CROP/CHART_CROP/PARSER_IMAGE',
    asset_status VARCHAR(64) NOT NULL COMMENT 'AVAILABLE/MISSING_IN_ARTIFACT/STORAGE_FAILED/RENDER_FAILED',
    model_version VARCHAR(64) DEFAULT NULL COMMENT 'Reading Model 版本',
    page_number INT DEFAULT NULL COMMENT '1-based PDF 页码',
    source_object_id VARCHAR(96) DEFAULT NULL COMMENT 'Parser source object ID',
    reading_element_id VARCHAR(96) DEFAULT NULL COMMENT '关联 reading element ID',
    parser_element_id VARCHAR(96) DEFAULT NULL COMMENT 'Parser 元素 ID',
    parser_image_path VARCHAR(500) DEFAULT NULL COMMENT 'Parser img_path',
    bbox_json TEXT DEFAULT NULL COMMENT 'bbox JSON',
    object_key VARCHAR(500) DEFAULT NULL COMMENT '对象存储 key；失败/缺失时为空',
    content_type VARCHAR(128) DEFAULT NULL COMMENT '内容类型',
    width_px INT DEFAULT NULL COMMENT '图片宽度',
    height_px INT DEFAULT NULL COMMENT '图片高度',
    sha256 VARCHAR(64) DEFAULT NULL COMMENT '图片 SHA-256',
    failure_reason VARCHAR(1000) DEFAULT NULL COMMENT '缺失或失败原因',
    user_id VARCHAR(64) DEFAULT NULL COMMENT '上传用户 ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_visual_asset_paper (paper_id),
    INDEX idx_visual_asset_source (paper_id, source_object_id),
    INDEX idx_visual_asset_reading_element (paper_id, reading_element_id),
    INDEX idx_visual_asset_page (paper_id, page_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='论文视觉资产或视觉缺口';

CREATE TABLE model_provider_configs (
                                        id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '模型配置主键',
                                        config_scope VARCHAR(32) NOT NULL COMMENT '作用域：llm',
                                        provider_code VARCHAR(64) NOT NULL COMMENT 'provider 标识',
                                        display_name VARCHAR(128) NOT NULL COMMENT '展示名称',
                                        api_style VARCHAR(64) NOT NULL COMMENT '协议风格',
                                        api_base_url VARCHAR(512) NOT NULL COMMENT 'API 基础地址',
                                        model_name VARCHAR(255) NOT NULL COMMENT '模型名称',
                                        api_key_ciphertext VARCHAR(2048) DEFAULT NULL COMMENT '加密后的 API Key',
                                        enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用',
                                        active BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否当前激活',
                                        updated_by VARCHAR(255) NOT NULL COMMENT '最后更新人',
                                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                        UNIQUE KEY uk_model_provider_scope_code (config_scope, provider_code),
                                        KEY idx_model_provider_scope (config_scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行时模型 Provider 配置表';

-- 创建用户 Token 变动记录表
CREATE TABLE IF NOT EXISTS `user_token_record` (
                               `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
                               `user_id` VARCHAR(64) NOT NULL COMMENT '用户 ID',
                               `record_date` DATE NOT NULL COMMENT '记录日期（按天统计）',
                               `token_type` VARCHAR(20) NOT NULL COMMENT 'Token 类型：LLM/EMBEDDING',
                               `change_type` VARCHAR(20) NOT NULL COMMENT '变动类型：INCREASE/CONSUME',
                                `request_count` BIGINT NOT NULL DEFAULT 0 COMMENT '请求次数（一次对话可能包含多次 API 请求）',
                               `amount` BIGINT NOT NULL COMMENT '变动数量',
                               `balance_before` BIGINT DEFAULT NULL COMMENT '变动前的余额',
                               `balance_after` BIGINT DEFAULT NULL COMMENT '变动后的余额',
                               `reason` VARCHAR(500) DEFAULT NULL COMMENT '变动原因描述',
                               `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注信息（操作来源、对话 ID 等）',
                               `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                               PRIMARY KEY (`id`),
                               INDEX `idx_user_date` (`user_id`, `record_date`),
                               INDEX `idx_record_date` (`record_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户 Token 变动记录表';


CREATE TABLE IF NOT EXISTS `user_daily_chat_count` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `user_id` VARCHAR(64) NOT NULL COMMENT '用户 ID',
    `record_date` DATE NOT NULL COMMENT '记录日期',
    `chat_request_count` BIGINT(20) NOT NULL DEFAULT 0 COMMENT '对话请求次数',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_date` (`user_id`, `record_date`) COMMENT '用户 + 日期唯一索引',
    INDEX `idx_record_date` (`record_date`) COMMENT '按日期查询索引'
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户每日对话次数记录表';
