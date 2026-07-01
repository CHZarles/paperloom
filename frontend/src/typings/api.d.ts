/**
 * Namespace Api
 *
 * All backend api type
 */
declare namespace Api {
  namespace Common {
    /** common params of paginating */
    interface PaginatingCommonParams {
      /** current page number */
      page?: number;
      number: number;
      /** page size */
      size?: number;
      /** total count */
      totalElements: number;
    }

    /** common params of paginating query list data */
    interface PaginatingQueryRecord<T = any> extends PaginatingCommonParams {
      data: T[];
      content: T[];
    }

    /** common search params of table */
    type CommonSearchParams = Pick<Common.PaginatingCommonParams, 'page' | 'size'>;
  }

  /**
   * namespace Auth
   *
   * backend api module: "auth"
   */
  namespace Auth {
    interface LoginToken {
      token: string;
      refreshToken: string;
    }

    interface UserInfo {
      id: number;
      username: string;
      role: 'USER' | 'ADMIN';
      orgTags: string[];
      primaryOrg: string;
    }
  }

  /**
   * namespace Route
   *
   * backend api module: "route"
   */
  namespace Route {
    type ElegantConstRoute = import('@elegant-router/types').ElegantConstRoute;

    interface MenuRoute extends ElegantConstRoute {
      id: string;
    }

    interface UserRoute {
      routes: MenuRoute[];
      home: import('@elegant-router/types').LastLevelRouteKey;
    }
  }

  namespace OrgTag {
    interface Item {
      tagId: string;
      name: string;
      description: string;
      parentTag: string | null;
      uploadMaxSizeBytes: number | null;
      uploadMaxSizeMb: number | null;
      children?: Item[];
    }

    type List = Common.PaginatingQueryRecord<Item>;

    type Details = Pick<Item, 'tagId' | 'name' | 'description'>;
    type Mine = {
      orgTags: string[];
      primaryOrg: string;
      orgTagDetails: Details[];
    };
  }

  namespace User {
    interface UsageQuota {
      enabled: boolean;
      usedTokens: number;
      limitTokens: number;
      remainingTokens: number;
      requestCount: number;
    }

    interface UsageSnapshot {
      day: string;
      chatRequestCount: number;
      llm: UsageQuota;
      embedding: UsageQuota;
    }

    interface TokenRecord {
      id: number;
      recordDate: string;
      tokenType: 'LLM' | 'EMBEDDING';
      changeType: 'INCREASE' | 'CONSUME';
      amount: number;
      balanceBefore: number | null;
      balanceAfter: number | null;
      reason: string;
      remark: string | null;
      requestCount: number;
      createdAt: string;
    }

    type SearchParams = CommonType.RecordNullable<
      Common.CommonSearchParams & {
        keyword: string;
        orgTag: string;
        status: number;
      }
    >;

    type Item = {
      userId: string;
      username: string;
      status: number;
      orgTags: Pick<OrgTag.Item, 'tagId' | 'name'>[];
      primaryOrg: string;
      createdAt: string;
      usage: UsageSnapshot;
      chatUsage?: string;
      llmUsage?: string;
      embeddingUsage?: string;
    };

    type List = Common.PaginatingQueryRecord<Item>;
  }

  namespace InviteCode {
    type SearchParams = CommonType.RecordNullable<
      Common.CommonSearchParams & {
        enabled: boolean;
      }
    >;

    interface Creator {
      id: number;
      username: string;
    }

    interface Item {
      id: number;
      code: string;
      maxUses: number;
      usedCount: number;
      expiresAt: string | null;
      enabled: boolean;
      createdBy?: Creator;
      createdAt: string;
      updatedAt: string;
    }

    interface ListPayload {
      records: Item[];
      total: number;
      pages: number;
      current: number;
      size: number;
    }
  }

  /**
   * namespace Recharge
   *
   * backend api module: "recharge"
   */
  namespace Recharge {
    /** 充值套餐 */
    interface Package {
      id: number;
      packageName: string;
      packagePrice: number; // 单位分
      packageDesc: string;
      packageBenefit: string;
      llmToken: number; // LLM token 数量
      embeddingToken: number; // Embedding token 数量
      enabled: boolean;
      createdAt: string;
      updatedAt: string;
    }

    /** 订单信息 */
    interface OrderInfo {
      outTradeNo: string;
      appId: string;
      prePayId: string;
      expireTime: number;
    }

    /** 充值订单 */
    interface Order {
      id: number;
      tradeNo: string;
      userId: string;
      packageId: number;
      amount: number; // 单位分
      llmToken: number; // LLM token 数量
      embeddingToken: number; // Embedding token 数量
      wxTransactionId: string;
      status: 'NOT_PAY' | 'PAYING' | 'SUCCEED' | 'FAIL' | 'CANCELLED';
      description: string;
      payTime: string | null;
      createdAt: string;
      updatedAt: string;
    }
  }

