package io.github.chzarles.paperloom.eval;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EvalCorpusCleanupCli {

    private static final String REQUIRED_EVAL_SCHEMA = "paperloom_eval";
    private static final String PRODUCT_PAPER_INDEX = "paper_search";
    private static final String PRODUCT_CHUNK_INDEX = "paper_chunks";
    private static final int DELETE_BATCH_SIZE = 50_000;
    private static final List<TableRef> PAPER_ID_TABLES = List.of(
            new TableRef("paper_visual_assets", "paper_id"),
            new TableRef("paper_parser_artifacts", "paper_id"),
            new TableRef("paper_reading_elements", "paper_id"),
            new TableRef("paper_locations", "paper_id"),
            new TableRef("paper_sections", "paper_id"),
            new TableRef("paper_pages", "paper_id"),
            new TableRef("paper_reading_models", "paper_id"),
            new TableRef("paper_text_chunks", "paper_id"),
            new TableRef("chunk_info", "file_md5"),
            new TableRef("file_upload", "file_md5")
    );
    private static final List<String> LEGACY_EVAL_COLUMNS = List.of(
            "is_eval",
            "source_dataset",
            "external_corpus_id",
            "eval_split"
    );
    private static final List<String> STRAY_PRODUCT_EVAL_TABLES = List.of(
            "eval_chunks",
            "eval_papers",
            "eval_queries",
            "eval_runs"
    );

    private EvalCorpusCleanupCli() {
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            Options options = Options.parse(args);
            exitCode = run(options, JdbcCleanupStore.fromOptions(options), System.out);
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    static int run(Options options, CleanupStore store, PrintStream output) {
        CleanupCounts planned = store.count(options);
        printCounts("planned", options, planned, output);
        if (!options.dryRun()) {
            CleanupCounts deleted = store.cleanup(options);
            printCounts("deleted", options, deleted, output);
        }
        return 0;
    }

    private static void printCounts(String label, Options options, CleanupCounts counts, PrintStream output) {
        output.println(label + " cleanup");
        output.println("source schema: " + options.sourceSchema());
        output.println("eval schema: " + options.evalSchema());
        output.println("dryRun=" + options.dryRun());
        output.println("confirmDeleteEvalFromProduct=" + options.confirmDeleteEvalFromProduct());
        output.println("eval papers: " + counts.evalPapers());
        output.println("file_upload rows: " + counts.fileUploadRows());
        output.println("paper_text_chunks rows: " + counts.paperTextChunkRows());
        output.println("chunk_info rows: " + counts.uploadChunkRows());
        output.println("paper_parser_artifacts rows: " + counts.parserArtifactRows());
        output.println("paper_reading_models rows: " + counts.readingModelRows());
        output.println("paper_pages rows: " + counts.readingPageRows());
        output.println("paper_sections rows: " + counts.readingSectionRows());
        output.println("paper_locations rows: " + counts.readingLocationRows());
        output.println("paper_reading_elements rows: " + counts.readingElementRows());
        output.println("paper_visual_assets rows: " + counts.visualAssetRows());
        output.println("conversations rows: " + counts.conversationRows());
        output.println("product paper_search docs: " + counts.paperSearchDocs());
        output.println("product paper_chunks docs: " + counts.paperChunkDocs());
        output.println("legacy eval columns: " + counts.legacyEvalColumns());
        output.println("product eval tables: " + counts.productEvalTables());
    }

    public interface CleanupStore {
        CleanupCounts count(Options options);

        CleanupCounts cleanup(Options options);
    }

    public record CleanupCounts(
            long evalPapers,
            long fileUploadRows,
            long paperTextChunkRows,
            long uploadChunkRows,
            long parserArtifactRows,
            long readingModelRows,
            long readingPageRows,
            long readingSectionRows,
            long readingLocationRows,
            long readingElementRows,
            long visualAssetRows,
            long conversationRows,
            long paperSearchDocs,
            long paperChunkDocs,
            long legacyEvalColumns,
            long productEvalTables
    ) {
    }

    public record Options(
            String sourceSchema,
            String evalSchema,
            boolean dryRun,
            boolean confirmDeleteEvalFromProduct,
            String jdbcUrl,
            String username,
            String password,
            String elasticsearchUrl,
            String elasticsearchUsername,
            String elasticsearchPassword
    ) {

        static Options parse(String[] args) {
            Map<String, String> values = parseArgs(args);
            Properties env = loadEnv(Path.of(values.getOrDefault("env", ".env")));
            String sourceSchema = normalizeIdentifier(requireOption(values, "source-schema"), "source schema");
            String evalSchema = normalizeIdentifier(requireOption(values, "eval-schema"), "eval schema");
            if (!REQUIRED_EVAL_SCHEMA.equals(evalSchema)) {
                throw new IllegalArgumentException("eval schema must be paperloom_eval");
            }
            boolean dryRun = Boolean.parseBoolean(values.getOrDefault("dry-run", "false"));
            boolean confirm = Boolean.parseBoolean(values.getOrDefault("confirm-delete-eval-from-product", "false"));
            if (!dryRun && !confirm) {
                throw new IllegalArgumentException("confirmed cleanup requires --confirm-delete-eval-from-product");
            }
            return new Options(
                    sourceSchema,
                    evalSchema,
                    dryRun,
                    confirm,
                    values.getOrDefault("jdbc-url", env.getProperty("SPRING_DATASOURCE_URL", "")),
                    values.getOrDefault("username", env.getProperty("SPRING_DATASOURCE_USERNAME", "")),
                    values.getOrDefault("password", env.getProperty("SPRING_DATASOURCE_PASSWORD", "")),
                    values.getOrDefault("elasticsearch-url", EvalCorpusCleanupCli.elasticsearchUrl(env)),
                    values.getOrDefault("elasticsearch-username", env.getProperty("ELASTICSEARCH_USERNAME", "")),
                    values.getOrDefault("elasticsearch-password", env.getProperty("ELASTICSEARCH_PASSWORD", ""))
            );
        }
    }

    static final class JdbcCleanupStore implements CleanupStore {
        private final String jdbcUrl;
        private final String username;
        private final String password;
        private final ProductSearchStore productSearchStore;

        private JdbcCleanupStore(String jdbcUrl, String username, String password, ProductSearchStore productSearchStore) {
            this.jdbcUrl = requireText(jdbcUrl, "jdbc-url");
            this.username = requireText(username, "username");
            this.password = password == null ? "" : password;
            this.productSearchStore = productSearchStore;
        }

        static JdbcCleanupStore fromOptions(Options options) {
            return new JdbcCleanupStore(
                    options.jdbcUrl(),
                    options.username(),
                    options.password(),
                    HttpProductSearchStore.fromOptions(options)
            );
        }

        @Override
        public CleanupCounts count(Options options) {
            try (Connection connection = connect()) {
                List<String> prefixes = evalPaperPrefixes(connection, options.evalSchema());
                return new CleanupCounts(
                        countEvalPapers(connection, options.evalSchema()),
                        countPrefixRows(connection, options.sourceSchema(), "file_upload", "file_md5", prefixes),
                        countPrefixRows(connection, options.sourceSchema(), "paper_text_chunks", "paper_id", prefixes),
                        countPrefixRows(connection, options.sourceSchema(), "chunk_info", "file_md5", prefixes),
                        countPrefixRows(connection, options.sourceSchema(), "paper_parser_artifacts", "paper_id", prefixes),
                        countPrefixRows(connection, options.sourceSchema(), "paper_reading_models", "paper_id", prefixes),
                        countPrefixRows(connection, options.sourceSchema(), "paper_pages", "paper_id", prefixes),
                        countPrefixRows(connection, options.sourceSchema(), "paper_sections", "paper_id", prefixes),
                        countPrefixRows(connection, options.sourceSchema(), "paper_locations", "paper_id", prefixes),
                        countPrefixRows(connection, options.sourceSchema(), "paper_reading_elements", "paper_id", prefixes),
                        countPrefixRows(connection, options.sourceSchema(), "paper_visual_assets", "paper_id", prefixes),
                        countConversationRows(connection, options.sourceSchema(), prefixes),
                        productSearchStore.count(PRODUCT_PAPER_INDEX, prefixes),
                        productSearchStore.count(PRODUCT_CHUNK_INDEX, prefixes),
                        countLegacyColumns(connection, options.sourceSchema()),
                        countProductEvalTables(connection, options.sourceSchema())
                );
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to count product eval cleanup targets", exception);
            }
        }

        @Override
        public CleanupCounts cleanup(Options options) {
            try (Connection connection = connect()) {
                List<String> prefixes = evalPaperPrefixes(connection, options.evalSchema());
                long visualAssets = deletePrefixRowsInBatches(connection, options.sourceSchema(), "paper_visual_assets", "paper_id", prefixes);
                long parserArtifacts = deletePrefixRowsInBatches(connection, options.sourceSchema(), "paper_parser_artifacts", "paper_id", prefixes);
                long readingElements = deletePrefixRowsInBatches(connection, options.sourceSchema(), "paper_reading_elements", "paper_id", prefixes);
                long locations = deletePrefixRowsInBatches(connection, options.sourceSchema(), "paper_locations", "paper_id", prefixes);
                long sections = deletePrefixRowsInBatches(connection, options.sourceSchema(), "paper_sections", "paper_id", prefixes);
                long pages = deletePrefixRowsInBatches(connection, options.sourceSchema(), "paper_pages", "paper_id", prefixes);
                long readingModels = deletePrefixRowsInBatches(connection, options.sourceSchema(), "paper_reading_models", "paper_id", prefixes);
                long chunks = deletePrefixRowsInBatches(connection, options.sourceSchema(), "paper_text_chunks", "paper_id", prefixes);
                long uploadChunks = deletePrefixRowsInBatches(connection, options.sourceSchema(), "chunk_info", "file_md5", prefixes);
                long papers = deletePrefixRowsInBatches(connection, options.sourceSchema(), "file_upload", "file_md5", prefixes);
                long conversations = deleteConversationRows(connection, options.sourceSchema(), prefixes);
                long droppedColumns = dropLegacyEvalColumns(connection, options.sourceSchema());
                long droppedTables = dropProductEvalTables(connection, options.sourceSchema());
                long paperDocs = productSearchStore.delete(PRODUCT_PAPER_INDEX, prefixes);
                long chunkDocs = productSearchStore.delete(PRODUCT_CHUNK_INDEX, prefixes);
                return new CleanupCounts(
                        countEvalPapers(connection, options.evalSchema()),
                        papers,
                        chunks,
                        uploadChunks,
                        parserArtifacts,
                        readingModels,
                        pages,
                        sections,
                        locations,
                        readingElements,
                        visualAssets,
                        conversations,
                        paperDocs,
                        chunkDocs,
                        droppedColumns,
                        droppedTables
                );
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to clean eval records from product corpus", exception);
            }
        }

        private Connection connect() throws SQLException {
            return DriverManager.getConnection(jdbcUrl, username, password);
        }
    }

    interface ProductSearchStore {
        long count(String indexName, List<String> prefixes);

        long delete(String indexName, List<String> prefixes);
    }

    static final class HttpProductSearchStore implements ProductSearchStore {
        private static final Pattern COUNT_PATTERN = Pattern.compile("\"count\"\\s*:\\s*(\\d+)");
        private static final Pattern DELETED_PATTERN = Pattern.compile("\"deleted\"\\s*:\\s*(\\d+)");

        private final HttpClient client;
        private final String baseUrl;
        private final String authHeader;

        private HttpProductSearchStore(HttpClient client, String baseUrl, String username, String password) {
            this.client = client;
            this.baseUrl = requireText(baseUrl, "elasticsearch-url").replaceAll("/+$", "");
            this.authHeader = basicAuth(username, password);
        }

        static HttpProductSearchStore fromOptions(Options options) {
            return new HttpProductSearchStore(
                    HttpClient.newHttpClient(),
                    options.elasticsearchUrl(),
                    options.elasticsearchUsername(),
                    options.elasticsearchPassword()
            );
        }

        @Override
        public long count(String indexName, List<String> prefixes) {
            if (prefixes == null || prefixes.isEmpty()) {
                return 0L;
            }
            String response = post(indexName + "/_count", queryJson(prefixes));
            return numberValue(response, COUNT_PATTERN);
        }

        @Override
        public long delete(String indexName, List<String> prefixes) {
            if (prefixes == null || prefixes.isEmpty()) {
                return 0L;
            }
            String response = post(indexName + "/_delete_by_query?conflicts=proceed&refresh=true", queryJson(prefixes));
            return numberValue(response, DELETED_PATTERN);
        }

        private String post(String path, String body) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/" + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (authHeader != null) {
                builder.header("Authorization", authHeader);
            }
            try {
                HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 404) {
                    return "{}";
                }
                if (response.statusCode() >= 300) {
                    throw new IllegalStateException("Elasticsearch request failed: status=" + response.statusCode()
                            + ", body=" + response.body());
                }
                return response.body();
            } catch (IOException exception) {
                throw new IllegalStateException("Elasticsearch request failed", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Elasticsearch request interrupted", exception);
            }
        }

        private static String queryJson(List<String> prefixes) {
            String should = prefixes.stream()
                    .map(prefix -> "{\"prefix\":{\"paperId\":\"" + jsonEscape(prefix) + "\"}}")
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
            return "{\"query\":{\"bool\":{\"should\":[" + should + "],\"minimum_should_match\":1}}}";
        }

        private static long numberValue(String response, Pattern pattern) {
            Matcher matcher = pattern.matcher(response == null ? "" : response);
            return matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
        }

        private static String basicAuth(String username, String password) {
            if (username == null || username.isBlank()) {
                return null;
            }
            String credentials = username + ":" + (password == null ? "" : password);
            return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static long countEvalPapers(Connection connection, String evalSchema) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + quoteIdentifier(evalSchema) + ".eval_papers";
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static long countPrefixRows(Connection connection,
                                        String sourceSchema,
                                        String tableName,
                                        String paperIdColumn,
                                        List<String> prefixes) throws SQLException {
        if (prefixes == null || prefixes.isEmpty()) {
            return 0L;
        }
        String sql = "SELECT COUNT(*) FROM " + quoteIdentifier(sourceSchema) + "." + quoteIdentifier(tableName)
                + " WHERE " + prefixPredicates(paperIdColumn, prefixes.size());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindPrefixPatterns(statement, prefixes);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static long deletePrefixRowsInBatches(Connection connection,
                                                  String sourceSchema,
                                                  String tableName,
                                                  String paperIdColumn,
                                                  List<String> prefixes) throws SQLException {
        if (prefixes == null || prefixes.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        String sql = "DELETE FROM " + quoteIdentifier(sourceSchema) + "." + quoteIdentifier(tableName)
                + " WHERE " + prefixPredicates(paperIdColumn, prefixes.size())
                + " LIMIT " + DELETE_BATCH_SIZE;
        while (true) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindPrefixPatterns(statement, prefixes);
                int deleted = statement.executeUpdate();
                total += deleted;
                if (deleted < DELETE_BATCH_SIZE) {
                    return total;
                }
            }
        }
    }

    private static String prefixPredicates(String paperIdColumn, int count) {
        String predicate = quoteIdentifier(paperIdColumn) + " LIKE ?";
        return (predicate + " OR ").repeat(count - 1) + predicate;
    }

    private static void bindPrefixPatterns(PreparedStatement statement, List<String> prefixes) throws SQLException {
        for (int i = 0; i < prefixes.size(); i++) {
            statement.setString(i + 1, prefixes.get(i) + "%");
        }
    }

    private static long countConversationRows(Connection connection, String sourceSchema, List<String> prefixes) throws SQLException {
        if (prefixes == null || prefixes.isEmpty()) {
            return 0L;
        }
        String sql = "SELECT COUNT(*) FROM " + quoteIdentifier(sourceSchema) + ".conversations WHERE "
                + likePredicates(prefixes.size());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindLikePrefixes(statement, prefixes);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static long deleteConversationRows(Connection connection, String sourceSchema, List<String> prefixes) throws SQLException {
        if (prefixes == null || prefixes.isEmpty()) {
            return 0L;
        }
        String sql = "DELETE FROM " + quoteIdentifier(sourceSchema) + ".conversations WHERE "
                + likePredicates(prefixes.size());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindLikePrefixes(statement, prefixes);
            return statement.executeUpdate();
        }
    }

    private static String likePredicates(int count) {
        return "reference_mappings_json LIKE ? OR ".repeat(count - 1) + "reference_mappings_json LIKE ?";
    }

    private static void bindLikePrefixes(PreparedStatement statement, List<String> prefixes) throws SQLException {
        for (int i = 0; i < prefixes.size(); i++) {
            statement.setString(i + 1, "%" + prefixes.get(i) + "%");
        }
    }

    private static List<String> evalPaperPrefixes(Connection connection, String evalSchema) throws SQLException {
        String sql = "SELECT DISTINCT corpus FROM " + quoteIdentifier(evalSchema) + ".eval_papers ORDER BY corpus";
        List<String> prefixes = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                String corpus = resultSet.getString(1);
                if (corpus != null && !corpus.isBlank()) {
                    prefixes.add(corpus.trim() + ":");
                }
            }
        }
        return prefixes;
    }

    private static long countLegacyColumns(Connection connection, String sourceSchema) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = ?
                  AND TABLE_NAME = 'file_upload'
                  AND COLUMN_NAME IN (%s)
                """.formatted("?,".repeat(LEGACY_EVAL_COLUMNS.size() - 1) + "?");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sourceSchema);
            for (int i = 0; i < LEGACY_EVAL_COLUMNS.size(); i++) {
                statement.setString(i + 2, LEGACY_EVAL_COLUMNS.get(i));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static long dropLegacyEvalColumns(Connection connection, String sourceSchema) throws SQLException {
        long dropped = 0L;
        for (String column : LEGACY_EVAL_COLUMNS) {
            if (columnExists(connection, sourceSchema, "file_upload", column)) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("ALTER TABLE " + quoteIdentifier(sourceSchema)
                            + ".file_upload DROP COLUMN " + quoteIdentifier(column));
                    dropped += 1;
                }
            }
        }
        return dropped;
    }

    private static long countProductEvalTables(Connection connection, String sourceSchema) throws SQLException {
        long count = 0L;
        for (String table : STRAY_PRODUCT_EVAL_TABLES) {
            if (tableExists(connection, sourceSchema, table)) {
                count += 1;
            }
        }
        return count;
    }

    private static long dropProductEvalTables(Connection connection, String sourceSchema) throws SQLException {
        long dropped = 0L;
        for (String table : STRAY_PRODUCT_EVAL_TABLES) {
            if (tableExists(connection, sourceSchema, table)) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("DROP TABLE " + quoteIdentifier(sourceSchema) + "." + quoteIdentifier(table));
                    dropped += 1;
                }
            }
        }
        return dropped;
    }

    private static boolean columnExists(Connection connection, String schema, String table, String column) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = ?
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setString(3, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1) > 0;
            }
        }
    }

    private static boolean tableExists(Connection connection, String schema, String table) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = ?
                  AND TABLE_NAME = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1) > 0;
            }
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < (args == null ? 0 : args.length); i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }
            String key = arg.substring(2);
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                values.put(key, "true");
            } else {
                values.put(key, args[++i]);
            }
        }
        return values;
    }

    private static Properties loadEnv(Path envPath) {
        Properties properties = new Properties();
        if (envPath == null || !Files.isRegularFile(envPath)) {
            return properties;
        }
        try {
            for (String line : Files.readAllLines(envPath)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                properties.setProperty(trimmed.substring(0, separator), trimmed.substring(separator + 1));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read env file " + envPath, exception);
        }
        return properties;
    }

    private static String elasticsearchUrl(Properties env) {
        String scheme = env.getProperty("ELASTICSEARCH_SCHEME", "http");
        String host = env.getProperty("ELASTICSEARCH_HOST", "localhost");
        String port = env.getProperty("ELASTICSEARCH_PORT", "9200");
        return scheme + "://" + host + ":" + port;
    }

    private static String requireOption(Map<String, String> values, String key) {
        if (!values.containsKey(key)) {
            throw new IllegalArgumentException("--" + key + " is required");
        }
        return values.get(key);
    }

    private static String normalizeIdentifier(String value, String label) {
        String normalized = requireText(value, label);
        if (!normalized.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException(label + " must contain only letters, numbers, and underscores");
        }
        return normalized;
    }

    private static String quoteIdentifier(String identifier) {
        return "`" + normalizeIdentifier(identifier, "identifier") + "`";
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record TableRef(String tableName, String paperIdColumn) {
    }
}
