package io.github.chzarles.paperloom.eval;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public final class EvalCorpusMigrationCli {

    private static final String REQUIRED_TARGET_SCHEMA = "paperloom_eval";

    private EvalCorpusMigrationCli() {
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            Options options = Options.parse(args);
            exitCode = run(options, JdbcMigrationStore.fromOptions(options), System.out);
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    static int run(Options options, MigrationStore store, PrintStream output) {
        List<CorpusCount> counts = store.countByCorpus(options.sourceSchema(), options.selectedCorpora());
        printPlan(options, counts, output);
        if (!options.dryRun()) {
            store.migrate(options, output);
        }
        return 0;
    }

    private static void printPlan(Options options, List<CorpusCount> counts, PrintStream output) {
        output.println("source schema: " + options.sourceSchema());
        output.println("target schema: " + options.targetSchema());
        output.println("corpus: " + options.corpus());
        output.println("dryRun=" + options.dryRun());
        output.println("rebuildIndices=" + options.rebuildIndices());
        for (CorpusCount count : counts) {
            output.println(count.corpus() + " expected papers: " + count.papers());
            output.println(count.corpus() + " expected chunks: " + count.chunks());
        }
    }

    public record CorpusCount(String corpus, long papers, long chunks) {
    }

    public interface MigrationStore {
        List<CorpusCount> countByCorpus(String sourceSchema, List<String> corpora);

        default void migrate(Options options, PrintStream output) {
            throw new UnsupportedOperationException("Migration writes are not supported by this store");
        }
    }

    public record Options(
            String sourceSchema,
            String targetSchema,
            String corpus,
            boolean dryRun,
            boolean rebuildIndices,
            String jdbcUrl,
            String username,
            String password
    ) {

        static Options parse(String[] args) {
            Map<String, String> values = parseArgs(args);
            Properties env = loadEnv(Path.of(values.getOrDefault("env", ".env")));
            String sourceSchema = normalizeIdentifier(values.getOrDefault("source-schema", "paperloom"), "source schema");
            String targetSchema = normalizeIdentifier(values.getOrDefault("target-schema", REQUIRED_TARGET_SCHEMA), "target schema");
            if (!REQUIRED_TARGET_SCHEMA.equals(targetSchema)) {
                throw new IllegalArgumentException("target schema must be paperloom_eval");
            }
            String corpus = values.getOrDefault("corpus", "all").trim().toLowerCase(Locale.ROOT);
            if (!List.of("all", "litsearch", "qasper").contains(corpus)) {
                throw new IllegalArgumentException("corpus must be one of: all, litsearch, qasper");
            }
            return new Options(
                    sourceSchema,
                    targetSchema,
                    corpus,
                    Boolean.parseBoolean(values.getOrDefault("dry-run", "false")),
                    Boolean.parseBoolean(values.getOrDefault("rebuild-indices", "false")),
                    values.getOrDefault("jdbc-url", env.getProperty("SPRING_DATASOURCE_URL", "")),
                    values.getOrDefault("username", env.getProperty("SPRING_DATASOURCE_USERNAME", "")),
                    values.getOrDefault("password", env.getProperty("SPRING_DATASOURCE_PASSWORD", ""))
            );
        }

        List<String> selectedCorpora() {
            if ("all".equals(corpus)) {
                return List.of("litsearch", "qasper");
            }
            return List.of(corpus);
        }
    }

    static final class JdbcMigrationStore implements MigrationStore {
        private final String jdbcUrl;
        private final String username;
        private final String password;

        private JdbcMigrationStore(String jdbcUrl, String username, String password) {
            this.jdbcUrl = requireText(jdbcUrl, "jdbc-url");
            this.username = requireText(username, "username");
            this.password = password == null ? "" : password;
        }

        static JdbcMigrationStore fromOptions(Options options) {
            return new JdbcMigrationStore(options.jdbcUrl(), options.username(), options.password());
        }

        @Override
        public List<CorpusCount> countByCorpus(String sourceSchema, List<String> corpora) {
            try (Connection connection = connect()) {
                List<CorpusCount> counts = new ArrayList<>();
                for (String corpus : corpora) {
                    counts.add(new CorpusCount(
                            corpus,
                            countPapers(connection, sourceSchema, corpus),
                            countChunks(connection, sourceSchema, corpus)
                    ));
                }
                return counts;
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to count eval corpus rows", exception);
            }
        }

        @Override
        public void migrate(Options options, PrintStream output) {
            try (Connection connection = connect()) {
                createSchemaAndTables(connection, options.targetSchema());
                for (String corpus : options.selectedCorpora()) {
                    long papers = migratePapers(connection, options.sourceSchema(), options.targetSchema(), corpus);
                    long chunks = migrateChunks(connection, options.sourceSchema(), options.targetSchema(), corpus);
                    output.println(corpus + " migrated papers: " + papers);
                    output.println(corpus + " migrated chunks: " + chunks);
                }
                if (options.rebuildIndices()) {
                    EvalCorpusIndexService indexService = new EvalCorpusIndexService();
                    for (String corpus : options.selectedCorpora()) {
                        EvalCorpusIndexService.EvalIndices indices = indexService.indicesFor(corpus);
                        output.println(corpus + " eval paper index: " + indices.paperSearchIndex());
                        output.println(corpus + " eval chunk index: " + indices.chunksIndex());
                    }
                    output.println("rebuildIndices requested; Elasticsearch indexing is handled by the eval retrieval/index task");
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to migrate eval corpus rows", exception);
            }
        }

        private Connection connect() throws SQLException {
            return DriverManager.getConnection(jdbcUrl, username, password);
        }
    }

    private static long countPapers(Connection connection, String sourceSchema, String corpus) throws SQLException {
        String schema = quoteIdentifier(sourceSchema);
        String sql = """
                SELECT COUNT(*)
                FROM %s.file_upload
                WHERE source_dataset = ?
                   OR file_md5 LIKE CONCAT(?, '%%')
                """.formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, corpus);
            statement.setString(2, corpus + ":");
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static long countChunks(Connection connection, String sourceSchema, String corpus) throws SQLException {
        String schema = quoteIdentifier(sourceSchema);
        String sql = """
                SELECT COUNT(*)
                FROM %s.paper_text_chunks c
                JOIN %s.file_upload p ON p.file_md5 = c.paper_id
                WHERE p.source_dataset = ?
                   OR c.paper_id LIKE CONCAT(?, '%%')
                """.formatted(schema, schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, corpus);
            statement.setString(2, corpus + ":");
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static void createSchemaAndTables(Connection connection, String targetSchema) throws SQLException {
        String schema = quoteIdentifier(targetSchema);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS " + schema);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s.eval_papers (
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      corpus VARCHAR(64) NOT NULL,
                      split VARCHAR(64) NOT NULL,
                      external_paper_id VARCHAR(128) NOT NULL,
                      paper_id VARCHAR(160) NOT NULL,
                      title TEXT,
                      abstract_text TEXT,
                      authors TEXT,
                      venue VARCHAR(255),
                      publication_year INT,
                      doi VARCHAR(255),
                      arxiv_id VARCHAR(255),
                      full_text LONGTEXT,
                      source_json LONGTEXT,
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      UNIQUE KEY uk_eval_papers_corpus_paper (corpus, paper_id),
                      KEY idx_eval_papers_corpus_split (corpus, split),
                      KEY idx_eval_papers_external (corpus, external_paper_id)
                    )
                    """.formatted(schema));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s.eval_chunks (
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      corpus VARCHAR(64) NOT NULL,
                      split VARCHAR(64) NOT NULL,
                      paper_id VARCHAR(160) NOT NULL,
                      chunk_id INT NOT NULL,
                      text_content LONGTEXT,
                      retrieval_text_content LONGTEXT,
                      section_title VARCHAR(500),
                      page_number INT,
                      source_kind VARCHAR(64),
                      evidence_role VARCHAR(64),
                      source_json LONGTEXT,
                      UNIQUE KEY uk_eval_chunks_corpus_paper_chunk (corpus, paper_id, chunk_id),
                      KEY idx_eval_chunks_corpus_split (corpus, split),
                      KEY idx_eval_chunks_paper (corpus, paper_id, chunk_id)
                    )
                    """.formatted(schema));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s.eval_queries (
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      corpus VARCHAR(64) NOT NULL,
                      split VARCHAR(64) NOT NULL,
                      query_id VARCHAR(160) NOT NULL,
                      query_text LONGTEXT NOT NULL,
                      expected_paper_ids_json LONGTEXT,
                      expected_evidence_json LONGTEXT,
                      source_json LONGTEXT,
                      UNIQUE KEY uk_eval_queries_corpus_query (corpus, split, query_id),
                      KEY idx_eval_queries_corpus_split (corpus, split)
                    )
                    """.formatted(schema));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s.eval_runs (
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      corpus VARCHAR(64) NOT NULL,
                      split VARCHAR(64) NOT NULL,
                      strategy VARCHAR(128) NOT NULL,
                      run_config_json LONGTEXT,
                      metrics_json LONGTEXT,
                      artifact_path VARCHAR(1000),
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      KEY idx_eval_runs_corpus_split (corpus, split),
                      KEY idx_eval_runs_created_at (created_at)
                    )
                    """.formatted(schema));
        }
    }

    private static long migratePapers(Connection connection,
                                      String sourceSchema,
                                      String targetSchema,
                                      String corpus) throws SQLException {
        String source = quoteIdentifier(sourceSchema);
        String target = quoteIdentifier(targetSchema);
        String sql = """
                INSERT INTO %s.eval_papers (
                  corpus, split, external_paper_id, paper_id, title, abstract_text, authors, venue,
                  publication_year, doi, arxiv_id, full_text, source_json
                )
                SELECT
                  COALESCE(NULLIF(source_dataset, ''), ?),
                  COALESCE(NULLIF(eval_split, ''), 'full'),
                  COALESCE(NULLIF(external_corpus_id, ''), REPLACE(file_md5, CONCAT(?, ':'), '')),
                  file_md5,
                  paper_title,
                  abstract_text,
                  authors,
                  venue,
                  publication_year,
                  doi,
                  arxiv_id,
                  NULL,
                  JSON_OBJECT('sourceSchema', ?, 'originalFilename', file_name)
                FROM %s.file_upload
                WHERE source_dataset = ? OR file_md5 LIKE CONCAT(?, '%%')
                ON DUPLICATE KEY UPDATE
                  split = VALUES(split),
                  external_paper_id = VALUES(external_paper_id),
                  title = VALUES(title),
                  abstract_text = VALUES(abstract_text),
                  authors = VALUES(authors),
                  venue = VALUES(venue),
                  publication_year = VALUES(publication_year),
                  doi = VALUES(doi),
                  arxiv_id = VALUES(arxiv_id),
                  source_json = VALUES(source_json)
                """.formatted(target, source);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, corpus);
            statement.setString(2, corpus);
            statement.setString(3, sourceSchema);
            statement.setString(4, corpus);
            statement.setString(5, corpus + ":");
            return statement.executeUpdate();
        }
    }

    private static long migrateChunks(Connection connection,
                                      String sourceSchema,
                                      String targetSchema,
                                      String corpus) throws SQLException {
        String source = quoteIdentifier(sourceSchema);
        String target = quoteIdentifier(targetSchema);
        String sql = """
                INSERT INTO %s.eval_chunks (
                  corpus, split, paper_id, chunk_id, text_content, retrieval_text_content, section_title,
                  page_number, source_kind, evidence_role, source_json
                )
                SELECT
                  COALESCE(NULLIF(p.source_dataset, ''), ?),
                  COALESCE(NULLIF(p.eval_split, ''), 'full'),
                  c.paper_id,
                  c.chunk_id,
                  c.text_content,
                  c.text_content,
                  c.section_title,
                  c.page_number,
                  c.source_kind,
                  c.evidence_role,
                  JSON_OBJECT('sourceSchema', ?, 'sourceVectorId', c.vector_id, 'rawProvenance', c.raw_provenance_json)
                FROM %s.paper_text_chunks c
                JOIN %s.file_upload p ON p.file_md5 = c.paper_id
                WHERE p.source_dataset = ? OR c.paper_id LIKE CONCAT(?, '%%')
                ON DUPLICATE KEY UPDATE
                  split = VALUES(split),
                  text_content = VALUES(text_content),
                  retrieval_text_content = VALUES(retrieval_text_content),
                  section_title = VALUES(section_title),
                  page_number = VALUES(page_number),
                  source_kind = VALUES(source_kind),
                  evidence_role = VALUES(evidence_role),
                  source_json = VALUES(source_json)
                """.formatted(target, source, source);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, corpus);
            statement.setString(2, sourceSchema);
            statement.setString(3, corpus);
            statement.setString(4, corpus + ":");
            return statement.executeUpdate();
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
}