  namespace Admin {
    interface WindowLimit {
      max: number;
      windowSeconds: number;
    }

    interface DualWindowLimit {
      minuteMax: number;
      minuteWindowSeconds: number;
      dayMax: number;
      dayWindowSeconds: number;
    }

    interface TokenBudgetLimit {
      minuteMax: number;
      minuteWindowSeconds: number;
      dayMax: number;
      dayWindowSeconds: number;
    }

    interface RateLimitSettings {
      chatMessage: WindowLimit;
      llmGlobalToken: TokenBudgetLimit;
      embeddingUploadToken: TokenBudgetLimit;
      embeddingQueryRequest: DualWindowLimit;
      embeddingQueryGlobalToken: TokenBudgetLimit;
    }

    interface ModelProviderItem {
      provider: string;
      displayName: string;
      apiStyle: string;
      apiBaseUrl: string;
      model: string;
      dimension: number | null;
      enabled: boolean;
      active: boolean;
      hasApiKey: boolean;
      maskedApiKey: string;
      apiKeyInput?: string;
    }

    interface ModelProviderScopeSettings {
      scope: 'llm' | 'embedding';
      activeProvider: string;
      providers: ModelProviderItem[];
    }

    interface ModelProviderSettings {
      llm: ModelProviderScopeSettings;
      embedding: ModelProviderScopeSettings;
    }

    interface ConnectivityTestResult {
      success: boolean;
      message: string;
      latencyMs: number;
    }

    interface UsageTrendPoint {
      day: string;
      chatRequestCount: number;
      llmUsedTokens: number;
      llmRequestCount: number;
      embeddingUsedTokens: number;
      embeddingRequestCount: number;
    }

    interface UsageRankingItem {
      userId: string;
      username: string;
      scope: 'llm' | 'embedding';
      usedTokens: number;
      limitTokens: number;
      remainingTokens: number;
      requestCount: number;
    }

    interface UsageAlert {
      level: 'critical' | 'warning';
      userId: string;
      username: string;
      scope: 'llm' | 'embedding';
      usedTokens: number;
      limitTokens: number;
      remainingTokens: number;
      requestCount: number;
      usageRatio: number;
      message: string;
    }

    interface UsageOverview {
      days: number;
      today: UsageTrendPoint;
      trends: UsageTrendPoint[];
      llmRankings: UsageRankingItem[];
      embeddingRankings: UsageRankingItem[];
      alerts: UsageAlert[];
    }
  }

  namespace Paper {
    interface SearchParams {
      userId: string;
      query: string;
      pageBatchSize: number;
    }

    interface SearchResult {
      paperId: string;
      chunkId: number;
      textContent: string;
      score: number;
      paperTitle: string;
      originalFilename: string;
      pageNumber?: number | null;
      anchorText?: string | null;
      retrievalMode?: 'HYBRID' | 'TEXT_ONLY' | null;
      matchedChunkText?: string | null;
    }

    interface UploadState {
      tasks: UploadTask[];
      activeUploads: Set<string>; // 当前正在上传的任务ID
    }

    interface Form {
      orgTag: string | null;
      orgTagName: string | null;
      uploadMaxSizeBytes?: number | null;
      uploadMaxSizeMb?: number | null;
      isPublic: boolean;
      fileList: import('naive-ui').UploadFileInfo[];
    }

    interface UploadTask {
      file?: File;
      chunk?: Blob | null;
      paperId: string;
      chunkIndex: number;
      totalSize: number;
      sourceFileSizeBytes?: number;
      paperTitle: string;
      originalFilename: string;
      userId?: string;
      orgTag: string | null;
      orgTagName?: string | null;
      isPublic: boolean;
      uploadedChunks: number[];
      progress: number;
      status: UploadStatus;
      uploadStatus?: 0 | 1 | 2 | 'UPLOADING' | 'MERGING' | 'COMPLETED';
      estimatedEmbeddingTokens?: number;
      estimatedChunkCount?: number;
      actualEmbeddingTokens?: number;
      actualChunkCount?: number;
      processingStatus?:
        | 'PENDING'
        | 'PROCESSING'
        | 'MINERU_RUNNING'
        | 'MINERU_ARTIFACT_SAVED'
        | 'MAPPING_STRUCTURED_CONTENT'
        | 'RENDERING_VISUAL_ASSETS'
        | 'CHUNKING'
        | 'EMBEDDING'
        | 'INDEXING'
        | 'COMPLETED'
        | 'FAILED'
        | null;
      processingErrorMessage?: string | null;
      sourceType?: 'PDF';
      evidenceAssetLevel?: 'PDF_VISUAL' | 'PDF_PENDING_ASSETS';
      assetWarnings?: string[];
      pdfEvidenceAvailable?: boolean;
      authors?: string | null;
      publicationYear?: number | null;
      venue?: string | null;
      abstractText?: string | null;
      doi?: string | null;
      arxivId?: string | null;
      parserArtifact?: {
        available: boolean;
        parserName?: string | null;
        parserVersion?: string | null;
      };
      tableAsset?: {
        tableCount: number;
        tableSearchable: boolean;
      };
      figureAsset?: {
        figureCount: number;
        figureSearchable: boolean;
      };
      formulaAsset?: {
        formulaCount: number;
        formulaSearchable: boolean;
      };
      visualAsset?: {
        pageScreenshotCount: number;
        tableCropCount: number;
        figureCropCount?: number;
      };
      createdAt?: string;
      mergedAt?: string;
      requestIds?: string[]; // 请求ID，用于取消上传
    }
    type List = Common.PaginatingQueryRecord<UploadTask>;

