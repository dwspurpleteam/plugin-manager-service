package ru.itmo.pluginmanager.codegen;

import java.nio.file.Files;
import java.nio.file.Path;

import org.flywaydb.core.Flyway;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.Target;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class JooqGenerator {
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:17-alpine");

    private JooqGenerator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                "Expected arguments: <migrations directory> <output directory> <target package>"
            );
        }

        Path migrationsDirectory = Path.of(args[0]).toAbsolutePath().normalize();
        Path outputDirectory = Path.of(args[1]).toAbsolutePath().normalize();
        String targetPackage = args[2];

        if (!Files.isDirectory(migrationsDirectory)) {
            throw new IllegalArgumentException("Migrations directory does not exist: " + migrationsDirectory);
        }
        Files.createDirectories(outputDirectory);

        String jdbcUrl = System.getenv("JOOQ_JDBC_URL");
        if (jdbcUrl != null && !jdbcUrl.isBlank()) {
            if (!jdbcUrl.equals("jdbc:postgresql://127.0.0.1:5432/plugin_manager_build")) {
                throw new IllegalArgumentException("JOOQ_JDBC_URL must point to the disposable build database");
            }
            generate(jdbcUrl, "postgres", "", migrationsDirectory, outputDirectory, targetPackage);
            return;
        }

        try (PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("plugin_manager")
            .withUsername("plugin_manager")
            .withPassword("plugin_manager")) {
            postgres.start();
            generate(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                migrationsDirectory, outputDirectory, targetPackage);
        }
    }

    private static void generate(
        String jdbcUrl,
        String username,
        String password,
        Path migrationsDirectory,
        Path outputDirectory,
        String targetPackage
    ) throws Exception {
        Flyway.configure()
            .dataSource(jdbcUrl, username, password)
            .locations("filesystem:" + migrationsDirectory)
            .load()
            .migrate();

        GenerationTool.generate(configuration(jdbcUrl, username, password, outputDirectory, targetPackage));
    }

    private static Configuration configuration(
        String jdbcUrl,
        String username,
        String password,
        Path outputDirectory,
        String targetPackage
    ) {
        return new Configuration()
            .withJdbc(new Jdbc()
                .withDriver("org.postgresql.Driver")
                .withUrl(jdbcUrl)
                .withUser(username)
                .withPassword(password))
            .withGenerator(new Generator()
                .withName("org.jooq.codegen.JavaGenerator")
                .withDatabase(new Database()
                    .withName("org.jooq.meta.postgres.PostgresDatabase")
                    .withInputSchema("public")
                    .withIncludes(".*")
                    .withExcludes("flyway_schema_history"))
                .withGenerate(new Generate()
                    .withDeprecated(false)
                    .withRecords(true))
                .withTarget(new Target()
                    .withPackageName(targetPackage)
                    .withDirectory(outputDirectory.toString())
                    .withClean(true)));
    }
}
