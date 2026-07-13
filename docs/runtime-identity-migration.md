# Runtime Identity Migration

The source-code rename is independent from persisted infrastructure. Back up MySQL, Redis, MinIO,
Kafka, and Elasticsearch volumes before changing Docker resource names or the product schema.

## Compatibility First

The Compose file accepts `PAPERLOOM_*_CONTAINER` and `PAPERLOOM_*_VOLUME` overrides. Existing
installations can point these variables at their current resources while application code moves to
the PaperLoom identity. Fresh installations should use the default `paperloom-*` names.

## Migrate MySQL

Inspect the migration without changing data:

```bash
scripts/paperloom-migrate-database.sh --source-schema <current-schema>
```

After backing up the Docker volumes, apply it:

```bash
scripts/paperloom-migrate-database.sh --source-schema <current-schema> --apply
```

Then update `SPRING_DATASOURCE_URL` to use the `paperloom` schema and restart the backend. Keep the
source schema until a full application smoke test and row-count audit have passed.

## Rename Docker Resources

1. Record current volume names with `docker volume ls`.
2. Set the `PAPERLOOM_*_VOLUME` variables to those names before using the renamed Compose project.
3. Recreate containers with `docker compose --env-file .env -f docs/docker-compose.yaml up -d`.
4. Verify MySQL tables, MinIO objects, Elasticsearch indices, Kafka topics, and Redis connectivity.
5. Create new PaperLoom-named volumes and copy data only during a separate maintenance window.

Container and volume migration must never be combined with `docker compose down -v`.