    type Merge = Pick<UploadTask, 'paperId' | 'paperTitle'>;

    interface Progress {
      uploaded: number[];
      progress: number;
      totalChunks: number;
      paperId?: string;
      paperTitle?: string;
      originalFilename?: string;
    }

    interface MergeResult {
      objectUrl: string;
      paperId: string;
      paperTitle: string;
      originalFilename: string;
      estimatedEmbeddingTokens?: number;
      estimatedChunkCount?: number;
    }

    interface DownloadResponse {
      paperTitle: string;
      originalFilename: string;
      downloadUrl: string;
      sourceFileSizeBytes: number;
      paperId?: string;
    }

    interface ParserArtifactResponse {
      paperId: string;
      parserName?: string | null;
      parserVersion?: string | null;
      artifactType: string;
      downloadUrl: string;
      sizeBytes?: number | null;
    }

    interface TableItem {
      paperId: string;
      tableId: string;
      pageNumber?: number | null;
      caption?: string | null;
      sectionTitle?: string | null;
      rowCount?: number | null;
      columnCount?: number | null;
      tableText?: string | null;
      tableMarkdown?: string | null;
      bboxJson?: string | null;
      parserName?: string | null;
      parserVersion?: string | null;
      screenshotAvailable?: boolean;
    }

    interface VisualAssetResponse {
      paperId: string;
      assetType: 'PAGE_SCREENSHOT' | 'TABLE_CROP' | 'FIGURE_CROP' | 'CHART_CROP';
      pageNumber?: number | null;
      tableId?: string | null;
      figureId?: string | null;
      downloadUrl: string;
      contentType?: string | null;
      widthPx?: number | null;
      heightPx?: number | null;
    }

    interface ReferenceDetailResponse extends Chat.ReferenceEvidence {
      referenceNumber: number;
    }
  }

  namespace PaperCollection {
    type Visibility = 'PRIVATE' | 'ORG';

    interface Item {
      id: number;
      name: string;
      description?: string | null;
      visibility: Visibility;
      orgTag?: string | null;
      ownerUserId?: number | string | null;
      paperCount: number;
      searchablePaperCount: number;
      createdAt?: string;
      updatedAt?: string;
    }

    interface Detail extends Item {
      paperIds: string[];
    }

    interface UpsertPayload {
      name: string;
      description?: string | null;
      visibility: Visibility;
      orgTag?: string | null;
    }
  }

  namespace Chat {
    type GenerationStatus = 'STREAMING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
    type ScopeMode = 'AUTO_LIBRARY' | 'SOURCE_SET_SNAPSHOT';
    type ScopeStatus = 'READY' | 'DEGRADED' | 'INVALID';

    interface ReferenceEvidence {
      paperId: string;
      paperTitle: string;
      originalFilename?: string | null;
      pageNumber?: number | null;
      anchorText?: string | null;
      retrievalMode?: 'HYBRID' | 'TEXT_ONLY' | null;
      retrievalLabel?: string | null;
      retrievalQuery?: string | null;
      matchedChunkText?: string | null;
      evidenceSnippet?: string | null;
      score?: number | null;
      chunkId?: number | null;
      elementType?: string | null;
      sectionTitle?: string | null;
      sectionLevel?: number | null;
      bboxJson?: string | null;
      parserName?: string | null;
      parserVersion?: string | null;
      sourceKind?: 'TEXT' | 'TABLE' | 'FIGURE' | 'CHART' | 'FORMULA' | null;
      tableId?: string | null;
      figureId?: string | null;
      formulaId?: string | null;
      evidenceRole?: string | null;
      retrievalRoute?: string | null;
      intent?: string | null;
      rankReason?: string | null;
      tableText?: string | null;
      tableMarkdown?: string | null;
      tableScreenshotAvailable?: boolean | null;
      sourceType?: 'PDF' | null;
      evidenceAssetLevel?: 'PDF_VISUAL' | 'PDF_PENDING_ASSETS' | null;
      pdfEvidenceAvailable?: boolean | null;
      pageScreenshotAvailable?: boolean | null;
      figureScreenshotAvailable?: boolean | null;
      citationRef?: string | null;
      evidenceRef?: string | null;
      assetWarnings?: string[] | null;
    }

