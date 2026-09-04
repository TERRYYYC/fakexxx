package io.github.terryyyc.fakexxx.integration.pr63issue66

import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostReceiptModeGuardTest {

    @Test
    fun `aggregate validator requires exact clean source provenance in schema v3`() {
        val repo = findRepoRoot()
        val verifier = repo.resolve("scripts/verify-a-plus.sh").readText()
        val functionSource = verifyHostReceiptFunction(repo)

        assertTrue("host receipt schema must advance to v3", "\"schemaVersion\": 3" in functionSource)
        listOf("sourceHead", "sourceTree", "sourceState", "runnerSha256", "runId").forEach {
            field ->
            assertTrue("host receipt validator is missing $field", "\"$field\"" in functionSource)
        }
        assertTrue(
            "validator must invoke git through a fixed empty environment",
            functionSource.contains("/usr/bin/env") && functionSource.contains("\"-i\""),
        )
        assertTrue(
            "validator must disable replacement objects in fixed git",
            functionSource.contains("/usr/bin/git") && functionSource.contains("--no-replace-objects"),
        )
        assertTrue(
            "validator must verify the repository root and clean working tree",
            functionSource.contains("--show-toplevel") &&
                functionSource.contains("--porcelain=v1") &&
                functionSource.contains("--untracked-files=all"),
        )
        assertTrue(
            "validator must reject hidden index flags before trusting clean status",
            functionSource.contains("ls-files\", \"-v\", \"-z\", \"--cached") &&
                functionSource.contains("record[:2] != b\"H \""),
        )
        assertTrue(
            "validator must bind the current runner digest to the reviewed HEAD blob",
            functionSource.contains("\"cat-file\",") &&
                functionSource.contains("f\"{source_head}:{canonical_runner_relative}\"") &&
                functionSource.contains("runner_sha256 != reviewed_runner_sha256"),
        )
        assertTrue(
            "validator must stably read the canonical runner without following it",
            functionSource.contains("runner_fd") && functionSource.contains("os.O_NOFOLLOW"),
        )
        assertTrue(
            "validator must recheck source provenance before success",
            functionSource.contains("source_binding_before") &&
                functionSource.contains("source_binding_after"),
        )
        assertTrue(
            "production validator call must pin repository and runner arguments",
            verifier.contains(
                "verify_host_receipt \"\$HOST_RECEIPT\" \"\$HOST_RECEIPT_LOCK\" " +
                    "\"\$REPO_ROOT\" \"\$HOST_GATE_RUNNER\"",
            ),
        )

        val source = cleanSourceFixture()
        val stateDir = Files.createTempDirectory("issue66-host-receipt-provenance-").toRealPath()
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        try {
            Files.write(receipt, (validReceipt(source) + "\n").toByteArray())
            val validation = validatorProcess(functionSource, receipt, lock, source).apply {
                environment()["GIT_DIR"] = "/definitely/not/the/source-repository"
                environment()["GIT_WORK_TREE"] = "/definitely/not/the/source-worktree"
                environment()["GIT_OBJECT_DIRECTORY"] = "/definitely/not/the/object-store"
                environment()["GIT_CONFIG_GLOBAL"] = "/definitely/not/a/config"
                environment()["PATH"] = stateDir.toString()
            }.start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 0, validation.waitFor())
            assertTrue(output, output.contains("schemaVersion=3"))
            assertTrue(output, output.contains("sourceState=CLEAN"))
            assertTrue("successful provenance validation leaked its lock", !Files.exists(lock))
        } finally {
            stateDir.toFile().deleteRecursively()
            source.close()
        }
    }

    @Test
    fun `aggregate validator rejects a group or world writable receipt`() {
        val repo = findRepoRoot()
        val functionSource = verifyHostReceiptFunction(repo)
        val source = cleanSourceFixture()
        assertTrue(
            "receipt validator must bind ownership to the current host user",
            functionSource.contains("receipt_fd_state.st_uid != os.geteuid()"),
        )
        assertTrue(
            "receipt validator must reject group or world write authority",
            functionSource.contains("stat.S_IMODE(receipt_fd_state.st_mode) & 0o022"),
        )

        val stateDir = Files.createTempDirectory("issue66-host-receipt-mode-").toRealPath()
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        val probe = stateDir.resolve("verify-host-receipt.sh")
        try {
            Files.write(receipt, (validReceipt(source) + "\n").toByteArray())
            Files.setPosixFilePermissions(
                receipt,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE,
                ),
            )
            Files.write(
                probe,
                (
                    "#!/bin/bash\n" +
                        "set -uo pipefail\n" +
                        functionSource + "\n" +
                        "verify_host_receipt \"\$1\" \"\$2\" \"\$3\" \"\$4\"\n"
                    ).toByteArray(),
            )

            val validation = ProcessBuilder(
                "/bin/bash",
                probe.toString(),
                receipt.toString(),
                lock.toString(),
                source.root.toString(),
                source.runner.toString(),
            ).redirectErrorStream(true).start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 1, validation.waitFor())
            assertTrue(output, output.contains("receipt permissions or owner are unsafe"))
            assertTrue("unsafe-mode rejection leaked the validator lock", !Files.exists(lock))
        } finally {
            stateDir.toFile().deleteRecursively()
            source.close()
        }
    }

    @Test
    fun `aggregate validator rejects a group or world writable receipt parent`() {
        val repo = findRepoRoot()
        val functionSource = verifyHostReceiptFunction(repo)
        val source = cleanSourceFixture()
        val unsafeParentModes = listOf(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_WRITE,
            ),
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.OTHERS_WRITE,
            ),
        )
        try {
            unsafeParentModes.forEachIndexed { index, mode ->
                val stateDir = Files.createTempDirectory("issue66-host-receipt-parent-mode-$index-")
                    .toRealPath()
                val receipt = stateDir.resolve("host-gate-receipt.json")
                val lock = stateDir.resolve("host-gate.lock")
                try {
                    Files.write(receipt, (validReceipt(source) + "\n").toByteArray())
                    Files.setPosixFilePermissions(stateDir, mode)
                    val validation = validatorProcess(functionSource, receipt, lock, source).start()
                    val output = validation.inputStream.bufferedReader().use { it.readText() }
                    assertEquals("unsafe parent mode $index escaped:\n$output", 1, validation.waitFor())
                    assertTrue(output, output.contains("receipt parent"))
                    assertTrue("unsafe-parent rejection created a lock", !Files.exists(lock))
                } finally {
                    stateDir.toFile().deleteRecursively()
                }
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun `aggregate validator rejects a Darwin extended ACL on the receipt parent`() {
        if (!isDarwin()) return

        val repo = findRepoRoot()
        val functionSource = verifyHostReceiptFunction(repo)
        val source = cleanSourceFixture()
        val stateDir = Files.createTempDirectory("issue66-host-receipt-parent-acl-").toRealPath()
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        try {
            Files.write(receipt, (validReceipt(source) + "\n").toByteArray())
            addDarwinAcl(
                stateDir,
                "everyone allow list,search,add_file,add_subdirectory,file_inherit,directory_inherit",
            )

            val validation = validatorProcess(functionSource, receipt, lock, source).start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 1, validation.waitFor())
            assertTrue(output, output.contains("extended ACL"))
            assertTrue("unsafe parent ACL created a validator lock", !Files.exists(lock))
        } finally {
            stateDir.toFile().deleteRecursively()
            source.close()
        }
    }

    @Test
    fun `aggregate validator rejects a Darwin extended ACL on the receipt`() {
        if (!isDarwin()) return

        val repo = findRepoRoot()
        val functionSource = verifyHostReceiptFunction(repo)
        val source = cleanSourceFixture()
        val stateDir = Files.createTempDirectory("issue66-host-receipt-file-acl-").toRealPath()
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        try {
            Files.write(receipt, (validReceipt(source) + "\n").toByteArray())
            addDarwinAcl(
                receipt,
                "everyone allow read,write,append,readattr,writeattr,readextattr,writeextattr",
            )

            val validation = validatorProcess(functionSource, receipt, lock, source).start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 1, validation.waitFor())
            assertTrue(output, output.contains("extended ACL"))
            assertTrue("unsafe receipt ACL leaked the validator lock", !Files.exists(lock))
        } finally {
            stateDir.toFile().deleteRecursively()
            source.close()
        }
    }

    @Test
    fun `runner rejects a Darwin inherited ACL before creating receipt state`() {
        if (!isDarwin()) return

        val repo = findRepoRoot()
        val runner = repo.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh").readText()
        val functionStart = runner.indexOf("prepare_private_directory() {")
        val functionEnd = runner.indexOf("\n}\n\ncreate_host_gate_lock() {", functionStart)
        check(functionStart >= 0 && functionEnd > functionStart) {
            "runner private-directory helper changed"
        }
        val functionSource = runner.substring(functionStart, functionEnd + 2)
        val stateRoot = Files.createTempDirectory("issue66-host-runner-parent-acl-").toRealPath()
        val anchor = Files.createDirectory(stateRoot.resolve("anchor"))
        val reports = Files.createDirectory(anchor.resolve("reports"))
        val receiptDir = reports.resolve("private")
        val probe = stateRoot.resolve("prepare-private-directory.sh")
        try {
            addDarwinAcl(
                reports,
                "everyone allow list,search,add_file,add_subdirectory,file_inherit,directory_inherit",
            )
            Files.write(
                probe,
                (
                    "#!/bin/bash\n" +
                        "set -uo pipefail\n" +
                        functionSource + "\n" +
                        "prepare_private_directory \"\$1\" \"\$2\"\n"
                    ).toByteArray(),
            )

            val process = ProcessBuilder(
                "/bin/bash",
                probe.toString(),
                anchor.toString(),
                "reports/private",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 1, process.waitFor())
            assertTrue(output, output.contains("extended ACL"))
            assertTrue("runner created state below an inherited ACL", !Files.exists(receiptDir))
        } finally {
            stateRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `runner ACL guards cover receipt parent lock owner and receipt`() {
        val repo = findRepoRoot()
        val runner = repo.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh").readText()

        fun functionSource(name: String, nextName: String): String {
            val start = runner.indexOf("$name() {")
            val end = runner.indexOf("\n}\n\n$nextName() {", start)
            check(start >= 0 && end > start) { "runner helper $name changed" }
            return runner.substring(start, end + 2)
        }

        val directoryPrep = functionSource("prepare_private_directory", "create_host_gate_lock")
        val lockCreation = functionSource("create_host_gate_lock", "write_private_file_exclusively")
        val ownerCreation = functionSource("write_private_file_exclusively", "read_source_provenance")
        val receiptPublication = functionSource("write_receipt_atomically", "release_host_gate_lock")
        val lockRelease = functionSource("release_host_gate_lock", "cleanup_host_gate_lock")

        assertTrue("receipt parent lacks an ACL guard", "has_extended_acl(current_path)" in directoryPrep)
        assertTrue("host lock lacks an ACL guard", "has_extended_acl(lock_path)" in lockCreation)
        assertTrue("host lock owner lacks an ACL guard", "has_extended_acl(output_path)" in ownerCreation)
        assertTrue("published receipt lacks an ACL guard", "has_extended_acl(receipt_path)" in receiptPublication)
        assertTrue(
            "lock cleanup does not recheck owner and receipt ACLs",
            "has_extended_acl(owner_path)" in lockRelease &&
                "has_extended_acl(receipt_path)" in lockRelease,
        )
    }

    @Test
    fun `aggregate validator refuses every intermediate receipt parent symlink`() {
        val repo = findRepoRoot()
        val functionSource = verifyHostReceiptFunction(repo)
        assertTrue(
            "validator must walk directory components without following links",
            functionSource.contains("open_directory_nofollow"),
        )
        val source = cleanSourceFixture()
        val stateRoot = Files.createTempDirectory("issue66-host-receipt-parent-walk-").toRealPath()
        val external = Files.createTempDirectory("issue66-host-receipt-parent-external-").toRealPath()
        val safe = Files.createDirectory(stateRoot.resolve("safe"))
        val redirected = safe.resolve("redirected")
        val probe = stateRoot.resolve("verify-host-receipt.sh")
        try {
            Files.createSymbolicLink(redirected, external)
            val receipt = redirected.resolve("host-gate-receipt.json")
            val lock = redirected.resolve("host-gate.lock")
            Files.write(external.resolve("host-gate-receipt.json"), (validReceipt(source) + "\n").toByteArray())
            Files.write(
                probe,
                (
                    "#!/bin/bash\n" +
                        "set -uo pipefail\n" +
                        functionSource + "\n" +
                        "verify_host_receipt \"\$1\" \"\$2\" \"\$3\" \"\$4\"\n"
                    ).toByteArray(),
            )

            val validation = ProcessBuilder(
                "/bin/bash",
                probe.toString(),
                receipt.toString(),
                lock.toString(),
                source.root.toString(),
                source.runner.toString(),
            ).redirectErrorStream(true).start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 1, validation.waitFor())
            assertTrue(output, output.contains("receipt parent"))
            assertTrue("validator created a lock through an intermediate symlink", !Files.exists(external.resolve("host-gate.lock")))
        } finally {
            stateRoot.toFile().deleteRecursively()
            external.toFile().deleteRecursively()
            source.close()
        }
    }

    @Test
    fun `aggregate validator rereads and rejects same-inode mutation after contract parsing`() {
        val repo = findRepoRoot()
        val functionSource = verifyHostReceiptFunction(repo)
        val source = cleanSourceFixture()
        val recheckMarker = "receipt_recheck_error = None\n"
        val rendezvous =
            "with open(os.environ[\"HOST_RECEIPT_POST_CONTRACT_READY\"], \"w\") as ready_file:\n" +
                "    ready_file.write(\"ready\\n\")\n" +
                "while not os.path.exists(os.environ[\"HOST_RECEIPT_POST_CONTRACT_RELEASE\"]):\n" +
                "    __import__(\"time\").sleep(0.01)\n" +
                recheckMarker
        val instrumented = functionSource.replace(recheckMarker, rendezvous)
        assertTrue("post-contract reread seam is missing", instrumented != functionSource)

        val stateDir = Files.createTempDirectory("issue66-host-receipt-same-inode-").toRealPath()
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        val probe = stateDir.resolve("verify-host-receipt-rendezvous.sh")
        val ready = stateDir.resolve("post-contract-ready")
        val release = stateDir.resolve("post-contract-release")
        var validator: Process? = null
        try {
            Files.write(receipt, (validReceipt(source) + "\n").toByteArray())
            Files.write(
                probe,
                (
                    "#!/bin/bash\n" +
                        "set -uo pipefail\n" +
                        instrumented + "\n" +
                        "verify_host_receipt \"\$1\" \"\$2\" \"\$3\" \"\$4\"\n"
                    ).toByteArray(),
            )
            validator = ProcessBuilder(
                "/bin/bash",
                probe.toString(),
                receipt.toString(),
                lock.toString(),
                source.root.toString(),
                source.runner.toString(),
            ).redirectErrorStream(true).apply {
                environment()["HOST_RECEIPT_POST_CONTRACT_READY"] = ready.toString()
                environment()["HOST_RECEIPT_POST_CONTRACT_RELEASE"] = release.toString()
            }.start()
            waitForPath(ready)

            val originalKey = Files.readAttributes(
                receipt,
                java.nio.file.attribute.BasicFileAttributes::class.java,
            ).fileKey()
            Files.write(
                receipt,
                "{}\n".toByteArray(),
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            val mutatedKey = Files.readAttributes(
                receipt,
                java.nio.file.attribute.BasicFileAttributes::class.java,
            ).fileKey()
            assertEquals("mutation replaced the receipt inode", originalKey, mutatedKey)
            Files.createFile(release)

            val output = validator.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 1, validator.waitFor())
            assertTrue(output, output.contains("receipt identity changed after contract validation"))
            assertTrue("same-inode rejection leaked the validator lock", !Files.exists(lock))
        } finally {
            if (!Files.exists(release)) Files.createFile(release)
            validator?.let { process ->
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor()
                }
            }
            stateDir.toFile().deleteRecursively()
            source.close()
        }
    }

    @Test
    fun `aggregate validator rejects forged provenance fields and a dirty source tree`() {
        val repo = findRepoRoot()
        val functionSource = verifyHostReceiptFunction(repo)
        val source = cleanSourceFixture()
        try {
            val valid = validReceipt(source)
            val mutations = listOf(
                valid.replace("\"schemaVersion\":3", "\"schemaVersion\":2"),
                valid.replace(source.head, "0".repeat(40)),
                valid.replace(source.tree, "1".repeat(40)),
                valid.replace("\"sourceState\":\"CLEAN\"", "\"sourceState\":\"DIRTY\""),
                valid.replace(source.runnerSha256, "2".repeat(64)),
                valid.replace("\"runId\":\"0123456789abcdef0123456789abcdef\"", "\"runId\":\"BAD\""),
                valid.dropLast(1) + ",\"unexpected\":true}",
            )
            mutations.forEachIndexed { index, mutated ->
                val stateDir = Files.createTempDirectory("issue66-host-provenance-mutation-$index-")
                    .toRealPath()
                val receipt = stateDir.resolve("host-gate-receipt.json")
                val lock = stateDir.resolve("host-gate.lock")
                try {
                    Files.write(receipt, (mutated + "\n").toByteArray())
                    val validation = validatorProcess(functionSource, receipt, lock, source).start()
                    val output = validation.inputStream.bufferedReader().use { it.readText() }
                    assertEquals("mutation $index escaped:\n$output", 1, validation.waitFor())
                    assertTrue("mutation $index leaked its validator lock", !Files.exists(lock))
                } finally {
                    stateDir.toFile().deleteRecursively()
                }
            }

            Files.write(source.root.resolve("untracked-source.txt"), "dirty\n".toByteArray())
            val dirtyStateDir = Files.createTempDirectory("issue66-host-provenance-dirty-").toRealPath()
            val dirtyReceipt = dirtyStateDir.resolve("host-gate-receipt.json")
            val dirtyLock = dirtyStateDir.resolve("host-gate.lock")
            try {
                Files.write(dirtyReceipt, (valid + "\n").toByteArray())
                val validation = validatorProcess(
                    functionSource,
                    dirtyReceipt,
                    dirtyLock,
                    source,
                ).start()
                val output = validation.inputStream.bufferedReader().use { it.readText() }
                assertEquals(output, 1, validation.waitFor())
                assertTrue(output, output.contains("source provenance invalid"))
                assertTrue(output, output.contains("not clean"))
                assertTrue("dirty-source rejection leaked its validator lock", !Files.exists(dirtyLock))
            } finally {
                dirtyStateDir.toFile().deleteRecursively()
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun `runner source provenance rejects index-hidden tracked changes`() {
        val repo = findRepoRoot()
        val provenanceSource = runHostGateSourceFunction(repo)

        listOf("--assume-unchanged", "--skip-worktree").forEach { indexFlag ->
            val source = cleanSourceFixture()
            val probe = Files.createTempFile("issue66-read-source-provenance-", ".sh")
            try {
                runGit(
                    source.root,
                    "update-index",
                    indexFlag,
                    "--",
                    "integration-tests/pr63-on-issue66/run-host-gate.sh",
                )
                Files.write(
                    source.runner,
                    "# hidden tracked mutation\n".toByteArray(),
                    StandardOpenOption.APPEND,
                )
                assertEquals(
                    "$indexFlag fixture is hidden from ordinary status",
                    "",
                    runGit(source.root, "status", "--porcelain=v1", "--untracked-files=all"),
                )
                Files.write(
                    probe,
                    (
                        "#!/bin/bash\n" +
                            "set -uo pipefail\n" +
                            "repo_root=\"\$1\"\n" +
                            "runner_path=\"\$2\"\n" +
                            provenanceSource + "\n" +
                            "read_source_provenance\n"
                        ).toByteArray(),
                )

                val validation = ProcessBuilder(
                    "/bin/bash",
                    probe.toString(),
                    source.root.toString(),
                    source.runner.toString(),
                ).redirectErrorStream(true).start()
                val output = validation.inputStream.bufferedReader().use { it.readText() }
                assertEquals("$indexFlag escaped runner source binding:\n$output", 1, validation.waitFor())
            } finally {
                Files.deleteIfExists(probe)
                source.close()
            }
        }
    }

    @Test
    fun `host runner pins shell and PATH before resolving repository paths`() {
        val runner = findRepoRoot()
            .resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
            .readText()
        assertTrue("host runner must use the fixed system Bash", runner.startsWith("#!/bin/bash\n"))
        val pathPin = runner.indexOf("PATH=/usr/bin:/bin")
        val pathExport = runner.indexOf("export PATH", pathPin)
        val scriptDir = runner.indexOf("script_dir=")
        assertTrue(
            "host runner must export a fixed PATH before its first host command lookup",
            pathPin >= 0 && pathExport > pathPin && scriptDir > pathExport,
        )
        assertTrue(
            "collector selftest must run through fixed Bash",
            "/bin/bash \"\$repo_root/scripts/selftest-issue66-moto-readonly-collector.sh\"" in runner,
        )
        assertTrue(
            "services selftest must run through fixed Bash",
            "/bin/bash \"\$repo_root/scripts/selftest-issue66-services-compatibility.sh\"" in runner,
        )
        assertTrue(
            "host runner must compare its stable bytes with the reviewed HEAD blob",
            "read_head_runner_sha256 \"\$source_head\" \"\$runner_path\"" in runner &&
                "cat-file blob \"\$source_head:\$runner_relative_path\"" in runner &&
                "\"\$reviewed_runner_sha256\" != \"\$runner_sha256\"" in runner,
        )
    }

    @Test
    fun `aggregate validator rejects index-hidden tracked changes`() {
        val repo = findRepoRoot()
        val functionSource = verifyHostReceiptFunction(repo)

        listOf("--assume-unchanged", "--skip-worktree").forEachIndexed { index, indexFlag ->
            val source = cleanSourceFixture()
            val stateDir = Files.createTempDirectory("issue66-host-index-flag-$index-").toRealPath()
            val receipt = stateDir.resolve("host-gate-receipt.json")
            val lock = stateDir.resolve("host-gate.lock")
            try {
                runGit(
                    source.root,
                    "update-index",
                    indexFlag,
                    "--",
                    "integration-tests/pr63-on-issue66/run-host-gate.sh",
                )
                Files.write(
                    source.runner,
                    "# hidden tracked mutation\n".toByteArray(),
                    StandardOpenOption.APPEND,
                )
                assertEquals(
                    "$indexFlag fixture is hidden from ordinary status",
                    "",
                    runGit(source.root, "status", "--porcelain=v1", "--untracked-files=all"),
                )
                val forgedSource = source.copy(runnerSha256 = sha256(source.runner))
                Files.write(receipt, (validReceipt(forgedSource) + "\n").toByteArray())

                val validation = validatorProcess(
                    functionSource,
                    receipt,
                    lock,
                    forgedSource,
                ).start()
                val output = validation.inputStream.bufferedReader().use { it.readText() }
                assertEquals("$indexFlag escaped receipt source binding:\n$output", 1, validation.waitFor())
                assertTrue(output, output.contains("source provenance invalid"))
                assertTrue(output, output.contains("index"))
                assertTrue("$indexFlag rejection leaked validator lock", !Files.exists(lock))
            } finally {
                stateDir.toFile().deleteRecursively()
                source.close()
            }
        }
    }

    @Test
    fun `aggregate validator rechecks source and runner before success`() {
        val repo = findRepoRoot()
        val functionSource = verifyHostReceiptFunction(repo)
        val recheckMarker = "source_binding_after = None\n"
        val rendezvous =
            "with open(os.environ[\"HOST_SOURCE_RECHECK_READY\"], \"w\") as ready_file:\n" +
                "    ready_file.write(\"ready\\n\")\n" +
                "while not os.path.exists(os.environ[\"HOST_SOURCE_RECHECK_RELEASE\"]):\n" +
                "    __import__(\"time\").sleep(0.01)\n" +
                recheckMarker
        val instrumented = functionSource.replace(recheckMarker, rendezvous)
        assertTrue("source provenance recheck seam is missing", instrumented != functionSource)

        val source = cleanSourceFixture()
        val stateDir = Files.createTempDirectory("issue66-host-source-recheck-").toRealPath()
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        val probe = stateDir.resolve("verify-host-receipt-rendezvous.sh")
        val ready = stateDir.resolve("source-recheck-ready")
        val release = stateDir.resolve("source-recheck-release")
        var validator: Process? = null
        try {
            Files.write(receipt, (validReceipt(source) + "\n").toByteArray())
            Files.write(
                probe,
                (
                    "#!/bin/bash\n" +
                        "set -uo pipefail\n" +
                        instrumented + "\n" +
                        "verify_host_receipt \"\$1\" \"\$2\" \"\$3\" \"\$4\"\n"
                    ).toByteArray(),
            )
            validator = ProcessBuilder(
                "/bin/bash",
                probe.toString(),
                receipt.toString(),
                lock.toString(),
                source.root.toString(),
                source.runner.toString(),
            ).redirectErrorStream(true).apply {
                environment()["HOST_SOURCE_RECHECK_READY"] = ready.toString()
                environment()["HOST_SOURCE_RECHECK_RELEASE"] = release.toString()
            }.start()
            waitForPath(ready)
            Files.write(
                source.runner,
                "# changed during validation\n".toByteArray(),
                StandardOpenOption.APPEND,
            )
            Files.createFile(release)

            val output = validator.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 1, validator.waitFor())
            assertTrue(output, output.contains("source provenance changed"))
            assertTrue("source-recheck rejection leaked its validator lock", !Files.exists(lock))
        } finally {
            if (!Files.exists(release)) Files.createFile(release)
            validator?.let { process ->
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor()
                }
            }
            stateDir.toFile().deleteRecursively()
            source.close()
        }
    }

    private fun validatorProcess(
        functionSource: String,
        receipt: Path,
        lock: Path,
        source: SourceFixture,
    ): ProcessBuilder {
        val probe = receipt.parent.resolve("verify-host-receipt.sh")
        Files.write(
            probe,
            (
                "#!/bin/bash\n" +
                    "set -uo pipefail\n" +
                    functionSource + "\n" +
                    "verify_host_receipt \"\$1\" \"\$2\" \"\$3\" \"\$4\"\n"
                ).toByteArray(),
        )
        return ProcessBuilder(
            "/bin/bash",
            probe.toString(),
            receipt.toString(),
            lock.toString(),
            source.root.toString(),
            source.runner.toString(),
        ).redirectErrorStream(true)
    }

    private fun isDarwin(): Boolean = System.getProperty("os.name") == "Mac OS X"

    private fun addDarwinAcl(path: Path, rule: String) {
        val process = ProcessBuilder("/bin/chmod", "+a", rule, path.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "could not install Darwin ACL fixture: $output" }
    }

    private fun cleanSourceFixture(): SourceFixture {
        val root = Files.createTempDirectory("issue66-host-source-").toRealPath()
        val runner = root.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
        Files.createDirectories(runner.parent)
        Files.write(runner, "#!/usr/bin/env bash\nexit 0\n".toByteArray())
        Files.setPosixFilePermissions(
            runner,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE,
            ),
        )
        runGit(root, "init", "-q")
        runGit(root, "config", "user.name", "Receipt Test")
        runGit(root, "config", "user.email", "receipt-test@example.invalid")
        runGit(root, "add", "--", ".")
        runGit(root, "commit", "-q", "-m", "fixture")
        return SourceFixture(
            root = root,
            runner = runner,
            head = runGit(root, "rev-parse", "HEAD"),
            tree = runGit(root, "rev-parse", "HEAD^{tree}"),
            runnerSha256 = sha256(runner),
        )
    }

    private fun runGit(root: Path, vararg args: String): String {
        val process = ProcessBuilder(
            listOf("/usr/bin/git", "-C", root.toString()) + args,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output.trim()
    }

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path))
        .joinToString("") { "%02x".format(it) }

    private fun validReceipt(source: SourceFixture): String =
        "{" +
            "\"schemaVersion\":3," +
            "\"hostIntegration\":\"PASS\"," +
            "\"issue66Ac7\":\"NOT_PASSED\"," +
            "\"emulator\":\"NOT_RUN\"," +
            "\"physicalDevice\":\"NOT_RUN\"," +
            "\"deviceFull\":\"BLOCKED\"," +
            "\"overall\":\"BLOCKED\"," +
            "\"sourceState\":\"CLEAN\"," +
            "\"reason\":\"HOST_GATE_HAS_NO_DEVICE_EVIDENCE__BOTH_ADMISSION_LISTS_EMPTY__" +
            "ACTIVATION_CLEANUP_REBOOTS_AND_ADVERSARIAL_MUTATIONS_REQUIRE_ADDITIONAL_AUTHORIZATION\"," +
            "\"sourceHead\":\"${source.head}\"," +
            "\"sourceTree\":\"${source.tree}\"," +
            "\"runnerSha256\":\"${source.runnerSha256}\"," +
            "\"runId\":\"0123456789abcdef0123456789abcdef\"}"

    private data class SourceFixture(
        val root: Path,
        val runner: Path,
        val head: String,
        val tree: String,
        val runnerSha256: String,
    ) : AutoCloseable {
        override fun close() {
            root.toFile().deleteRecursively()
        }
    }

    private fun verifyHostReceiptFunction(repo: Path): String {
        val verifier = repo.resolve("scripts/verify-a-plus.sh").readText()
        val functionStart = verifier.indexOf("verify_host_receipt() {")
        val functionEndMarker = "\n}\n\nprintf 'verify-a-plus: stage=%s\\n'"
        val functionEnd = verifier.indexOf(functionEndMarker, functionStart)
        check(functionStart >= 0) { "host receipt validator function is missing" }
        check(functionEnd >= 0) { "host receipt validator end marker is missing" }
        return verifier.substring(functionStart, functionEnd + 2)
    }

    private fun runHostGateSourceFunction(repo: Path): String {
        val runner = repo.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh").readText()
        val provenanceStart = runner.indexOf("read_source_provenance() {")
        val provenanceEnd = runner.indexOf("\n}\n\nnew_run_id()", provenanceStart)
        val digestStart = runner.indexOf("read_runner_sha256() {")
        val digestEnd = runner.indexOf("\n}\n\nwrite_receipt_atomically()", digestStart)
        check(provenanceStart >= 0 && provenanceEnd >= 0) {
            "host-gate source provenance function is missing"
        }
        check(digestStart >= 0 && digestEnd >= 0) {
            "host-gate runner digest function is missing"
        }
        return runner.substring(provenanceStart, provenanceEnd + 2) + "\n\n" +
            runner.substring(digestStart, digestEnd + 2)
    }

    private fun findRepoRoot(): Path {
        var candidate = Paths.get("").toAbsolutePath().normalize()
        while (candidate.parent != null) {
            if (candidate.resolve("apps/cellrebel-auto").isDirectory() &&
                candidate.resolve("apps/qianwangyou").isDirectory()
            ) return candidate
            candidate = candidate.parent
        }
        error("could not locate fakexxx repository root")
    }

    private fun waitForPath(path: Path) {
        repeat(250) {
            if (Files.exists(path)) return
            Thread.sleep(20)
        }
        error("timed out waiting for $path")
    }
}
