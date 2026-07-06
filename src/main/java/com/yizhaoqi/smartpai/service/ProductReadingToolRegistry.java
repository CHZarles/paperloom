package com.yizhaoqi.smartpai.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProductReadingToolRegistry {

    private static final String SEARCH_TOOL_NAME = "search_paper_candidates";
    private static final String LOCATION_TOOL_NAME = "find_reading_locations";
    private static final String READ_TOOL_NAME = "read_locations";

    private final ProductReadingToolAdapter adapter;
    private final ReadingToolArgumentValidator validator;
    private final List<AgentToolRegistry.AgentTool> tools;

    public ProductReadingToolRegistry(ProductReadingToolAdapter adapter,
                                      ReadingToolArgumentValidator validator) {
        this.adapter = adapter;
        this.validator = validator;
        this.tools = List.of(searchPaperCandidatesTool(), findReadingLocationsTool(), readLocationsTool());
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
            case SEARCH_TOOL_NAME -> executeSearchPaperCandidates(safeArguments, safeContext);
            case LOCATION_TOOL_NAME -> executeFindReadingLocations(safeArguments, safeContext);
            case READ_TOOL_NAME -> executeReadLocations(safeArguments, safeContext);
            default -> error(toolName, "unsupported_reading_tool");
        };
    }

    private ProductToolResult executeSearchPaperCandidates(Map<String, Object> arguments, ProductToolContext context) {
        ReadingToolArgumentValidator.ValidationResult validation = validator.validateSearchPaperCandidates(arguments);
        if (!validation.valid()) {
            return invalidArgument(SEARCH_TOOL_NAME, validation);
        }
        return adapter.searchPaperCandidates(stringValue(arguments.get("queryText")), context);
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

    private AgentToolRegistry.AgentTool searchPaperCandidatesTool() {
        return new AgentToolRegistry.AgentTool(
                SEARCH_TOOL_NAME,
                "Search READY paper candidates inside the fixed conversation search scope. Returns paperHandle cards only; previews are not Source Quotes.",
                objectSchema(Map.of(
                        "queryText", stringSchema("Caller-authored paper candidate search text.")
                ), List.of("queryText"))
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