    interface Input {
      message: string;
      conversationId?: string;
    }

    interface Output {
      chunk: string;
    }

    interface AgentToolEvent {
      id?: string;
      tool: string;
      status: 'executing' | 'success' | 'failed';
      timestamp?: number;
    }

    interface Conversation {
      conversationId: string;
    }

    type Route =
      | 'SMALLTALK'
      | 'LIBRARY_SEARCH'
      | 'AUTO_SOURCE_QA'
      | 'MANUAL_SOURCE_QA'
      | 'REFERENCE_QA'
      | 'FOLLOW_UP'
      | 'CLARIFY'
      | 'PAPER_QA';

    interface Message {
      role: 'user' | 'assistant';
      content: string;
      status?: 'pending' | 'loading' | 'finished' | 'error';
      timestamp?: string;
      conversationId?: string;
      conversationRecordId?: number;
      generationId?: string;
      username?: string;
      route?: Route;
      referenceMappings?: Record<string, ReferenceEvidence>;
      diagnostics?: Diagnostics;
      toolEvents?: AgentToolEvent[];
      feedbackRating?: 'good' | 'bad';
      effectiveScope?: ConversationScope | Record<string, any>;
    }

    interface Diagnostics {
      route?: string;
      scopeMode?: string;
      scannedCount?: number;
      acceptedEvidenceCount?: number;
      sourceCount?: number;
      stopReason?: 'EXHAUSTED' | 'PLATEAU' | 'CONTEXT_BUDGET' | 'LATENCY_BUDGET' | 'NO_USABLE_EVIDENCE' | string;
      plannerRounds?: number;
      attemptedQueries?: string[];
      fallbackUsed?: boolean;
    }

    interface Scope {
      paperIds?: string[];
      paperTitles?: string[];
      referenceNumber?: number;
      conversationRecordId?: number;
      chunkId?: number;
      pageNumber?: number;
      paperId?: string;
      paperTitle?: string;
      originalFilename?: string;
      matchedText?: string;
      matchedChunkText?: string;
      evidenceSnippet?: string;
      bboxJson?: string;
      sourceKind?: ReferenceEvidence['sourceKind'];
    }

    interface ConversationScope {
      scopeMode: ScopeMode;
      scopeLocked: boolean;
      scopeStatus: ScopeStatus;
      sourceLabel?: string | null;
      sourcePaperCount?: number | null;
      paperIds?: string[];
      sourceRecipe?: Record<string, any> | null;
    }

    interface TitleMatchScopePreviewPaper {
      paperId: string;
      paperTitle?: string | null;
      originalFilename?: string | null;
      authors?: string | null;
      venue?: string | null;
      publicationYear?: number | null;
    }

    interface TitleMatchScopePreview {
      paperCount: number;
      paperIds: string[];
      papers: TitleMatchScopePreviewPaper[];
      sourceLabel: string;
      sourceRecipe: Record<string, any>;
    }

    interface UpdateConversationScopePayload {
      scopeMode: ScopeMode;
      sourceLabel?: string;
      collectionIds?: number[];
      paperIds?: string[];
      sourceRecipe?: Record<string, any>;
      titleQuery?: string;
      titleRegex?: string;
    }

    interface Token {
      cmdToken: string;
    }

    interface GenerationSnapshot {
      generationId: string;
      userId: string;
      conversationId: string;
      question: string;
      status: GenerationStatus;
      content: string;
      createdAt: string;
      updatedAt: string;
      errorMessage?: string | null;
      referenceMappings?: Record<string, ReferenceEvidence>;
      diagnostics?: Diagnostics;
    }

    interface ConversationSession {
      id: number;
      conversationId: string;
      title: string;
      status: 'ACTIVE' | 'ARCHIVED';
      scopeMode?: ScopeMode;
      scopeLocked?: boolean;
      scopeStatus?: ScopeStatus;
      sourceLabel?: string | null;
      sourcePaperCount?: number | null;
      createdAt: string;
      updatedAt: string;
    }
  }

  namespace KnowledgeBase {
    type SearchParams = Paper.SearchParams;
    type SearchResult = Paper.SearchResult;
    type UploadState = Paper.UploadState;
    type Form = Paper.Form;
    type UploadTask = Paper.UploadTask;
    type List = Paper.List;
    type Merge = Paper.Merge;
    type Progress = Paper.Progress;
    type MergeResult = Paper.MergeResult;
  }
}
