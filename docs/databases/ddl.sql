CREATE TABLE users (
                       id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户唯一标识',
                       username VARCHAR(255) NOT NULL UNIQUE COMMENT '用户名，唯一',
                       password VARCHAR(255) NOT NULL COMMENT '加密后的密码',
                       role ENUM('USER', 'ADMIN') NOT NULL DEFAULT 'USER' COMMENT '用户角色',
                       org_tags VARCHAR(255) DEFAULT NULL COMMENT '用户所属组织标签，多个用逗号分隔',
                       primary_org VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '用户主组织标签',
                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                       updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                       INDEX idx_username (username) COMMENT '用户名索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';
CREATE TABLE organization_tags (
                                   tag_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY COMMENT '标签唯一标识',
                                   name VARCHAR(100) NOT NULL COMMENT '标签名称',
                                   description TEXT COMMENT '描述',
                                   parent_tag VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '父标签ID',
                                   upload_max_size_bytes BIGINT DEFAULT NULL COMMENT '非管理员上传文件大小上限，单位字节',
                                   created_by BIGINT NOT NULL COMMENT '创建者ID',
                                   created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                   updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                   FOREIGN KEY (parent_tag) REFERENCES organization_tags(tag_id) ON DELETE SET NULL,
                                   FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织标签表';


CREATE TABLE file_upload (
                             id           BIGINT           NOT NULL AUTO_INCREMENT COMMENT '主键',
                             file_md5     VARCHAR(32)      NOT NULL COMMENT '文件 MD5',
                             file_name    VARCHAR(255)     NOT NULL COMMENT '文件名称',
                             total_size   BIGINT           NOT NULL COMMENT '文件大小',
                             status       TINYINT          NOT NULL DEFAULT 0 COMMENT '上传状态：0上传中 1已完成 2合并中',
                             user_id      VARCHAR(64)      NOT NULL COMMENT '用户 ID',
                             org_tag      VARCHAR(50)      DEFAULT NULL COMMENT '组织标签',
                             is_public    BOOLEAN          NOT NULL DEFAULT FALSE COMMENT '是否公开',
                             paper_title  VARCHAR(255)     DEFAULT NULL COMMENT '论文标题',
                             authors      VARCHAR(1000)    DEFAULT NULL COMMENT '论文作者',
                             publication_year INT          DEFAULT NULL COMMENT '发表年份',
                             venue        VARCHAR(255)     DEFAULT NULL COMMENT '发表会议或期刊',
                             abstract_text TEXT            DEFAULT NULL COMMENT '论文摘要',
                             doi          VARCHAR(255)     DEFAULT NULL COMMENT 'DOI',
                             arxiv_id     VARCHAR(255)     DEFAULT NULL COMMENT 'arXiv ID',
                             estimated_embedding_tokens BIGINT DEFAULT NULL COMMENT '预估 embedding token 数',
                             estimated_chunk_count INT DEFAULT NULL COMMENT '预估切片数',
                             actual_embedding_tokens BIGINT DEFAULT NULL COMMENT '实际 embedding token 数',
                             actual_chunk_count INT DEFAULT NULL COMMENT '实际切片数',
                             vectorization_status VARCHAR(32) DEFAULT 'PENDING' COMMENT '解析/向量化流水线状态',
                             vectorization_error_message VARCHAR(1000) DEFAULT NULL COMMENT '解析/向量化错误信息',
                             created_at   TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                             merged_at    TIMESTAMP        NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '合并时间',
                             PRIMARY KEY (id),
                             UNIQUE KEY uk_md5_user (file_md5, user_id),
                             INDEX idx_user (user_id),
                             INDEX idx_org_tag (org_tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件上传记录';
CREATE TABLE chunk_info (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '分块记录唯一标识',
                            file_md5 VARCHAR(32) NOT NULL COMMENT '关联的文件MD5值',
                            chunk_index INT NOT NULL COMMENT '分块序号',
                            chunk_md5 VARCHAR(32) NOT NULL COMMENT '分块的MD5值',
                            storage_path VARCHAR(255) NOT NULL COMMENT '分块在存储系统中的路径',
                            UNIQUE KEY uk_file_md5_chunk_index (file_md5, chunk_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件分块信息表';

CREATE TABLE document_vectors (
                                  vector_id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '向量记录唯一标识',
                                  file_md5 VARCHAR(32) NOT NULL COMMENT '关联的文件MD5值',
                                  chunk_id INT NOT NULL COMMENT '文本分块序号',
                                  text_content TEXT COMMENT '文本内容',
                                  page_number INT COMMENT 'PDF页码，用于引用定位',
                                  anchor_text VARCHAR(255) COMMENT '页内定位锚点文本',
                                  model_version VARCHAR(32) COMMENT '向量模型版本',
                                  user_id VARCHAR(64) NOT NULL COMMENT '上传用户ID',
                                  org_tag VARCHAR(50) COMMENT '文件所属组织标签',
                                  is_public BOOLEAN NOT NULL DEFAULT FALSE COMMENT '文件是否公开'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档向量存储表';

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
    org_tag VARCHAR(50) DEFAULT NULL COMMENT '组织标签',
    is_public BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否公开',
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
    diagnostics_json TEXT DEFAULT NULL COMMENT '构建诊断 JSON',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_paper_reading_models_paper_current (paper_id, is_current),
    INDEX idx_paper_reading_models_paper_version (paper_id, model_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='论文 Reading Model 版本';

CREATE TABLE IF NOT EXISTS paper_pages (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    paper_id VARCHAR(32) NOT NULL COMMENT '论文 ID，对应 file_upload.file_md5',
    model_version VARCHAR(64) NOT NULL COMMENT 'Reading Model 版本',
    page_number INT NOT NULL COMMENT '1-based PDF 页码',
    page_text TEXT NOT NULL COMMENT '页面可读文本，文本页可为空字符串',
    text_hash VARCHAR(64) NOT NULL COMMENT '页面文本 SHA-256',
    char_count INT NOT NULL COMMENT '页面文本字符数',
    text_status VARCHAR(32) NOT NULL COMMENT 'READABLE/TEXTLESS/PARSER_MISSING',
    source_span_json TEXT NOT NULL COMMENT '页面 source span JSON',
    parser_name VARCHAR(64) DEFAULT NULL COMMENT 'Parser 名称',
    parser_version VARCHAR(64) DEFAULT NULL COMMENT 'Parser 版本',
    user_id VARCHAR(64) NOT NULL COMMENT '上传用户 ID',
    org_tag VARCHAR(50) DEFAULT NULL COMMENT '组织标签',
    is_public BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否公开',
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
    source_span_json TEXT NOT NULL COMMENT '章节 source span JSON',
    parser_name VARCHAR(64) DEFAULT NULL COMMENT 'Parser 名称',
    parser_version VARCHAR(64) DEFAULT NULL COMMENT 'Parser 版本',
    user_id VARCHAR(64) NOT NULL COMMENT '上传用户 ID',
    org_tag VARCHAR(50) DEFAULT NULL COMMENT '组织标签',
    is_public BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否公开',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_paper_sections_paper_model_page (paper_id, model_version, page_number_from),
    INDEX idx_paper_sections_paper_model_section (paper_id, model_version, section_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='论文章节聚合块';

CREATE TABLE IF NOT EXISTS paper_locations (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    location_ref VARCHAR(96) NOT NULL COMMENT '对外 opaque location ref',
    paper_id VARCHAR(32) NOT NULL COMMENT '论文 ID，对应 file_upload.file_md5',
    model_version VARCHAR(64) NOT NULL COMMENT 'Reading Model 版本',
    location_type VARCHAR(32) NOT NULL COMMENT 'PAGE/SECTION/TABLE/FIGURE',
    page_number INT NOT NULL COMMENT '起始页码',
    page_end_number INT DEFAULT NULL COMMENT '结束页码',
    section_title VARCHAR(500) DEFAULT NULL COMMENT '章节标题',
    source_object_id VARCHAR(96) DEFAULT NULL COMMENT '目标对象 ID：SECTION 为 sectionId，TABLE/FIGURE 为 readingElementId',
    display_order INT DEFAULT NULL COMMENT '展示顺序',
    source_span_json TEXT NOT NULL COMMENT 'location source span JSON',
    content_kind VARCHAR(64) NOT NULL COMMENT 'PAGE_TEXT/PAGE_SURFACE/SECTION_TEXT/TABLE/FIGURE',
    user_id VARCHAR(64) NOT NULL COMMENT '上传用户 ID',
    org_tag VARCHAR(50) DEFAULT NULL COMMENT '组织标签',
    is_public BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否公开',
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
    org_tag VARCHAR(50) DEFAULT NULL COMMENT '组织标签',
    is_public BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否公开',
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
    source_span_json TEXT NOT NULL COMMENT 'source span JSON',
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
    org_tag VARCHAR(50) DEFAULT NULL COMMENT '组织标签',
    is_public BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否公开',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_visual_asset_paper (paper_id),
    INDEX idx_visual_asset_source (paper_id, source_object_id),
    INDEX idx_visual_asset_reading_element (paper_id, reading_element_id),
    INDEX idx_visual_asset_page (paper_id, page_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='论文视觉资产或视觉缺口';

CREATE TABLE rate_limit_configs (
                                    config_key VARCHAR(64) PRIMARY KEY COMMENT '限流配置键',
                                    single_max INT DEFAULT NULL COMMENT '单窗口最大次数',
                                    single_window_seconds BIGINT DEFAULT NULL COMMENT '单窗口秒数',
                                    minute_max BIGINT DEFAULT NULL COMMENT '分钟窗口最大值',
                                    minute_window_seconds BIGINT DEFAULT NULL COMMENT '分钟窗口秒数',
                                    day_max BIGINT DEFAULT NULL COMMENT '日窗口最大值',
                                    day_window_seconds BIGINT DEFAULT NULL COMMENT '日窗口秒数',
                                    updated_by VARCHAR(255) NOT NULL COMMENT '最后更新人',
                                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行时限流配置表';

CREATE TABLE model_provider_configs (
                                        id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '模型配置主键',
                                        config_scope VARCHAR(32) NOT NULL COMMENT '作用域：llm / embedding',
                                        provider_code VARCHAR(64) NOT NULL COMMENT 'provider 标识',
                                        display_name VARCHAR(128) NOT NULL COMMENT '展示名称',
                                        api_style VARCHAR(64) NOT NULL COMMENT '协议风格',
                                        api_base_url VARCHAR(512) NOT NULL COMMENT 'API 基础地址',
                                        model_name VARCHAR(255) NOT NULL COMMENT '模型名称',
                                        api_key_ciphertext VARCHAR(2048) DEFAULT NULL COMMENT '加密后的 API Key',
                                        embedding_dimension INT DEFAULT NULL COMMENT 'Embedding 维度',
                                        enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用',
                                        active BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否当前激活',
                                        updated_by VARCHAR(255) NOT NULL COMMENT '最后更新人',
                                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                        UNIQUE KEY uk_model_provider_scope_code (config_scope, provider_code),
                                        KEY idx_model_provider_scope (config_scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行时模型 Provider 配置表';

-- 充值套餐表
CREATE TABLE recharge_packages (
                                   id INT AUTO_INCREMENT PRIMARY KEY COMMENT '套餐 ID（自增主键）',
                                   package_name VARCHAR(128) NOT NULL COMMENT '套餐名称',
                                   package_price BIGINT NOT NULL COMMENT '套餐价格，单位分',
                                   package_desc TEXT COMMENT '套餐描述',
                                   package_benefit TEXT COMMENT '套餐权益',
                                   llm_token INT NOT NULL COMMENT 'LLM token 数量',
                                   embedding_token INT NOT NULL COMMENT 'Embedding token 数量',
                                   sort_order INT NOT NULL DEFAULT 10 COMMENT '排序顺序（数字越小越靠前）',
                                   enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用',
                                   deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已删除',
                                   created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                   updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充值套餐表';

-- 充值订单表
CREATE TABLE recharge_orders (
                                 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '订单 ID',
                                 trade_no VARCHAR(128) NOT NULL UNIQUE COMMENT '业务单号（外部系统唯一）',
                                 user_id VARCHAR(64) NOT NULL COMMENT '用户 ID（关联 users 表）',
                                 package_id INT NOT NULL COMMENT '套餐 ID（如果是自定义充值，则为 0）',
                                 amount BIGINT NOT NULL COMMENT '订单金额，单位分',
                                 llm_token INT NOT NULL COMMENT 'LLM token 数量',
                                 embedding_token INT NOT NULL COMMENT 'Embedding token 数量',
                                 wx_transaction_id VARCHAR(64) NOT NULL COMMENT '微信交易流水号',
                                 status ENUM('NOT_PAY', 'PAYING', 'SUCCEED', 'FAIL', 'CANCELLED') NOT NULL DEFAULT 'NOT_PAY' COMMENT '订单状态',
                                 description VARCHAR(255) COMMENT '订单描述',
                                 pay_time TIMESTAMP NULL COMMENT '支付成功时间',
                                 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                 updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                 INDEX idx_trade_no (trade_no),
                                 INDEX idx_user_id (user_id),
                                 INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充值订单表';

-- 初始化充值套餐数据
-- 说明：
-- 1. 保留 1 分钱内部基准套餐，用于自定义充值时按比例折算 Token，不对前台用户展示。
-- 2. 默认三档套餐基于 2026-03-20 的 DeepSeek / 阿里百炼官方价格做保守估算，兼顾吸引力和利润空间。
INSERT INTO recharge_packages (package_name, package_price, package_desc, package_benefit, llm_token, embedding_token, sort_order, enabled)
VALUES
    ('内部基准', 1, '自定义充值折算基准，不对外展示', 'LLM Token: 2,500\nEmbedding Token: 1,000', 2500, 1000, 999, TRUE),
    ('体验版', 990, '适合轻度体验、日常问答和少量知识库上传。', 'LLM Token：250 万\nEmbedding Token：100 万\n支持微信支付充值\n余额到账后可直接使用', 2500000, 1000000, 10, TRUE),
    ('进阶版', 1990, '适合持续问答、资料整理和中等规模知识库构建。', 'LLM Token：550 万\nEmbedding Token：250 万\n支持微信支付充值\n余额到账后可直接使用', 5500000, 2500000, 20, TRUE),
    ('旗舰版', 4990, '适合高频问答、团队共享资料和较大规模知识库场景。', 'LLM Token：1400 万\nEmbedding Token：600 万\n支持微信支付充值\n余额到账后可直接使用', 14000000, 6000000, 30, TRUE);


-- 创建用户 Token 变动记录表
CREATE TABLE IF NOT EXISTS `user_token_record` (
                               `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
                               `user_id` VARCHAR(64) NOT NULL COMMENT '用户 ID',
                               `record_date` DATE NOT NULL COMMENT '记录日期（按天统计）',
                               `token_type` VARCHAR(20) NOT NULL COMMENT 'Token 类型：LLM/EMBEDDING',
                               `change_type` VARCHAR(20) NOT NULL COMMENT '变动类型：INCREASE/CONSUME',
                                `request_count` BIGINT NOT NULL DEFAULT 0 COMMENT '请求次数（一次充值或对话可能包含多次 API 请求）',
                               `amount` BIGINT NOT NULL COMMENT '变动数量',
                               `balance_before` BIGINT DEFAULT NULL COMMENT '变动前的余额',
                               `balance_after` BIGINT DEFAULT NULL COMMENT '变动后的余额',
                               `reason` VARCHAR(500) DEFAULT NULL COMMENT '变动原因描述',
                               `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注信息（订单号、对话 ID 等）',
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
