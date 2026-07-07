package com.yizhaoqi.smartpai.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProductReadingToolRegistry {

    private static final String SESSION_TOOL_NAME = "get_session_state";
    private static final String LIST_PAPERS_TOOL_NAME = "list_papers";
    private static final String SEARCH_TOOL_NAME = "search_paper_candidates";
    private static final String IDENTITY_TOOL_NAME = "find_papers_by_identity";
    private static final String GET_OUTLINE_TOOL_NAME = "get_paper_outline";
    private static final String LIST_LOCATIONS_TOOL_NAME = "list_paper_locations";
    private static final String LOCATION_TOOL_NAME = "find_reading_locations";
    private static final String READ_TOOL_NAME = "read_locations";
    private static final String TRACE_TOOL_NAME = "trace_source_quotes";

    private final ProductReadingToolAdapter adapter;
    private final ReadingToolArgumentValidator validator;
    private final List<AgentToolRegistry.AgentTool> tools;

    public ProductReadingToolRegistry(ProductReadingToolAdapter adapter,
                                      ReadingToolArgumentValidator validator) {
        this.adapter = adapter;
        this.validator = validator;
        this.tools = List.of(
                getSessionStateTool(),
                listPapersTool(),
                searchPaperCandidatesTool(),
                findPapersByIdentityTool(),
                getPaperOutlineTool(),
                listPaperLocationsTool(),
                findReadingLocationsTool(),
                readLocationsTool(),
                traceSourceQuotesTool()
        );
    }

    public List<AgentToolRegistry.AgentTool> listTools() {
        return tools;
    }

    public ProductToolResult execute(String toolName, Map<String, Object> arguments, ProductToolContext context) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        ProductToolContext safeContext = context == null
                ? new ProductToolContext(null, "", "", SourceScope.auto())
                : context;
        return switch (toolName == null ? "" : toolName) {
            case SESSION_TOOL_NAME -> executeGetSessionState(safeArguments, safeContext);
            case LIST_PAPERS_TOOL_NAME -> executeListPapers(safeArguments, safeContext);
            case SEARCH_TOOL_NAME -> executeSearchPaperCandidates(safeArguments, safeContext);
            case IDENTITY_TOOL_NAME -> executeFindPapersByIdentity(safeArguments, safeContext);
            case GET_OUTLINE_TOOL_NAME -> executeGetPaperOutline(safeArguments, safeContext);
            case LIST_LOCATIONS_TOOL_NAME -> executeListPaperLocations(safeArguments, safeContext);
            case LOCATION_TOOL_NAME -> executeFindReadingLocations(safeArguments, safeContext);
            case READ_TOOL_NAME -> executeReadLocations(safeArguments, safeContext);
            case TRACE_TOOL_NAME -> executeTraceSourceQuotes(safeArguments, safeContext);
            default -> error(toolName, "unsupported_reading_tool");
        };
    }

    private ProductToolResult executeGetSessionState(Map<String, Object> arguments, ProductToolContext context) {
        ReadingToolArgumentValidator.ValidationResult validation = validator.validateGetSessionState(arguments);
        if (!validation.valid()) {
            return invalidArgument(SESSION_TOOL_NAME, validation);
        }
        return adapter.getSessionState(context);
    }

    private ProductToolResult executeListPapers(Map<String, Object> arguments, ProductToolContext context) {
        ReadingToolArgumentValidator.ValidationResult validation = validator.validateListPapers(arguments);
        if (!validation.valid()) {
            return invalidArgument(LIST_PAPERS_TOOL_NAME, validation);
        }
        return adapter.listPapers(
                validator.listPaperFilters(arguments.get("filters")),
                validator.includeFacets(arguments.get("includeFacets")),
                validator.listPaperSort(arguments.get("sort")),
                context
        );
    }

    private ProductToolResult executeSearchPaperCandidates(Map<String, Object> arguments, ProductToolContext context) {
        ReadingToolArgumentValidator.ValidationResult validation = validator.validateSearchPaperCandidates(arguments);
        if (!validation.valid()) {
            return invalidArgument(SEARCH_TOOL_NAME, validation);
        }
        return adapter.searchPaperCandidates(stringValue(arguments.get("queryText")), context);
    }

    private ProductToolResult executeFindPapersByIdentity(Map<String, Object> arguments, ProductToolContext context) {
        ReadingToolArgumentValidator.ValidationResult validation = validator.validateFindPapersByIdentity(arguments);
        if (!validation.valid()) {
            return invalidArgument(IDENTITY_TOOL_NAME, validation);
        }
        return adapter.findPapersByIdentity(validator.identityHints(arguments.get("identityHints")), context);
    }

    private AgentToolRegistry.AgentTool getSessionStateTool() {
        return new AgentToolRegistry.AgentTool(
                SESSION_TOOL_NAME,
                "Get fixed Product Reading search-scope label and READY readable paper count. Returns compact product state only; no paper handles and no paper content.",
                objectSchema(Map.of(), List.of())
        );
    }

    private AgentToolRegistry.AgentTool listPapersTool() {
        return new AgentToolRegistry.AgentTool(
                LIST_PAPERS_TOOL_NAME,
                "Browse deterministic READY papers inside the fixed conversation search scope. Returns paperHandle cards and optional facets only; it is not semantic search and not Source Quotes.",
                objectSchema(Map.of(
                        "filters", objectSchema(Map.of(
                                "titleContains", stringSchema("Optional deterministic title substring filter."),
                                "titleExact", stringSchema("Optional deterministic exact title filter."),
                                "filenameContains", stringSchema("Optional deterministic filename substring filter."),
                                "filenameExact", stringSchema("Optional deterministic exact filename filter."),
                                "authorName", stringSchema("Optional deterministic author substring filter."),
                                "doiExact", stringSchema("Optional deterministic exact DOI filter."),
                                "arxivIdExact", stringSchema("Optional deterministic exact arXiv id filter."),
                                "yearRange", objectSchema(Map.of(
                                        "from", integerSchema("Inclusive start publication year."),
                                        "to", integerSchema("Inclusive end publication year.")
                                ), List.of("from", "to")),
                                "venue", stringSchema("Optional deterministic venue substring filter.")
                        ), List.of()),
                        "includeFacets", booleanSchema("Whether to include deterministic value/count facet buckets."),
                        "sort", enumStringSchema("Deterministic browse sort.", List.of("RECENT", "TITLE", "YEAR"))
                ), List.of())
        );
    }

    private ProductToolResult executeGetPaperOutline(Map<String, Object> arguments, ProductToolContext context) {
        ReadingToolArgumentValidator.ValidationResult validation = validator.validateGetPaperOutline(arguments);
        if (!validation.valid()) {
            return invalidArgument(GET_OUTLINE_TOOL_NAME, validation);
        }
        return adapter.getPaperOutline(validator.stringList(arguments.get("paperHandles")), context);
    }

    private ProductToolResult executeListPaperLocations(Map<String, Object> arguments, ProductToolContext context) {
        ReadingToolArgumentValidator.ValidationResult validation = validator.validateListPaperLocations(arguments);
        if (!validation.valid()) {
            return invalidArgument(LIST_LOCATIONS_TOOL_NAME, validation);
        }
        return adapter.listPaperLocations(
                validator.stringList(arguments.get("paperHandles")),
                validator.pageRange(arguments.get("pageRange")),
                validator.locationTypes(arguments.get("locationTypes")),
                context
        );
    }

    private ProductToolResult executeFindReadingLocations(Map<String, Object> arguments, ProductToolContext context) {
        ReadingToolArgumentValidator.ValidationResult validation = validator.validateFindReadingLocations(arguments);
        if (!validation.valid()) {
            return invalidArgument(LOCATION_TOOL_NAME, validation);
        }
        return adapter.findReadingLocations(
                validator.stringList(arguments.get("paperHandles")),
                stringValue(arguments.get("queryText")),
                validator.locationTypes(arguments.get("locationTypes")),
                context
        );
    }

    private ProductToolResult executeReadLocations(Map<String, Object> arguments, ProductToolContext context) {
        ReadingToolArgumentValidator.ValidationResult validation = validator.validateReadLocations(arguments);
        if (!validation.valid()) {
            return invalidArgument(READ_TOOL_NAME, validation);
        }
        return adapter.readLocations(validator.stringList(arguments.get("locationRefs")), context);
    }

    private ProductToolResult executeTraceSourceQuotes(Map<String, Object> arguments, ProductToolContext context) {
        ReadingToolArgumentValidator.ValidationResult validation = validator.validateTraceSourceQuotes(arguments);
        if (!validation.valid()) {
            return invalidArgument(TRACE_TOOL_NAME, validation);
        }
        return adapter.traceSourceQuotes(validator.stringList(arguments.get("sourceQuoteRefs")), context);
    }

    private AgentToolRegistry.AgentTool searchPaperCandidatesTool() {
        return new AgentToolRegistry.AgentTool(
                SEARCH_TOOL_NAME,
                "Search READY paper candidates inside the fixed conversation search scope. Returns paperHandle cards only; previews are not Source Quotes.",
                objectSchema(Map.of(
                        "queryText", stringSchema("Caller-authored paper candidate search text.")
                ), List.of("queryText"))
        );
    }

    private AgentToolRegistry.AgentTool findPapersByIdentityTool() {
        return new AgentToolRegistry.AgentTool(
                IDENTITY_TOOL_NAME,
                "Resolve a specific READY paper by deterministic identity hints such as title, filename, DOI, arXiv id, author, or year. Returns paperHandle cards and ambiguity status only; it is not semantic search and not Source Quotes.",
                objectSchema(Map.of(
                        "identityHints", objectSchema(Map.of(
                                "titleContains", stringSchema("Optional deterministic title substring identity hint."),
                                "titleExact", stringSchema("Optional deterministic exact title identity hint."),
                                "filenameContains", stringSchema("Optional deterministic filename substring identity hint."),
                                "filenameExact", stringSchema("Optional deterministic exact filename identity hint."),
                                "doiExact", stringSchema("Optional canonical exact DOI identity hint."),
                                "arxivIdExact", stringSchema("Optional canonical exact arXiv id identity hint."),
                                "authorName", stringSchema("Optional deterministic author substring identity hint."),
                                "year", integerSchema("Optional publication year narrowing hint; year alone is invalid.")
                        ), List.of())
                ), List.of("identityHints"))
        );
    }

    private AgentToolRegistry.AgentTool getPaperOutlineTool() {
        return new AgentToolRegistry.AgentTool(
                GET_OUTLINE_TOOL_NAME,
                "Inspect deterministic current READY paper structure for explicit paperHandles. Returns sectionRefs and parser-quality metadata only; it does not read paper content.",
                objectSchema(Map.of(
                        "paperHandles", arrayStringSchema("Opaque paper handles returned by PaperLoom tools or clicked paper rows.")
                ), List.of("paperHandles"))
        );
    }

    private AgentToolRegistry.AgentTool listPaperLocationsTool() {
        return new AgentToolRegistry.AgentTool(
                LIST_LOCATIONS_TOOL_NAME,
                "List deterministic current READY paper locations for explicit paperHandles. Returns locationRefs only; it does not read content and does not accept semantic search text.",
                objectSchema(Map.of(
                        "paperHandles", arrayStringSchema("Opaque paper handles returned by PaperLoom tools or clicked paper rows."),
                        "pageRange", objectSchema(Map.of(
                                "from", integerSchema("1-based inclusive start page."),
                                "to", integerSchema("1-based inclusive end page.")
                        ), List.of("from", "to")),
                        "locationTypes", arrayEnumSchema(
                                "Optional deterministic location type filter.",
                                List.of("PAGE", "SECTION", "TABLE", "FIGURE")
                        )
                ), List.of("paperHandles"))
        );
    }

    private AgentToolRegistry.AgentTool findReadingLocationsTool() {
        return new AgentToolRegistry.AgentTool(
                LOCATION_TOOL_NAME,
                "Find candidate reading locations inside explicit READY paperHandles. Returns locationRef candidates only; previews and refs are not Source Quotes.",
                objectSchema(Map.of(
                        "paperHandles", arrayStringSchema("Opaque paper handles returned by PaperLoom tools or clicked paper rows."),
                        "queryText", stringSchema("Caller-authored in-paper location search text."),
                        "locationTypes", arrayEnumSchema(
                                "Optional coarse reading-location type filter.",
                                List.of("PAGE", "SECTION", "TABLE", "FIGURE")
                        )
                ), List.of("paperHandles", "queryText"))
        );
    }

    private AgentToolRegistry.AgentTool readLocationsTool() {
        return new AgentToolRegistry.AgentTool(
                READ_TOOL_NAME,
                "Read explicitly selected current reading locations and return Source Quotes. Accepts locationRefs only; output size and splitting are product-controlled.",
                objectSchema(Map.of(
                        "locationRefs", arrayStringSchema("Opaque location refs returned by PaperLoom reading-location tools.")
                ), List.of("locationRefs"))
        );
    }

    private AgentToolRegistry.AgentTool traceSourceQuotesTool() {
        return new AgentToolRegistry.AgentTool(
                TRACE_TOOL_NAME,
                "Trace explicitly clicked Source Quote refs from this turn's reading anchors. Accepts sourceQuoteRefs only and returns stored Source Quotes.",
                objectSchema(Map.of(
                        "sourceQuoteRefs", arrayStringSchema("Opaque source quote refs from explicit clickedSourceQuoteRefs anchors.")
                ), List.of("sourceQuoteRefs"))
        );
    }

    private ProductToolResult invalidArgument(String toolName,
                                              ReadingToolArgumentValidator.ValidationResult validation) {
        return new ProductToolResult(
                toolName,
                false,
                Map.of(
                        "status", "INVALID_ARGUMENT",
                        "error", validation.error(),
                        "argument", validation.argument()
                ),
                ProductToolEffect.ERROR
        );
    }

    private ProductToolResult error(String toolName, String reason) {
        return new ProductToolResult(
                toolName,
                false,
                Map.of("error", reason),
                ProductToolEffect.ERROR
        );
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties == null ? Map.of() : properties);
        schema.put("required", required == null ? List.of() : required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> stringSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        return schema;
    }

    private Map<String, Object> integerSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("description", description);
        return schema;
    }

    private Map<String, Object> booleanSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "boolean");
        schema.put("description", description);
        return schema;
    }

    private Map<String, Object> enumStringSchema(String description, List<String> values) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        schema.put("enum", values == null ? List.of() : values);
        return schema;
    }

    private Map<String, Object> arrayStringSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("description", description);
        schema.put("items", Map.of("type", "string"));
        return schema;
    }

    private Map<String, Object> arrayEnumSchema(String description, List<String> values) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("description", description);
        schema.put("items", Map.of(
                "type", "string",
                "enum", values == null ? List.of() : values
        ));
        return schema;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
