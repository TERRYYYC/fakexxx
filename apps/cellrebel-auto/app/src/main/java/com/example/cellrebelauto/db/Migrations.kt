package com.example.cellrebelauto.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v4 → v5 (Issue #5 Task 4): introduces the trusted-ledger / execution / audit / legacy-snapshot /
 * provider-pairing tables, and snapshots pre-A+ progress into `legacy_completion_snapshots`.
 *
 * Non-destructive (INV-24): no existing table is rebuilt and no row is lost. The v4 columns
 * `location_tasks.completedSuccesses` and `location_tasks.status` are LEFT IN PLACE as frozen
 * display values; their authoritative legacy copy is written to `legacy_completion_snapshots`
 * with semantics LEGACY_UNVERIFIED. Trusted quota starts from 0 per task — the migration never
 * mints a TrustedQuotaEntry (those old counts carry no A+ evidence chain; INV-05/06, M-MG-01).
 * `provider_pairing_records` is created EMPTY — an upgrade must not produce a trusted provider,
 * which would bypass §6.5.3 operator approval (M-MG-01).
 *
 * # v4→v5 迁移：新增五表，旧进度快照为 LEGACY_UNVERIFIED，可信配额从 0 起，provider 表初始为空
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val migratedAt = System.currentTimeMillis()

        // 1. trusted_quota_entries — UNIQUE(attemptId), insert-only ledger.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `trusted_quota_entries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `attemptId` INTEGER NOT NULL,
                `taskId` INTEGER NOT NULL,
                `evidenceDigest` TEXT NOT NULL,
                `committedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_trusted_quota_entries_attemptId` " +
                "ON `trusted_quota_entries`(`attemptId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_trusted_quota_entries_taskId` " +
                "ON `trusted_quota_entries`(`taskId`)"
        )

        // 2. cellrebel_executions — observed external executions (§8.6).
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cellrebel_executions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `executionId` TEXT NOT NULL,
                `attemptId` INTEGER NOT NULL,
                `completionEvidenceWire` INTEGER NOT NULL,
                `evidencePayloadDigest` TEXT NOT NULL,
                `startedAt` INTEGER NOT NULL,
                `classifiedAt` INTEGER,
                `startedAtElapsed` INTEGER NOT NULL,
                `runningConfirmedAtElapsed` INTEGER NOT NULL,
                `completedAtElapsed` INTEGER NOT NULL,
                `baselineRunningState` TEXT,
                `runningMarkerText` TEXT,
                `runningDurationMs` INTEGER,
                `webBrowsingScore` REAL,
                `videoStreamingScore` REAL,
                `roundTimestampsElapsed` TEXT
            )
            """.trimIndent()
        )
        // §6.4.2 clock discipline: the three *Elapsed columns are the ONLY mutually comparable
        // timestamps (SystemClock.elapsedRealtime). Added in R3-1 alongside the entity fields;
        // a fresh v5 table has no rows, so INTEGER NOT NULL needs no default. Without these the
        // entity DDL (5.json) would not match the migration DDL and Room schema validation throws.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_cellrebel_executions_attemptId` " +
                "ON `cellrebel_executions`(`attemptId`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_cellrebel_executions_executionId` " +
                "ON `cellrebel_executions`(`executionId`)"
        )

        // 3. auto_audit_events — append-only audit stream.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `auto_audit_events` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `seq` INTEGER NOT NULL,
                `attemptId` INTEGER,
                `correlationRef` TEXT,
                `eventType` TEXT NOT NULL,
                `payloadDigest` TEXT NOT NULL,
                `recordedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_auto_audit_events_attemptId` " +
                "ON `auto_audit_events`(`attemptId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_auto_audit_events_seq` " +
                "ON `auto_audit_events`(`seq`)"
        )

        // 4. legacy_completion_snapshots — write-once, taskId PK.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `legacy_completion_snapshots` (
                `taskId` INTEGER PRIMARY KEY NOT NULL,
                `legacyCompletedSuccesses` INTEGER NOT NULL,
                `legacyStatus` TEXT NOT NULL,
                `migratedFromSchemaVersion` INTEGER NOT NULL,
                `migratedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // 5. provider_pairing_records — the frozen principal is (applicationId, currentSignerDigest)
        //    (§6.5.4; R44, Sol GREEN-review-3 F4): a NON-unique composite lookup index. A
        //    single-column UNIQUE(applicationId) blocked signer rotation AND post-revocation
        //    re-approval (M-PA-10); single-active-per-principal is enforced by ProviderTrustStore.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `provider_pairing_records` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `applicationId` TEXT NOT NULL,
                `currentSignerDigest` TEXT NOT NULL,
                `approvedAt` INTEGER NOT NULL,
                `revokedAt` INTEGER,
                `approvedVersionCode` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_provider_pairing_records_applicationId_currentSignerDigest` " +
                "ON `provider_pairing_records`(`applicationId`, `currentSignerDigest`)"
        )

        // Snapshot pre-A+ progress as LEGACY_UNVERIFIED. Trusted quota stays empty (INV-05/06).
        // location_tasks columns are left in place as frozen display values.
        db.execSQL(
            """
            INSERT INTO legacy_completion_snapshots
                (taskId, legacyCompletedSuccesses, legacyStatus, migratedFromSchemaVersion, migratedAt)
            SELECT id, completedSuccesses, status, 4, $migratedAt FROM location_tasks
            """.trimIndent()
        )

        // ---- Owner-state current operation + unverified carrier (folded INTO v5, Sol round-9 P1 schema
        //      boundary): Issue #5's spec froze version = 5; these §7.1 shapes belong in v5, not a
        //      LATER SCHEMA. [F-19 chronicle 2026-08-27: the freeze governed Issue #5's end-state and
        //      still does — v6 adds NO table/column (see MIGRATION_5_6 below). The version NUMBER was
        //      re-opened by the operator's 2026-08-27T09:46Z plan-B ruling because an uncommitted
        //      ~8/01 build left a device on an unmigratable drifted v5; INV-24 exemption scoped to
        //      Auto only, chronicle in AppDatabase.]
        //      `aplusState`/`aplusLeaseId` = the Attempt's 当前 operation (§7.1 P1-3); the unverified table
        //      is the independent §7.1 UnverifiedAttemptRecord carrier (P2). Non-destructive: nullable
        //      ALTER + a fresh empty table (no synthetic trusted/unverified rows minted by a migration). ----
        db.execSQL("ALTER TABLE test_attempts ADD COLUMN aplusState TEXT")
        db.execSQL("ALTER TABLE test_attempts ADD COLUMN aplusLeaseId TEXT")
        db.execSQL("ALTER TABLE test_attempts ADD COLUMN currentExecutionId TEXT")
        // R48 (DSF 0ec7dd4 review P1 / spec §4.3 step 1 / R9 P1-7 precedent): the advance CAS
        // anchor triple is §7.1 attempt-owner shape — part of the FROZEN v5 end-state, folded
        // into MIGRATION_4_5 (never a later bump).
        db.execSQL("ALTER TABLE test_attempts ADD COLUMN aplusAnchorScheduleId TEXT")
        db.execSQL("ALTER TABLE test_attempts ADD COLUMN aplusAnchorItemId TEXT")
        db.execSQL("ALTER TABLE test_attempts ADD COLUMN aplusAnchorVersion INTEGER")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `unverified_attempt_records` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `attemptId` INTEGER NOT NULL,
                `reason` TEXT NOT NULL,
                `evidenceDigest` TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_unverified_attempt_records_attemptId` " +
                "ON `unverified_attempt_records`(`attemptId`)"
        )

        // ---- Durable observation + completion receipt carriers (R37, Sol R36 P1-1: recovery must
        //      re-decide from durable DB data, not a stale live source). Folded INTO v5 per INV-24. ----
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `durable_observation_records` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `attemptId` INTEGER NOT NULL,
                `phase` TEXT NOT NULL,
                `leaseId` TEXT NOT NULL,
                `acceptedIntentHash` TEXT NOT NULL,
                `coverage` TEXT NOT NULL,
                `verificationLevel` TEXT NOT NULL,
                `deliveryMode` TEXT NOT NULL,
                `isMock` INTEGER,
                `scheduleDecision` TEXT NOT NULL,
                `effectiveLat` REAL,
                `effectiveLng` REAL,
                `environmentRevision` INTEGER NOT NULL,
                `environmentFingerprint` TEXT NOT NULL,
                `observedAtElapsedRealtimeMs` INTEGER NOT NULL,
                `observedAtEpochMs` INTEGER NOT NULL,
                `continuitySinceElapsedRealtimeMs` INTEGER,
                `continuitySinceEpochMs` INTEGER,
                `evidenceRefsJson` TEXT NOT NULL,
                `evidenceRefs` TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_durable_observation_records_attemptId_phase` " +
                "ON `durable_observation_records`(`attemptId`, `phase`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_durable_observation_records_attemptId` " +
                "ON `durable_observation_records`(`attemptId`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `durable_completion_receipts` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `attemptId` INTEGER NOT NULL,
                `completionEvidenceWire` INTEGER NOT NULL,
                `acceptedIntentHash` TEXT NOT NULL,
                `leaseId` TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_durable_completion_receipts_attemptId` " +
                "ON `durable_completion_receipts`(`attemptId`)"
        )

        // ---- Room-backed operation/recovery/release receipts (R43, Sol GREEN-review P1-1):
        //      the production DurableRecoveryLog binding. Folded INTO v5 (pre-release schema). ----
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `operation_receipts` (
                `idempotencyKey` TEXT PRIMARY KEY NOT NULL,
                `requestDigest` TEXT NOT NULL,
                `resultOutcome` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `leaseId` TEXT,
                `operationId` TEXT,
                `acceptedIntentHash` TEXT,
                `appliedAtEpochMs` INTEGER,
                `environmentRevision` INTEGER,
                `verificationLevelWire` INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `recovery_checkpoints` (
                `attemptId` INTEGER PRIMARY KEY NOT NULL,
                `lastDurableStage` TEXT NOT NULL,
                `receiptKey` TEXT,
                `recordedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `release_receipts` (
                `idempotencyKey` TEXT PRIMARY KEY NOT NULL,
                `leaseId` TEXT NOT NULL,
                `releaseDigest` TEXT NOT NULL,
                `resultOutcome` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

/**
 * v5 → v6 (F-19, 2026-08-27): DELIBERATE no-op — v6 is table-for-table identical to v5's committed
 * end-state. The bump exists only to re-open a migration window after the ZY22 drift incident
 * (a `user_version = 5` database from an uncommitted ~8/01 build, identity hash `dea7bb12…` ≠
 * committed `0d083aef…`; Room cannot migrate inside one version).
 *
 * Why empty is CORRECT here, per entry path:
 *  - v2–v4 ladder (2→3→4→5→6): MIGRATION_4_5 is maintained in lockstep with the entity DDL
 *    (see its R3-1 note), so the schema arriving at this step already matches v6 — data is
 *    preserved and Room's post-migration validation passes. NOT blind-rebuilt.
 *  - Healthy committed v5 (`0d083aef…`, installs from afecace..HEAD): same shape as v6 — data
 *    preserved, validation passes.
 *  - Drifted v5 (the incident) NEVER reaches this migration: the AppDatabase v5-drift quarantine
 *    deletes it pre-open (operator 2026-08-27T09:46Z plan-B ruling; INV-24 exemption, Auto only).
 *    Without the quarantine an empty 5→6 would instead die in post-migration validation
 *    ("Migration didn't properly handle") — fallbackToDestructiveMigration does NOT rescue a
 *    failed migration, it only fires when NO path exists (v1/unknown versions).
 *
 * Adding real schema changes later? Put them in a NEW version (6→7) with a real migration —
 * do not grow this one.
 *
 * # v5→v6（F-19）：故意空迁移——v6 与 v5 提交终态逐表相同，bump 只为重开迁移窗口。
 * # 阶梯与健康 v5 保数据走这里；漂移 v5 由隔离区在开库前删除，永远到不了这一步。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Intentionally empty — see the chronicle above.
    }
}

/**
 * v6 → v7: freeze the selected applicationId P on the plan and recovery chain, plus immutable
 * signer owner S on attempt/lease-scoped rows. Every column is nullable with NO SQL default and NO
 * backfill: an old row cannot borrow P or S from the build/package that happens to open it. The plan
 * intentionally remains P-only so an approved rotation may own a future attempt while old leases
 * stay bound to their original S. Legacy in-flight rows remain explicit unknown and fail closed.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE location_plans ADD COLUMN providerApplicationId TEXT")
        db.execSQL("ALTER TABLE test_attempts ADD COLUMN providerApplicationId TEXT")
        db.execSQL("ALTER TABLE operation_receipts ADD COLUMN providerApplicationId TEXT")
        db.execSQL("ALTER TABLE recovery_checkpoints ADD COLUMN providerApplicationId TEXT")
        db.execSQL("ALTER TABLE release_receipts ADD COLUMN providerApplicationId TEXT")
        db.execSQL("ALTER TABLE test_attempts ADD COLUMN providerSignerDigest TEXT")
        db.execSQL("ALTER TABLE operation_receipts ADD COLUMN providerSignerDigest TEXT")
        db.execSQL("ALTER TABLE recovery_checkpoints ADD COLUMN providerSignerDigest TEXT")
        db.execSQL("ALTER TABLE release_receipts ADD COLUMN providerSignerDigest TEXT")
    }
}
