package io.github.terryyyc.fakexxx.integration.pr63issue66

import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostReceiptModeGuardTest {

    @Test
    fun `aggregate validator rejects a PASS receipt whose Gradle attestations are missing`() {
        listOf("auto", "qwy", "harness").forEach { stage ->
            assertReceiptRejectsGradleAttestationMutation { stateDir ->
                Files.delete(stateDir.resolve("gradle-attestation-$stage-$VALID_RUN_ID.txt"))
            }
        }
    }

    @Test
    fun `aggregate validator rejects a PASS receipt whose Gradle attestations are forged`() {
        listOf("auto", "qwy", "harness").forEach { stage ->
            assertReceiptRejectsGradleAttestationMutation { stateDir ->
                Files.write(
                    stateDir.resolve("gradle-attestation-$stage-$VALID_RUN_ID.txt"),
                    "forged-$stage\n".toByteArray(),
                    StandardOpenOption.TRUNCATE_EXISTING,
                )
            }
        }
    }

    @Test
    fun `aggregate validator rejects a PASS receipt whose Gradle attestation is tampered`() {
        listOf("auto", "qwy", "harness").forEach { stage ->
            assertReceiptRejectsGradleAttestationMutation { stateDir ->
                Files.write(
                    stateDir.resolve("gradle-attestation-$stage-$VALID_RUN_ID.txt"),
                    "tampered-after-publication\n".toByteArray(),
                    StandardOpenOption.APPEND,
                )
            }
        }
    }

    @Test
    fun `aggregate validator binds every Gradle attestation field to schema v2`() {
        val functionSource = verifyHostReceiptFunction(findRepoRoot())
        val source = cleanSourceFixture()
        try {
            val mutations = listOf<Pair<String, (String) -> String>>(
                "wrong schema" to { it.replace("schemaVersion=2", "schemaVersion=1") },
                "wrong run id" to { it.replace("runId=$VALID_RUN_ID", "runId=${"d".repeat(32)}") },
                "wrong stage" to { it.replace("stage=auto", "stage=qwy") },
                "wrong task" to { it.replace(":app:testDebugUnitTest", ":app:testReleaseUnitTest") },
                "wrong JDK home" to { it.replace("/jdk-runtime.${"c".repeat(32)}/home", "/jdk-runtime.${"d".repeat(32)}/home") },
                "wrong JDK profile" to { it.replace(VALID_JDK_PROFILE_ID, "linux-x86_64-eclipse-temurin-17.0.20.1+1") },
                "wrong JDK runtime" to { it.replace(VALID_JDK_RUNTIME_VERSION, "17.0.19+10") },
                "wrong JDK tree" to { it.replace(VALID_JDK_TREE_SHA256, "e".repeat(64)) },
                "vendor mismatch" to { it.replace("javaVmVendor=Eclipse Adoptium", "javaVmVendor=Homebrew") },
                "wrong Java major" to { it.replace("jdkMajor=17", "jdkMajor=21") },
                "wrong launcher major" to { it.replace("testLauncherMajor=17", "testLauncherMajor=21") },
                "zero tests" to { it.replace("testCount=1", "testCount=0") },
                "reported failure" to { it.replace("failureCount=0", "failureCount=1") },
                "wrong classes" to {
                    Regex("(?m)^classes=.*$").replace(it, "classes=forged.Class")
                },
                "unqualified observed class" to {
                    it.replace(
                        "classes=com.example.cellrebelauto.automation.ProviderPrincipalRoutingRedTest",
                        "classes=UnqualifiedEvidence," +
                            "com.example.cellrebelauto.automation.ProviderPrincipalRoutingRedTest",
                    )
                },
                "duplicate key" to { it.replace("stage=auto\n", "stage=auto\nstage=auto\n") },
                "extra key" to { it.replace("classes=", "unexpected=value\nclasses=") },
                "missing key" to { it.replace("jdkMajor=17\n", "") },
            )
            mutations.forEach { (label, mutation) ->
                assertBoundAttestationMutationRejected(
                    functionSource,
                    source,
                    label,
                    mutation = mutation,
                )
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun `aggregate validator binds the complete Java identity to the registered profile`() {
        val functionSource = verifyHostReceiptFunction(findRepoRoot())
        val source = cleanSourceFixture()
        try {
            assertRegisteredJavaTupleMutationRejected(
                functionSource,
                source,
                "self-consistent Homebrew vendor pair",
                attestationMutation = {
                    it.replace("javaVendor=Eclipse Adoptium", "javaVendor=Homebrew")
                        .replace("javaVmVendor=Eclipse Adoptium", "javaVmVendor=Homebrew")
                },
            )
            assertRegisteredJavaTupleMutationRejected(
                functionSource,
                source,
                "Linux profile id spliced onto the Darwin tree",
                attestationMutation = {
                    it.replace(VALID_JDK_PROFILE_ID, LINUX_JDK_PROFILE_ID)
                },
                receiptMutation = {
                    it.replace(VALID_JDK_PROFILE_ID, LINUX_JDK_PROFILE_ID)
                },
            )
            assertRegisteredJavaTupleMutationRejected(
                functionSource,
                source,
                "self-consistent unregistered runtime version",
                attestationMutation = {
                    it.replace(
                        "jdkRuntimeVersion=$VALID_JDK_RUNTIME_VERSION",
                        "jdkRuntimeVersion=17.0.19+9",
                    )
                },
                receiptMutation = {
                    it.replace(
                        "\"jdkRuntimeVersion\":\"$VALID_JDK_RUNTIME_VERSION\"",
                        "\"jdkRuntimeVersion\":\"17.0.19+9\"",
                    )
                },
            )
        } finally {
            source.close()
        }
    }

    @Test
    fun `aggregate validator rejects required test simple-name collisions from another package`() {
        val functionSource = verifyHostReceiptFunction(findRepoRoot())
        val source = cleanSourceFixture()
        try {
            GRADLE_ATTESTATION_SPECS.forEach { spec ->
                assertBoundAttestationMutationRejected(
                    functionSource,
                    source,
                    "${spec.stage} package collision",
                    spec.stage,
                ) { attestation ->
                    val firstClass = spec.classes.substringBefore(',')
                    val simpleName = firstClass.substringAfterLast('.')
                    attestation.replace(firstClass, "collision.example.$simpleName")
                }
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun `aggregate validator rejects placeholder Gradle attestation hashes`() {
        val functionSource = verifyHostReceiptFunction(findRepoRoot())
        val source = cleanSourceFixture()
        val stateDir = Files.createTempDirectory("issue66-host-gradle-placeholder-").toRealPath()
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        try {
            val valid = validReceipt(source, stateDir)
            val placeholder = valid.replace(
                Regex("\"gradleAttestationAutoSha256\":\"[0-9a-f]{64}\""),
                "\"gradleAttestationAutoSha256\":\"NOT_AVAILABLE_YET\"",
            )
            Files.write(receipt, (placeholder + "\n").toByteArray())
            val validation = validatorProcess(functionSource, receipt, lock, source).start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 1, validation.waitFor())
            assertTrue(output, output.contains("schema mismatch"))
            assertTrue("placeholder rejection leaked validator lock", !Files.exists(lock))
        } finally {
            stateDir.toFile().deleteRecursively()
            source.close()
        }
    }

    @Test
    fun `aggregate validator opens Gradle attestations nofollow with exact private mode`() {
        val functionSource = verifyHostReceiptFunction(findRepoRoot())
        val source = cleanSourceFixture()
        try {
            listOf("symlink", "unsafe-mode").forEach { scenario ->
                val stateDir = Files.createTempDirectory("issue66-host-gradle-$scenario-").toRealPath()
                val receipt = stateDir.resolve("host-gate-receipt.json")
                val lock = stateDir.resolve("host-gate.lock")
                try {
                    val valid = validReceipt(source, stateDir)
                    val auto = stateDir.resolve("gradle-attestation-auto-$VALID_RUN_ID.txt")
                    if (scenario == "symlink") {
                        val target = stateDir.resolve("attestation-target.txt")
                        Files.move(auto, target)
                        Files.createSymbolicLink(auto, target.fileName)
                    } else {
                        Files.setPosixFilePermissions(
                            auto,
                            setOf(
                                PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE,
                                PosixFilePermission.GROUP_READ,
                            ),
                        )
                    }
                    Files.write(receipt, (valid + "\n").toByteArray())
                    val validation = validatorProcess(functionSource, receipt, lock, source).start()
                    val output = validation.inputStream.bufferedReader().use { it.readText() }
                    assertEquals("$scenario escaped:\n$output", 1, validation.waitFor())
                    assertTrue(output, output.contains("Gradle attestation"))
                    assertTrue("$scenario rejection leaked validator lock", !Files.exists(lock))
                } finally {
                    stateDir.toFile().deleteRecursively()
                }
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun `aggregate validator requires exact clean source provenance in schema v4`() {
        val repo = findRepoRoot()
        val verifier = repo.resolve("scripts/verify-a-plus.sh").readText()
        val functionSource = verifyHostReceiptFunction(repo)

        assertTrue("host receipt schema must advance to v4", "\"schemaVersion\": 4" in functionSource)
        listOf(
            "sourceHead",
            "sourceTree",
            "sourceState",
            "runnerSha256",
            "runId",
            "jdkProfileId",
            "jdkRuntimeVersion",
            "jdkTreeSha256",
            "gradleAttestationAutoSha256",
            "gradleAttestationQwySha256",
            "gradleAttestationHarnessSha256",
        ).forEach {
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
            "validator must resolve the repository root without executing attribute filters",
            functionSource.contains("--show-toplevel") &&
                !functionSource.contains("\"status\",") &&
                !functionSource.contains("--porcelain=v1"),
        )
        assertTrue(
            "validator must bind HEAD and index entries including flags, mode and blob",
            functionSource.contains("ls-tree\", \"-r\", \"-z\", \"--full-tree") &&
                functionSource.contains("ls-files\", \"--stage\", \"-v\", \"-z\", \"--cached") &&
                functionSource.contains("head_entries != index_entries"),
        )
        assertTrue(
            "validator must hash original tracked worktree bytes and symlink targets",
            functionSource.contains("verify_raw_worktree") &&
                functionSource.contains("git_blob_sha1") &&
                functionSource.contains("os.readlink"),
        )
        assertTrue(
            "raw source ACL checks must use pinned descriptors without a path cache",
            functionSource.contains("acl_get_fd_np") &&
                functionSource.contains("fd_has_extended_acl") &&
                functionSource.contains("validate_source_directory_fd") &&
                functionSource.contains("tracked regular file") &&
                functionSource.contains("symlink's replacement authority") &&
                !functionSource.contains("source_acl_cache"),
        )
        assertTrue(
            "raw source ACLs must be probed both before and after reads",
            Regex("fd_has_extended_acl\\(directory_fd\\)")
                .findAll(functionSource).count() >= 2 &&
                Regex("fd_has_extended_acl\\(file_fd\\)")
                    .findAll(functionSource).count() >= 2,
        )
        assertTrue(
            "untracked checks must consume only raw-bound committed gitignore files",
            functionSource.contains("--exclude-per-directory=.gitignore") &&
                functionSource.contains("untracked .gitignore"),
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
            Files.write(receipt, (validReceipt(source, receipt.parent) + "\n").toByteArray())
            val validation = validatorProcess(functionSource, receipt, lock, source).apply {
                environment()["GIT_DIR"] = "/definitely/not/the/source-repository"
                environment()["GIT_WORK_TREE"] = "/definitely/not/the/source-worktree"
                environment()["GIT_OBJECT_DIRECTORY"] = "/definitely/not/the/object-store"
                environment()["GIT_CONFIG_GLOBAL"] = "/definitely/not/a/config"
                environment()["PATH"] = stateDir.toString()
            }.start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 0, validation.waitFor())
            assertTrue(output, output.contains("schemaVersion=4"))
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
            Files.write(receipt, (validReceipt(source, stateDir) + "\n").toByteArray())
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
                    Files.write(receipt, (validReceipt(source, stateDir) + "\n").toByteArray())
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
        assumeTrue("Darwin ACL semantics are required", isDarwin())

        val repo = findRepoRoot()
        val functionSource = verifyHostReceiptFunction(repo)
        val source = cleanSourceFixture()
        val stateDir = Files.createTempDirectory("issue66-host-receipt-parent-acl-").toRealPath()
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        try {
            Files.write(receipt, (validReceipt(source, stateDir) + "\n").toByteArray())
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
        assumeTrue("Darwin ACL semantics are required", isDarwin())

        val repo = findRepoRoot()
        val functionSource = verifyHostReceiptFunction(repo)
        val source = cleanSourceFixture()
        val stateDir = Files.createTempDirectory("issue66-host-receipt-file-acl-").toRealPath()
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        try {
            Files.write(receipt, (validReceipt(source, stateDir) + "\n").toByteArray())
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
        assumeTrue("Darwin ACL semantics are required", isDarwin())

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
            Files.write(
                external.resolve("host-gate-receipt.json"),
                (validReceipt(source, external) + "\n").toByteArray(),
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
            Files.write(receipt, (validReceipt(source, stateDir) + "\n").toByteArray())
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
            repeat(7) { index ->
                val stateDir = Files.createTempDirectory("issue66-host-provenance-mutation-$index-")
                    .toRealPath()
                val receipt = stateDir.resolve("host-gate-receipt.json")
                val lock = stateDir.resolve("host-gate.lock")
                try {
                    val valid = validReceipt(source, stateDir)
                    val mutated = when (index) {
                        0 -> valid.replace("\"schemaVersion\":4", "\"schemaVersion\":3")
                        1 -> valid.replace(source.head, "0".repeat(40))
                        2 -> valid.replace(source.tree, "1".repeat(40))
                        3 -> valid.replace(
                            "\"sourceState\":\"CLEAN\"",
                            "\"sourceState\":\"DIRTY\"",
                        )
                        4 -> valid.replace(source.runnerSha256, "2".repeat(64))
                        5 -> valid.replace("\"runId\":\"$VALID_RUN_ID\"", "\"runId\":\"BAD\"")
                        else -> valid.dropLast(1) + ",\"unexpected\":true}"
                    }
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
                Files.write(
                    dirtyReceipt,
                    (validReceipt(source, dirtyStateDir) + "\n").toByteArray(),
                )
                val validation = validatorProcess(
                    functionSource,
                    dirtyReceipt,
                    dirtyLock,
                    source,
                ).start()
                val output = validation.inputStream.bufferedReader().use { it.readText() }
                assertEquals(output, 1, validation.waitFor())
                assertTrue(output, output.contains("source provenance invalid"))
                assertTrue(output, output.contains("non-committed, non-ignored source"))
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
        assertTrue(
            "host runner must use privileged fixed system Bash",
            runner.startsWith("#!/bin/bash -p\n"),
        )
        val pathPin = runner.indexOf("PATH=/usr/bin:/bin")
        val pathExport = runner.indexOf("export PATH", pathPin)
        val scriptDir = runner.indexOf("script_dir=")
        assertTrue(
            "host runner must export a fixed PATH before its first host command lookup",
            pathPin >= 0 && pathExport > pathPin && scriptDir > pathExport,
        )
        assertTrue(
            "collector selftest must run through fixed Bash",
            "/bin/bash -p \"\$repo_root/scripts/selftest-issue66-moto-readonly-collector.sh\"" in runner,
        )
        assertTrue(
            "services selftest must run through fixed Bash",
            "/bin/bash -p \"\$repo_root/scripts/selftest-issue66-services-compatibility.sh\"" in runner,
        )
        assertTrue(
            "host runner must compare its stable bytes with the reviewed HEAD blob",
            "read_head_runner_sha256 \"\$source_head\" \"\$runner_path\"" in runner &&
                "cat-file blob \"\$source_head:\$runner_relative_path\"" in runner &&
                "\"\$reviewed_runner_sha256\" != \"\$runner_sha256\"" in runner,
        )
    }

    @Test
    fun `aggregate gate controlled entrypoints ignore poison PATH and cannot reuse a prior receipt`() {
        val canonicalVerifier = findRepoRoot().resolve("scripts/verify-a-plus.sh")
        val verifierSource = canonicalVerifier.readText()
        assertTrue(
            "aggregate verifier must use the fixed system Bash",
            verifierSource.startsWith("#!/bin/bash -p\n"),
        )
        val environmentClear = verifierSource.indexOf("unset BASH_ENV ENV")
        val pathPin = verifierSource.indexOf("PATH=/usr/bin:/bin")
        val pathExport = verifierSource.indexOf("export PATH", pathPin)
        val repoLookup = verifierSource.indexOf("REPO_ROOT=")
        assertTrue(
            "aggregate verifier must clear startup hooks and export fixed PATH before repository lookup",
            environmentClear >= 0 && pathPin > environmentClear &&
                pathExport > pathPin && repoLookup > pathExport,
        )
        assertTrue(
            "repository lookup must use a fixed dirname",
            "/usr/bin/dirname \"\${BASH_SOURCE[0]}\"" in verifierSource,
        )
        assertTrue(
            "host gate dispatch must use a fixed Bash",
            "|/bin/bash -p ./integration-tests/pr63-on-issue66/run-host-gate.sh" in verifierSource,
        )

        val source = aggregateGateFixture(canonicalVerifier)
        val receipt = source.root.resolve(
            "integration-tests/pr63-on-issue66/harness/build/reports/" +
                "pr63-on-issue66/host-gate-receipt.json",
        )
        val poisonDir = Files.createTempDirectory("issue66-aggregate-poison-path-").toRealPath()
        val poisonBashMarker = poisonDir.resolve("poison-bash-ran")
        val poisonDirnameMarker = poisonDir.resolve("poison-dirname-ran")
        val poisonEnvironmentMarker = poisonDir.resolve("poison-environment-ran")
        val poisonEnvironment = poisonDir.resolve("poison-environment.sh")
        val currentRunMarker = source.root.resolve("current-host-gate-ran")
        val javaHome = requireJava17Home()
        val androidHome = requireAndroidSdk35()
        try {
            Files.createDirectories(receipt.parent)
            Files.setPosixFilePermissions(
                receipt.parent,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
            Files.write(receipt, (validReceipt(source, receipt.parent) + "\n").toByteArray())
            Files.setPosixFilePermissions(
                receipt,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
            writeExecutable(
                poisonDir.resolve("bash"),
                "#!/bin/bash\n/usr/bin/touch \"\${POISON_BASH_MARKER:?}\"\nexit 0\n",
            )
            writeExecutable(
                poisonDir.resolve("dirname"),
                "#!/bin/bash\n" +
                    "/usr/bin/touch \"\${POISON_DIRNAME_MARKER:?}\"\n" +
                    "exec /usr/bin/dirname \"\$@\"\n",
            )
            writeExecutable(
                poisonEnvironment,
                "/usr/bin/touch \"\${POISON_ENVIRONMENT_MARKER:?}\"\nexit 0\n",
            )

            val verifier = source.root.resolve("scripts/verify-a-plus.sh")
            // An explicitly forced non-privileged Bash is a same-EUID hostile caller and is
            // intentionally outside these controlled-entrypoint guarantees.
            val invocations = listOf(
                "kernel shebang" to listOf(verifier.toString(), "--stage", "full"),
                "privileged parent Bash" to
                    listOf("/bin/bash", "-p", verifier.toString(), "--stage", "full"),
            )
            invocations.forEach { (label, command) ->
                Files.deleteIfExists(poisonBashMarker)
                Files.deleteIfExists(poisonDirnameMarker)
                Files.deleteIfExists(poisonEnvironmentMarker)
                Files.deleteIfExists(currentRunMarker)
                val process = ProcessBuilder(command)
                    .directory(source.root.toFile())
                    .redirectErrorStream(true)
                    .apply {
                        environment()["PATH"] = poisonDir.toString()
                        environment()["JAVA_HOME"] = javaHome.toString()
                        environment()["ANDROID_HOME"] = androidHome.toString()
                        environment()["ADB"] = "/usr/bin/false"
                        environment()["POISON_BASH_MARKER"] = poisonBashMarker.toString()
                        environment()["POISON_DIRNAME_MARKER"] = poisonDirnameMarker.toString()
                        environment()["POISON_ENVIRONMENT_MARKER"] = poisonEnvironmentMarker.toString()
                        environment()["BASH_ENV"] = poisonEnvironment.toString()
                        environment()["ENV"] = poisonEnvironment.toString()
                    }
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals("$label reused prior receipt or skipped a failing current run:\n$output", 1, process.waitFor())
                assertTrue("$label did not execute the current host gate:\n$output", Files.exists(currentRunMarker))
                assertTrue("$label executed the poison Bash shim:\n$output", !Files.exists(poisonBashMarker))
                assertTrue("$label executed the poison dirname shim:\n$output", !Files.exists(poisonDirnameMarker))
                assertTrue(
                    "$label sourced poison BASH_ENV or ENV:\n$output",
                    !Files.exists(poisonEnvironmentMarker),
                )
            }
        } finally {
            poisonDir.toFile().deleteRecursively()
            source.close()
        }
    }

    @Test
    fun `Gradle test cache tracks host guard script bytes`() {
        val repo = findRepoRoot()
        val buildScript = repo
            .resolve("integration-tests/pr63-on-issue66/harness/build.gradle.kts")
            .readText()
        assertEquals(
            "Android SDK validator must occur exactly once in hostGuardExternalScripts",
            1,
            gradleFilesEntries(buildScript, "hostGuardExternalScripts")
                .count { it == "../../scripts/validate-android-sdk-runtime.py" },
        )
        assertEquals(
            "host guard inputs must be consumed by the one all-Test RELATIVE input block",
            emptyList<String>(),
            hostGuardInputContractViolations(buildScript),
        )
        val validatorEntry =
            "    rootProject.file(\"../../scripts/validate-android-sdk-runtime.py\"),"
        val inputEntry = "        hostGuardExternalScripts,"
        val relativeEntry =
            "        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)"
        val sdkInputMutants = linkedMapOf(
            "validator moved to comment" to buildScript.replace(
                validatorEntry,
                "    // validate-android-sdk-runtime.py is intentionally not an input",
            ),
            "validator moved outside collection" to buildScript.replace(
                validatorEntry,
                ")\nval decoyAndroidSdkValidator = " +
                    "rootProject.file(\"../../scripts/validate-android-sdk-runtime.py\")\n" +
                    "val ignoredHostGuardInputs = files(",
            ),
            "collection consumption moved to comment" to buildScript.replace(
                inputEntry,
                "        // hostGuardExternalScripts,",
            ),
            "RELATIVE sensitivity moved to comment" to buildScript.replace(
                relativeEntry,
                "        // .withPathSensitivity(" +
                    "org.gradle.api.tasks.PathSensitivity.RELATIVE)",
            ),
        )
        sdkInputMutants.forEach { (label, mutant) ->
            assertTrue("$label mutation is a no-op", mutant != buildScript)
            assertTrue(
                "$label escaped the exact Gradle input contract",
                hostGuardInputContractViolations(mutant).isNotEmpty(),
            )
        }
        listOf(
            "../../apps/cellrebel-auto/app/src/main",
            "../../apps/qianwangyou/app/src/main",
        ).forEach { sourceTree ->
            assertTrue("Gradle test inputs omit $sourceTree", sourceTree in buildScript)
        }
        assertTrue(
            "Gradle test inputs omit their own build script",
            "rootProject.file(\"harness/build.gradle.kts\")" in buildScript,
        )
        listOf(
            "2026-09-03-readback-and-config-isolation-plan.md",
            "issue71-binder-identity-emulator.md",
            "readback-isolation-combined-2026-09-03.md",
            "issue66-moto-readonly-preflight-runbook.md",
        ).forEach { documentName ->
            assertTrue("Gradle test inputs omit $documentName", documentName in buildScript)
        }
        val aggregateVerifier = repo.resolve("scripts/verify-a-plus.sh").readText()
        assertEquals(
            "aggregate attestation specs must name every required FQCN exactly",
            GRADLE_ATTESTATION_SPECS,
            parseAggregateAttestationSpecs(aggregateVerifier),
        )
        val hostEphemeralRequirement =
            "            \"io.github.terryyyc.fakexxx.integration.pr63issue66." +
                "HostEphemeralCleanupGuardTest\","
        val commentOnlyMutant = aggregateVerifier.replace(
            hostEphemeralRequirement,
            "            # HostEphemeralCleanupGuardTest requirement deliberately deleted",
        )
        assertTrue("HostEphemeral requirement mutation is a no-op", commentOnlyMutant != aggregateVerifier)
        assertTrue(
            "HostEphemeral same-name comment was not retained in the mutant",
            "# HostEphemeralCleanupGuardTest requirement deliberately deleted" in commentOnlyMutant,
        )
        val expectedWithoutHostEphemeral = GRADLE_ATTESTATION_SPECS.map { spec ->
            if (spec.stage != "harness") {
                spec
            } else {
                spec.copy(
                    classes = spec.classes.split(',')
                        .filterNot { it.endsWith(".HostEphemeralCleanupGuardTest") }
                        .joinToString(","),
                )
            }
        }
        assertEquals(
            "exact spec parser did not isolate only the deleted HostEphemeral requirement",
            expectedWithoutHostEphemeral,
            parseAggregateAttestationSpecs(commentOnlyMutant),
        )
        assertTrue(
            "same-name comment was accepted as the deleted HostEphemeral requirement",
            parseAggregateAttestationSpecs(commentOnlyMutant) != GRADLE_ATTESTATION_SPECS,
        )
    }

    @Test
    fun `active verification docs use controlled Bash entrypoints`() {
        val repo = findRepoRoot()
        val documents = listOf(
            repo.resolve("feature-specs/2026-09-03-readback-and-config-isolation-plan.md"),
            repo.resolve("docs/acceptance/issue71-binder-identity-emulator.md"),
            repo.resolve("docs/acceptance/readback-isolation-combined-2026-09-03.md"),
            repo.resolve("docs/acceptance/issue66-moto-readonly-preflight-runbook.md"),
        )
        val unsafeRecipe = Regex(
            "(?:^|[\\s`])(?:bash|/bin/bash)\\s+(?:\\./)?scripts/" +
                "(?:verify-a-plus\\.sh|selftest-issue66[^\\s`]*\\.sh)",
            RegexOption.MULTILINE,
        )
        val issue71Contents = documents[1].readText()
        val historicalStartMarker = "<!-- issue71-historical-verification-invocation:start -->"
        val historicalEndMarker = "<!-- issue71-historical-verification-invocation:end -->"
        val historicalStart = issue71Contents.indexOf(historicalStartMarker)
        val historicalEnd = issue71Contents.indexOf(historicalEndMarker)
        assertTrue(
            "issue71 evidence must have exactly one bounded historical invocation",
            historicalStart >= 0 && historicalEnd > historicalStart &&
                historicalStart == issue71Contents.lastIndexOf(historicalStartMarker) &&
                historicalEnd == issue71Contents.lastIndexOf(historicalEndMarker),
        )
        val historicalEndExclusive = historicalEnd + historicalEndMarker.length
        val historicalInvocation = issue71Contents.substring(historicalStart, historicalEndExclusive)
        val expectedHistoricalInvocation = """
            <!-- issue71-historical-verification-invocation:start -->
            历史实际调用记录（deprecated；不可复跑）：

            ```text
            env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
              ANDROID_HOME=/Users/terry/Library/Android/sdk \
              PATH='/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin':"${'$'}PATH" \
              bash scripts/verify-a-plus.sh --stage full
            ```
            <!-- issue71-historical-verification-invocation:end -->
        """.trimIndent()
        assertEquals(
            "issue71 historical invocation exemption changed or gained another recipe",
            expectedHistoricalInvocation,
            historicalInvocation,
        )
        val activeIssue71Contents = issue71Contents.removeRange(historicalStart, historicalEndExclusive)
        assertTrue(
            "issue71 active instructions must retain the current controlled rerun",
            "当前安全复跑方式：" in activeIssue71Contents &&
                "./scripts/verify-a-plus.sh --stage full" in activeIssue71Contents,
        )
        documents.forEachIndexed { index, document ->
            val contents = if (index == 1) activeIssue71Contents else document.readText()
            assertTrue(
                "${document.fileName} retains a copyable non-privileged Bash recipe",
                !unsafeRecipe.containsMatchIn(contents),
            )
        }
        assertTrue(
            "combined evidence must label its old aggregate invocation as historical",
            documents[2].readText().contains("历史") &&
                documents[2].readText().contains("./scripts/verify-a-plus.sh --stage full"),
        )
        val runbook = documents[3].readText()
        assertTrue(
            "issue66 runbook must invoke both selftests with privileged Bash",
            "/bin/bash -p ./scripts/selftest-issue66-moto-readonly-collector.sh" in runbook &&
                "/bin/bash -p ./scripts/selftest-issue66-services-compatibility.sh" in runbook,
        )
    }

    @Test
    fun `aggregate gate manifest isolates child stdin and proves complete traversal`() {
        val verifier = findRepoRoot().resolve("scripts/verify-a-plus.sh").readText()
        val expectedRecords = listOf(
            "1|provenance|PR-1|scripts/check-provenance.sh|" +
                "./scripts/check-provenance.sh --stage \\\$STAGE",
            "1|auto-unit-tests|PR-1|apps/cellrebel-auto/gradlew|" +
                "cd apps/cellrebel-auto && ./gradlew testDebugUnitTest --no-daemon",
            "1|auto-assemble|PR-1|apps/cellrebel-auto/gradlew|" +
                "cd apps/cellrebel-auto && ./gradlew assembleDebug --no-daemon",
            "1|qwy-unit-tests|PR-1|apps/qianwangyou/gradlew|" +
                "cd apps/qianwangyou && ./gradlew testDebugUnitTest --no-daemon",
            "1|qwy-assemble|PR-1|apps/qianwangyou/gradlew|" +
                "cd apps/qianwangyou && ./gradlew assembleDebug --no-daemon",
            "1|inherited-lint-debt|PR-1|scripts/check-inherited-lint-debt.sh|" +
                "./scripts/check-inherited-lint-debt.sh",
            "2|contract-v1|PR-2|scripts/check-contract-v1.sh|./scripts/check-contract-v1.sh",
            "3|acceptance-scenarios|PR-5|acceptance/scenarios|" +
                "cd acceptance && ./gradlew test --no-daemon",
            "3|matrix-coverage|PR-5|scripts/check-matrix-coverage.sh|" +
                "./scripts/check-matrix-coverage.sh",
            "3|forbidden-boundaries|PR-5|acceptance/scripts/check-forbidden-boundaries.sh|" +
                "./acceptance/scripts/check-forbidden-boundaries.sh",
            "3|auto-qwy-host|PR-6|integration-tests/pr63-on-issue66/run-host-gate.sh|" +
                "/bin/bash -p ./integration-tests/pr63-on-issue66/run-host-gate.sh",
            "3|release-debt|PR-2|scripts/check-release-debt.sh|./scripts/check-release-debt.sh",
        )
        val manifestStart = verifier.indexOf("GATES=\"")
        val manifestEnd = verifier.indexOf("\n\"", manifestStart)
        assertTrue("aggregate gate manifest is missing", manifestStart >= 0 && manifestEnd > manifestStart)
        val actualRecords = verifier.substring(manifestStart, manifestEnd)
            .lineSequence()
            .drop(1)
            .filter(String::isNotBlank)
            .toList()

        assertEquals(
            "aggregate gate manifest changed outside the independent 12-by-5 contract",
            expectedRecords,
            actualRecords,
        )
        assertTrue(
            "aggregate child gates can consume the remaining manifest from stdin",
            "run_clean_gate_command \"\$cmd\" </dev/null" in verifier,
        )
        assertTrue(
            "aggregate gate lacks an independent exact manifest cardinality contract",
            "readonly EXPECTED_GATE_COUNT=12" in verifier &&
                "readonly EXPECTED_GATE_NAMES=" in verifier,
        )
        assertTrue(
            "aggregate gate can return success after partial manifest traversal",
            "VERIFY_A_PLUS_GATE_MANIFEST_INCOMPLETE" in verifier &&
                "MANIFEST_SEEN" in verifier &&
                "EXPECTED_GATE_COUNT" in verifier,
        )
    }

    @Test
    fun `aggregate gate rejects valid-looking mutations to every manifest field`() {
        val canonicalVerifier = findRepoRoot().resolve("scripts/verify-a-plus.sh").readText()
        val mutations = linkedMapOf(
            "rank" to (
                "1|provenance|PR-1|scripts/check-provenance.sh|" to
                    "2|provenance|PR-1|scripts/check-provenance.sh|"
                ),
            "name" to (
                "1|provenance|PR-1|scripts/check-provenance.sh|" to
                    "1|provenance-v2|PR-1|scripts/check-provenance.sh|"
                ),
            "owner" to (
                "1|provenance|PR-1|scripts/check-provenance.sh|" to
                    "1|provenance|PR-9|scripts/check-provenance.sh|"
                ),
            "file" to (
                "1|provenance|PR-1|scripts/check-provenance.sh|" to
                    "1|provenance|PR-1|scripts/check-provenance-v2.sh|"
                ),
            "command" to (
                "./scripts/check-provenance.sh --stage \\\$STAGE" to
                    "./scripts/check-provenance.sh --stage import"
                ),
        )
        val fixtureRoot = Files.createTempDirectory("issue66-aggregate-manifest-mutation-")
        try {
            mutations.forEach { (field, replacement) ->
                val mutatedVerifier = canonicalVerifier.replace(replacement.first, replacement.second)
                assertTrue("$field mutation seam is missing", mutatedVerifier != canonicalVerifier)
                val verifier = fixtureRoot.resolve(field).resolve("scripts/verify-a-plus.sh")
                writeExecutable(verifier, mutatedVerifier)

                val validation = ProcessBuilder(
                    "/usr/bin/env",
                    "-i",
                    "ADB=/usr/bin/false",
                    "PATH=/usr/bin:/bin",
                    "/bin/bash",
                    "-p",
                    verifier.toString(),
                    "--list",
                ).redirectErrorStream(true).start()
                val output = validation.inputStream.bufferedReader().use { it.readText() }
                assertEquals("valid-looking $field mutation escaped:\n$output", 1, validation.waitFor())
                assertTrue(output, output.contains("VERIFY_A_PLUS_GATE_MANIFEST_INCOMPLETE"))
            }
            assertTrue(
                "aggregate execution must consume the same exact manifest that was frozen",
                "readonly GATES" in canonicalVerifier &&
                canonicalVerifier.substring(canonicalVerifier.lastIndexOf("while IFS='|' read"))
                    .contains("\$(printf '%s\\n' \"\$GATES\")"),
            )
        } finally {
            fixtureRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `aggregate gate gives every child a fresh Gradle startup surface`() {
        val verifier = findRepoRoot().resolve("scripts/verify-a-plus.sh").readText()
        val functionStart = verifier.indexOf("run_clean_gate_command() {")
        val functionEnd = verifier.indexOf("\n}\n\nRUN=", functionStart)
        assertTrue(
            "aggregate clean-gate function is missing",
            functionStart >= 0 && functionEnd > functionStart,
        )
        val functionSource = verifier.substring(functionStart, functionEnd + 2)
        val fixtureRoot = Files.createTempDirectory("issue66-aggregate-gradle-isolation-")
        val probe = fixtureRoot.resolve("probe.sh")
        try {
            Files.createDirectories(fixtureRoot.resolve("home"))
            Files.createDirectories(fixtureRoot.resolve("gradle-user-home"))
            Files.write(
                probe,
                (
                    "#!/bin/bash -p\n" +
                        "set -uo pipefail\n" +
                        "verify_temp_root=\"\$1\"\n" +
                        "verify_child_home=\"\$verify_temp_root/home\"\n" +
                        "verify_gradle_user_home=\"\$verify_temp_root/gradle-user-home\"\n" +
                        "host_android_home=/private/android-sdk\n" +
                        "host_java_home=/private/java-17\n" +
                        "host_java_binding=fixture-binding\n" +
                        "STAGE=full\n" +
                        "GATE_ENVIRONMENT_INDEX=0\n" +
                        "verify_java_runtime_binding() { return 0; }\n" +
                        "verify_android_sdk_binding() { return 0; }\n" +
                        functionSource + "\n" +
                        "run_clean_gate_command '" +
                        "/bin/mkdir -p \"\$GRADLE_USER_HOME/init.d\" && " +
                        "/usr/bin/touch \"\$GRADLE_USER_HOME/init.d/injected.gradle\"' || exit 91\n" +
                        "run_clean_gate_command '" +
                        "[ ! -e \"\$GRADLE_USER_HOME/init.d/injected.gradle\" ]' || exit 92\n"
                    ).toByteArray(),
            )

            val validation = ProcessBuilder(
                "/usr/bin/env",
                "-i",
                "ADB=/usr/bin/false",
                "PATH=/usr/bin:/bin",
                "/bin/bash",
                "-p",
                probe.toString(),
                fixtureRoot.toString(),
            ).redirectErrorStream(true).start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals("a Gradle init script crossed gate boundaries:\n$output", 0, validation.waitFor())
        } finally {
            fixtureRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `aggregate gate cleans an exact private root when preparation fails after allocation`() {
        val verifier = findRepoRoot().resolve("scripts/verify-a-plus.sh").readText()
        assertFalse(
            "aggregate private namespaces retain an eager unbounded directory enumeration",
            "os.listdir(" in verifier,
        )
        val cleanupStart = verifier.indexOf("cleanup_verify_environment() {")
        val cleanupEnd = verifier.indexOf("\n}\n\n", cleanupStart)
        assertTrue(
            "aggregate private-environment cleanup is missing",
            cleanupStart >= 0 && cleanupEnd > cleanupStart,
        )
        val cleanupSource = verifier.substring(cleanupStart, cleanupEnd + 2)
        assertFalse(
            "aggregate cleanup materializes an unbounded directory before enforcing its ceiling",
            "os.listdir(directory_fd)" in cleanupSource,
        )
        assertTrue(
            "aggregate cleanup has no bounded streaming directory enumerator",
            "os.scandir(directory_fd)" in cleanupSource &&
                "remaining" in cleanupSource,
        )
        val privateParent = Files.createTempDirectory("issue66-aggregate-private-parent-").toRealPath()
        val privateRoot = privateParent.resolve("verify-a-plus.01234567")
        Files.createDirectory(privateRoot)
        Files.setPosixFilePermissions(
            privateRoot,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        val probe = Files.createTempFile("issue66-aggregate-preparation-failure-", ".sh")
        try {
            Files.write(
                probe,
                (
                    "#!/bin/bash -p\nset -uo pipefail\n" +
                        "verify_temp_parent=\"\$1\"\n" +
                        "verify_temp_root=\"\$2\"\n" +
                        cleanupSource + "\n" +
                        "trap cleanup_verify_environment EXIT\n" +
                        "/bin/mkdir \"\$verify_temp_root/home\"\n" +
                        "/usr/bin/false\n"
                    ).toByteArray(),
            )
            val validation = ProcessBuilder(
                "/usr/bin/env",
                "-i",
                "ADB=/usr/bin/false",
                "PATH=/usr/bin:/bin",
                "/bin/bash",
                "-p",
                probe.toString(),
                privateParent.toString(),
                privateRoot.toString(),
            ).redirectErrorStream(true).start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 1, validation.waitFor())
            assertTrue("failed preparation leaked $privateRoot:\n$output", !Files.exists(privateRoot))
        } finally {
            probe.toFile().delete()
            privateRoot.toFile().deleteRecursively()
            privateParent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `aggregate allocator removes its exact root when post mkdir validation fails`() {
        val verifier = findRepoRoot().resolve("scripts/verify-a-plus.sh").readText()
        val allocatorStart = verifier.indexOf("create_verify_temp_root() {")
        val allocatorEnd = verifier.indexOf(
            "\n}\n\ncreate_private_java_runtime_root() {",
            allocatorStart,
        )
        check(allocatorStart >= 0 && allocatorEnd > allocatorStart) {
            "aggregate private-root allocator is missing"
        }
        val allocator = verifier.substring(allocatorStart, allocatorEnd + 2)
        val failingAllocator = allocator.replace(
            "    validate_directory(root_fd, root_path, expected_mode=0o700)\n",
            "    raise OSError(\"injected post-mkdir validation failure\")\n",
        )
        assertTrue("post-mkdir validation seam is missing", failingAllocator != allocator)

        val anchor = Files.createTempDirectory(
            findRepoRoot().resolve("integration-tests/pr63-on-issue66/harness/build"),
            "issue66-aggregate-allocation-failure-",
        ).toRealPath()
        Files.setPosixFilePermissions(
            anchor,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        val probe = Files.createTempFile("issue66-aggregate-allocation-probe-", ".sh")
        try {
            Files.write(
                probe,
                (
                    "#!/bin/bash -p\nset -uo pipefail\n" +
                        failingAllocator + "\n" +
                        "create_verify_temp_root \"\$1\"\n"
                    ).toByteArray(),
            )
            val validation = ProcessBuilder(
                "/usr/bin/env",
                "-i",
                "PATH=/usr/bin:/bin",
                "/bin/bash",
                "-p",
                probe.toString(),
                anchor.toString(),
            ).redirectErrorStream(true).start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals("injected allocator failure unexpectedly passed:\n$output", 1, validation.waitFor())
            val leakedRoots = anchor.resolve("build").toFile().listFiles().orEmpty()
                .filter { it.name.matches(Regex("verify-a-plus\\.[0-9a-f]{8}")) }
            assertTrue("post-mkdir allocator failure leaked $leakedRoots:\n$output", leakedRoots.isEmpty())
        } finally {
            probe.toFile().delete()
            anchor.toFile().deleteRecursively()
        }
    }

    @Test
    fun `aggregate Gradle gates disable daemons and successful exit cleans the private root`() {
        val verifier = findRepoRoot().resolve("scripts/verify-a-plus.sh").readText()
        val manifestStart = verifier.indexOf("GATES=\"")
        val manifestEnd = verifier.indexOf("\n\"", manifestStart)
        assertTrue("aggregate gate manifest is missing", manifestStart >= 0 && manifestEnd > manifestStart)
        val gradleCommands = verifier.substring(manifestStart, manifestEnd)
            .lineSequence()
            .drop(1)
            .filter(String::isNotBlank)
            .map { it.split('|', limit = 5) }
            .filter { fields -> fields[3].endsWith("gradlew") || "./gradlew " in fields[4] }
            .map { fields -> fields[4] }
            .toList()

        val cleanupStart = verifier.indexOf("cleanup_verify_environment() {")
        val cleanupEnd = verifier.indexOf("\n}\n\n", cleanupStart)
        val functionStart = verifier.indexOf("run_clean_gate_command() {")
        val functionEnd = verifier.indexOf("\n}\n\nRUN=", functionStart)
        assertTrue(
            "aggregate private-environment lifecycle is missing",
            cleanupStart >= 0 && cleanupEnd > cleanupStart &&
                functionEnd > functionStart && functionStart > cleanupEnd,
        )
        val cleanupSource = verifier.substring(cleanupStart, cleanupEnd + 2)
        val functionSource = verifier.substring(functionStart, functionEnd + 2)
        val privateParent = Files.createTempDirectory("issue66-aggregate-normal-parent-").toRealPath()
        val privateRoot = privateParent.resolve("verify-a-plus.89abcdef")
        Files.createDirectory(privateRoot)
        Files.setPosixFilePermissions(
            privateRoot,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        Files.createDirectory(privateRoot.resolve("home"))
        val probe = Files.createTempFile("issue66-aggregate-normal-cleanup-", ".sh")
        try {
            Files.write(
                probe,
                (
                    "#!/bin/bash -p\nset -uo pipefail\n" +
                        "verify_temp_parent=\"\$1\"\n" +
                        "verify_temp_root=\"\$2\"\n" +
                        "verify_child_home=\"\$verify_temp_root/home\"\n" +
                        "host_android_home=/private/android-sdk\n" +
                        "host_java_home=/private/java-17\n" +
                        "host_java_binding=fixture-binding\n" +
                        "STAGE=full\n" +
                        "verify_java_runtime_binding() { return 0; }\n" +
                        "verify_android_sdk_binding() { return 0; }\n" +
                        cleanupSource + "\n" +
                        "trap cleanup_verify_environment EXIT\n" +
                        functionSource + "\n" +
                        "run_clean_gate_command /usr/bin/true\n"
                    ).toByteArray(),
            )
            val validation = ProcessBuilder(
                "/usr/bin/env",
                "-i",
                "ADB=/usr/bin/false",
                "PATH=/usr/bin:/bin",
                "/bin/bash",
                "-p",
                probe.toString(),
                privateParent.toString(),
                privateRoot.toString(),
            ).redirectErrorStream(true).start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals("successful aggregate probe failed:\n$output", 0, validation.waitFor())
            assertTrue("successful aggregate exit leaked $privateRoot", !Files.exists(privateRoot))
        } finally {
            probe.toFile().delete()
            privateRoot.toFile().deleteRecursively()
            privateParent.toFile().deleteRecursively()
        }

        assertEquals("unexpected aggregate Gradle gate count", 5, gradleCommands.size)
        gradleCommands.forEach { command ->
            assertTrue("Gradle gate can leave a daemon alive: $command", "--no-daemon" in command)
        }
    }

    @Test
    fun `aggregate cleanup is armed before allocation and safely handles an empty target`() {
        val verifier = findRepoRoot().resolve("scripts/verify-a-plus.sh").readText()
        val cleanupStart = verifier.indexOf("cleanup_verify_environment() {")
        val cleanupEnd = verifier.indexOf("\n}\n\n", cleanupStart)
        val exitTrap = verifier.indexOf("trap cleanup_verify_environment EXIT", cleanupEnd)
        val allocationStart = verifier.indexOf(
            "if ! verify_temp_root=\"\$(create_verify_temp_root \"\$verify_temp_anchor\")\"",
            cleanupEnd,
        )
        assertTrue("aggregate cleanup function is missing", cleanupStart >= 0 && cleanupEnd > cleanupStart)
        assertTrue(
            "aggregate cleanup is not armed before private-root allocation",
            exitTrap > cleanupEnd && allocationStart > exitTrap,
        )
        val cleanupSource = verifier.substring(cleanupStart, cleanupEnd + 2)
        assertTrue(
            "aggregate cleanup does not safely preserve an empty target",
            "\${verify_temp_root:-}" in cleanupSource,
        )
        assertTrue(
            "aggregate cleanup does not revalidate the exact build parent ACL and identity",
            "has_extended_acl(parent)" in cleanupSource &&
                "parent_after_acl" in cleanupSource &&
                "parent_confirmed" in cleanupSource,
        )

        val probe = Files.createTempFile("issue66-aggregate-empty-cleanup-", ".sh")
        try {
            Files.write(
                probe,
                (
                    "#!/bin/bash -p\nset -uo pipefail\n" +
                        "verify_temp_parent=/unreachable\n" +
                        "verify_temp_root=\n" +
                        cleanupSource + "\n" +
                        "trap cleanup_verify_environment EXIT\n" +
                        "/usr/bin/false\n"
                    )
                    .toByteArray(),
            )
            val validation = ProcessBuilder(
                "/usr/bin/env",
                "-i",
                "ADB=/usr/bin/false",
                "PATH=/usr/bin:/bin",
                "/bin/bash",
                "-p",
                probe.toString(),
            ).redirectErrorStream(true).start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 1, validation.waitFor())
            assertTrue(output, !output.contains("VERIFY_A_PLUS_PRIVATE_ENVIRONMENT_CLEANUP_FAILED"))
        } finally {
            probe.toFile().delete()
        }
    }

    @Test
    fun `aggregate cleanup masks repeated termination signals`() {
        val verifier = findRepoRoot().resolve("scripts/verify-a-plus.sh").readText()
        val cleanupStart = verifier.indexOf("cleanup_verify_environment() {")
        val cleanupEnd = verifier.indexOf("\n}\n\n", cleanupStart)
        assertTrue("aggregate cleanup lifecycle is missing", cleanupStart >= 0 && cleanupEnd > cleanupStart)
        val cleanupSource = verifier.substring(cleanupStart, cleanupEnd + 2)
        val injectedCleanup = cleanupSource.replace(
                "  trap - EXIT\n",
                "  trap - EXIT\n" +
                    "  /bin/kill -INT \"\$\$\"\n" +
                    "  /bin/kill -TERM \"\$\$\"\n" +
                    "  /bin/kill -HUP \"\$\$\"\n",
            )
        assertTrue("signal cleanup seam is missing", injectedCleanup != cleanupSource)

        val privateParent = Files.createTempDirectory("issue66-aggregate-signal-parent-").toRealPath()
        val privateRoot = privateParent.resolve("verify-a-plus.7654abcd")
        Files.createDirectory(privateRoot)
        Files.setPosixFilePermissions(
            privateRoot,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        val stagedPayload = privateRoot.resolve(
            "jdk-runtime.${"f".repeat(32)}/home/lib/runtime.bin",
        )
        Files.createDirectories(stagedPayload.parent)
        Files.write(stagedPayload, "runtime".toByteArray())
        val probe = Files.createTempFile("issue66-aggregate-signal-cleanup-", ".sh")
        try {
            Files.write(
                probe,
                (
                    "#!/bin/bash -p\nset -uo pipefail\n" +
                        "verify_temp_parent=\"\$1\"\n" +
                        "verify_temp_root=\"\$2\"\n" +
                        injectedCleanup + "\n" +
                        "trap cleanup_verify_environment EXIT\n" +
                        "exit 0\n"
                    )
                    .toByteArray(),
            )
            val validation = ProcessBuilder(
                "/usr/bin/env",
                "-i",
                "ADB=/usr/bin/false",
                "PATH=/usr/bin:/bin",
                "/bin/bash",
                "-p",
                probe.toString(),
                privateParent.toString(),
                privateRoot.toString(),
            ).redirectErrorStream(true).start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals("signal interrupted aggregate cleanup:\n$output", 0, validation.waitFor())
            assertTrue("signal-interrupted cleanup leaked $privateRoot", !Files.exists(privateRoot))
        } finally {
            probe.toFile().delete()
            privateRoot.toFile().deleteRecursively()
            privateParent.toFile().deleteRecursively()
        }
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
                Files.write(receipt, (validReceipt(forgedSource, stateDir) + "\n").toByteArray())

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
    fun `aggregate validator rejects stat-cache-hidden raw worktree changes`() {
        val functionSource = verifyHostReceiptFunction(findRepoRoot())
        val source = cleanSourceFixture()
        try {
            val tracked = source.root.resolve("tracked-source.txt")
            runGit(source.root, "config", "core.trustctime", "false")
            runGit(source.root, "config", "core.checkStat", "minimal")
            val committedTime = FileTime.fromMillis(System.currentTimeMillis() - 60_000)
            Files.setLastModifiedTime(tracked, committedTime)
            runGit(source.root, "update-index", "--refresh")
            val indexedTime = Files.getLastModifiedTime(tracked)
            Files.write(tracked, "forged!\n".toByteArray())
            Files.setLastModifiedTime(tracked, indexedTime)
            assertEquals(
                "fixture did not bypass Git's stat-cache status path",
                "",
                runGit(source.root, "status", "--porcelain=v1", "--untracked-files=all"),
            )
            assertRawDirtySourceRejected(functionSource, source)
        } finally {
            source.close()
        }
    }

    @Test
    fun `aggregate validator rejects clean-filter-hidden raw worktree changes`() {
        val functionSource = verifyHostReceiptFunction(findRepoRoot())
        val source = cleanSourceFixture()
        try {
            Files.write(
                source.root.resolve(".git/info/attributes"),
                "tracked-source.txt filter=receipt-mask\n".toByteArray(),
            )
            runGit(
                source.root,
                "config",
                "filter.receipt-mask.clean",
                "/usr/bin/printf 'trusted\\n'",
            )
            runGit(source.root, "config", "filter.receipt-mask.required", "true")
            Files.write(source.root.resolve("tracked-source.txt"), "forged!\n".toByteArray())
            assertEquals(
                "fixture did not bypass Git status through info attributes and clean filter",
                "",
                runGit(source.root, "status", "--porcelain=v1", "--untracked-files=all"),
            )
            assertRawDirtySourceRejected(functionSource, source)
        } finally {
            source.close()
        }
    }

    @Test
    fun `aggregate validator does not execute a repository clean filter`() {
        assertRepositoryFilterNotExecuted("clean")
    }

    @Test
    fun `aggregate validator does not execute a repository process filter`() {
        assertRepositoryFilterNotExecuted("process")
    }

    @Test
    fun `aggregate validator rejects info-exclude-hidden untracked source`() {
        val functionSource = verifyHostReceiptFunction(findRepoRoot())
        val source = cleanSourceFixture()
        try {
            Files.write(
                source.root.resolve(".git/info/exclude"),
                "hidden-source.txt\n".toByteArray(),
                StandardOpenOption.APPEND,
            )
            Files.write(source.root.resolve("hidden-source.txt"), "unreviewed\n".toByteArray())
            assertEquals(
                "fixture did not hide the untracked source through info/exclude",
                "",
                runGit(source.root, "status", "--porcelain=v1", "--untracked-files=all"),
            )
            assertRawDirtySourceRejected(functionSource, source)
        } finally {
            source.close()
        }
    }

    @Test
    fun `aggregate validator rejects file-mode-hidden tracked changes`() {
        val functionSource = verifyHostReceiptFunction(findRepoRoot())
        val source = cleanSourceFixture()
        try {
            val tracked = source.root.resolve("tracked-source.txt")
            runGit(source.root, "config", "core.fileMode", "false")
            Files.setPosixFilePermissions(
                tracked,
                Files.getPosixFilePermissions(tracked) + PosixFilePermission.OWNER_EXECUTE,
            )
            assertEquals(
                "fixture did not hide the executable-bit change through core.fileMode=false",
                "",
                runGit(source.root, "status", "--porcelain=v1", "--untracked-files=all"),
            )
            assertRawDirtySourceRejected(functionSource, source)
        } finally {
            source.close()
        }
    }

    @Test
    fun `aggregate validator rejects a Darwin extended ACL on the source root`() {
        assumeTrue("Darwin ACL semantics are required", isDarwin())
        assertDarwinRawSourceAclRejected(
            "repository root",
            "everyone allow add_file,add_subdirectory,delete_child,writeattr,writeextattr",
        ) { source -> source.root }
    }

    @Test
    fun `aggregate validator rejects a Darwin extended ACL on a source parent`() {
        assumeTrue("Darwin ACL semantics are required", isDarwin())
        assertDarwinRawSourceAclRejected(
            "tracked source parent",
            "everyone allow add_file,add_subdirectory,delete_child,writeattr,writeextattr",
        ) { source -> source.runner.parent }
    }

    @Test
    fun `aggregate validator rejects a Darwin extended ACL on a tracked source file`() {
        assumeTrue("Darwin ACL semantics are required", isDarwin())
        assertDarwinRawSourceAclRejected(
            "tracked source file",
            "everyone allow write,append,writeattr,writeextattr",
        ) { source ->
            source.root.resolve("tracked-source.txt")
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
            Files.write(receipt, (validReceipt(source, stateDir) + "\n").toByteArray())
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

    private fun assertReceiptRejectsGradleAttestationMutation(
        prepareAttestations: (Path) -> Unit,
    ) {
        val functionSource = verifyHostReceiptFunction(findRepoRoot())
        val source = cleanSourceFixture()
        val stateDir = Files.createTempDirectory("issue66-host-gradle-attestation-red-").toRealPath()
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        try {
            val valid = validReceipt(source, stateDir)
            prepareAttestations(stateDir)
            Files.write(receipt, (valid + "\n").toByteArray())
            val validation = validatorProcess(functionSource, receipt, lock, source).start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals(
                "unbound Gradle attestations were accepted:\n$output",
                1,
                validation.waitFor(),
            )
            assertTrue(output, output.contains("Gradle attestation"))
            assertTrue("attestation rejection leaked validator lock", !Files.exists(lock))
        } finally {
            stateDir.toFile().deleteRecursively()
            source.close()
        }
    }

    private fun assertBoundAttestationMutationRejected(
        functionSource: String,
        source: SourceFixture,
        label: String,
        stage: String = "auto",
        mutation: (String) -> String,
    ) {
        val stateDir = Files.createTempDirectory("issue66-host-gradle-field-").toRealPath()
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        try {
            val valid = validReceipt(source, stateDir)
            val attestation = stateDir.resolve("gradle-attestation-$stage-$VALID_RUN_ID.txt")
            val originalSha = sha256(attestation)
            val original = attestation.readText()
            val mutated = mutation(original)
            assertTrue("$label did not mutate the attestation fixture", mutated != original)
            Files.write(
                attestation,
                mutated.toByteArray(),
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            val reboundReceipt = valid.replace(originalSha, sha256(attestation))
            assertTrue("$label did not rebind the receipt hash", reboundReceipt != valid)
            Files.write(receipt, (reboundReceipt + "\n").toByteArray())
            val validation = validatorProcess(functionSource, receipt, lock, source).start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals("$label escaped semantic binding:\n$output", 1, validation.waitFor())
            assertTrue(output, output.contains("Gradle attestation"))
            assertTrue("$label rejection leaked validator lock", !Files.exists(lock))
        } finally {
            stateDir.toFile().deleteRecursively()
        }
    }

    private fun assertRegisteredJavaTupleMutationRejected(
        functionSource: String,
        source: SourceFixture,
        label: String,
        attestationMutation: (String) -> String,
        receiptMutation: (String) -> String = { it },
    ) {
        val stateDir = Files.createTempDirectory("issue66-host-gradle-profile-").toRealPath()
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        try {
            val valid = validReceipt(source, stateDir)
            var reboundReceipt = valid
            GRADLE_ATTESTATION_SPECS.forEach { spec ->
                val attestation = stateDir.resolve(
                    "gradle-attestation-${spec.stage}-$VALID_RUN_ID.txt",
                )
                val originalSha = sha256(attestation)
                val original = attestation.readText()
                val mutated = attestationMutation(original)
                assertTrue("$label did not mutate ${spec.stage}", mutated != original)
                Files.write(
                    attestation,
                    mutated.toByteArray(),
                    StandardOpenOption.TRUNCATE_EXISTING,
                )
                reboundReceipt = reboundReceipt.replace(originalSha, sha256(attestation))
            }
            reboundReceipt = receiptMutation(reboundReceipt)
            assertTrue("$label did not mutate the receipt set", reboundReceipt != valid)
            Files.write(receipt, (reboundReceipt + "\n").toByteArray())
            val validation = validatorProcess(functionSource, receipt, lock, source).start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals("$label escaped the registered Java tuple:\n$output", 1, validation.waitFor())
            assertTrue(output, output.contains("Gradle attestation"))
            assertTrue("profile-tuple rejection leaked validator lock", !Files.exists(lock))
        } finally {
            stateDir.toFile().deleteRecursively()
        }
    }

    private fun assertRawDirtySourceRejected(
        functionSource: String,
        source: SourceFixture,
    ) {
        val stateDir = Files.createTempDirectory("issue66-host-raw-source-").toRealPath()
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        try {
            Files.write(receipt, (validReceipt(source, stateDir) + "\n").toByteArray())
            val validation = validatorProcess(functionSource, receipt, lock, source).start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals("raw worktree mutation escaped source binding:\n$output", 1, validation.waitFor())
            assertTrue(output, output.contains("source provenance invalid"))
            assertTrue("raw-source rejection leaked validator lock", !Files.exists(lock))
        } finally {
            stateDir.toFile().deleteRecursively()
        }
    }

    private fun assertRepositoryFilterNotExecuted(filterProperty: String) {
        val functionSource = verifyHostReceiptFunction(findRepoRoot())
        val source = cleanSourceFixture()
        val stateDir = Files.createTempDirectory("issue66-host-filter-side-effect-").toRealPath()
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        val marker = stateDir.resolve("$filterProperty-filter-ran")
        val filter = source.root.resolve(".git/info/$filterProperty-filter.sh")
        try {
            val body = if (filterProperty == "clean") {
                "#!/bin/bash\n/usr/bin/touch '${marker}'\nexec /bin/cat\n"
            } else {
                "#!/bin/bash\n/usr/bin/touch '${marker}'\nexit 93\n"
            }
            writeExecutable(filter, body)
            Files.write(
                source.root.resolve(".git/info/attributes"),
                "tracked-source.txt filter=receipt-side-effect\n".toByteArray(),
            )
            runGit(
                source.root,
                "config",
                "filter.receipt-side-effect.$filterProperty",
                filter.toString(),
            )
            runGit(source.root, "config", "filter.receipt-side-effect.required", "true")
            val tracked = source.root.resolve("tracked-source.txt")
            Files.setLastModifiedTime(
                tracked,
                FileTime.fromMillis(Files.getLastModifiedTime(tracked).toMillis() - 120_000),
            )
            Files.write(receipt, (validReceipt(source, stateDir) + "\n").toByteArray())

            val validation = validatorProcess(functionSource, receipt, lock, source).start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals("validator consulted the $filterProperty filter:\n$output", 0, validation.waitFor())
            assertTrue(
                "validator executed the repository $filterProperty filter:\n$output",
                !Files.exists(marker),
            )
            assertTrue("successful filter-isolation check leaked its lock", !Files.exists(lock))
        } finally {
            stateDir.toFile().deleteRecursively()
            source.close()
        }
    }

    private fun assertDarwinRawSourceAclRejected(
        label: String,
        rule: String,
        target: (SourceFixture) -> Path,
    ) {
        val functionSource = verifyHostReceiptFunction(findRepoRoot())
        val source = cleanSourceFixture()
        val stateDir = Files.createTempDirectory("issue66-host-source-acl-").toRealPath()
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        try {
            Files.write(receipt, (validReceipt(source, stateDir) + "\n").toByteArray())
            val targetPath = target(source)
            val modeBefore = Files.getPosixFilePermissions(targetPath)
            addDarwinAcl(targetPath, rule)
            assertEquals(
                "$label ACL fixture unexpectedly changed POSIX mode",
                modeBefore,
                Files.getPosixFilePermissions(targetPath),
            )

            val validation = validatorProcess(functionSource, receipt, lock, source).start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals("$label extended ACL escaped raw source binding:\n$output", 1, validation.waitFor())
            assertTrue(output, output.contains("source provenance invalid"))
            assertTrue(output, output.contains("extended ACL"))
            assertTrue("$label ACL rejection leaked validator lock", !Files.exists(lock))
        } finally {
            stateDir.toFile().deleteRecursively()
            source.close()
        }
    }

    private fun isDarwin(): Boolean = System.getProperty("os.name") == "Mac OS X"

    private fun addDarwinAcl(path: Path, rule: String) {
        val process = ProcessBuilder("/bin/chmod", "+a", rule, path.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "could not install Darwin ACL fixture: $output" }
        val inspection = ProcessBuilder("/bin/ls", "-lde", path.toString())
            .redirectErrorStream(true)
            .start()
        val listing = inspection.inputStream.bufferedReader().use { it.readText() }
        check(inspection.waitFor() == 0 && Regex("(?m)^\\s*\\d+:").containsMatchIn(listing)) {
            "Darwin ACL fixture is not visible on the target itself: $listing"
        }
    }

    private fun aggregateGateFixture(canonicalVerifier: Path): SourceFixture {
        val root = Files.createTempDirectory(
            findRepoRoot().resolve("integration-tests/pr63-on-issue66/harness/build"),
            "issue66-aggregate-gate-",
        ).toRealPath()
        val verifier = root.resolve("scripts/verify-a-plus.sh")
        Files.createDirectories(verifier.parent)
        Files.copy(canonicalVerifier, verifier)
        Files.copy(
            canonicalVerifier.resolveSibling("validate-java17-runtime.py"),
            verifier.resolveSibling("validate-java17-runtime.py"),
        )
        Files.copy(
            canonicalVerifier.resolveSibling("stage-java17-runtime.py"),
            verifier.resolveSibling("stage-java17-runtime.py"),
        )
        Files.copy(
            canonicalVerifier.resolveSibling("validate-android-sdk-runtime.py"),
            verifier.resolveSibling("validate-android-sdk-runtime.py"),
        )
        val runtimeProfiles = verifier.parent.resolve(
            "fixtures/issue66-java17-runtime-profiles.json",
        )
        Files.createDirectories(runtimeProfiles.parent)
        Files.copy(
            canonicalVerifier.parent.resolve("fixtures/issue66-java17-runtime-profiles.json"),
            runtimeProfiles,
        )
        Files.write(
            root.resolve(".gitignore"),
            "integration-tests/pr63-on-issue66/harness/build/\n".toByteArray(),
        )
        Files.createDirectories(root.resolve("integration-tests/pr63-on-issue66/harness"))

        val successfulGate = "#!/bin/bash\nexit 0\n"
        listOf(
            "scripts/check-provenance.sh",
            "scripts/check-inherited-lint-debt.sh",
            "scripts/check-contract-v1.sh",
            "scripts/check-matrix-coverage.sh",
            "scripts/check-release-debt.sh",
            "acceptance/gradlew",
            "acceptance/scripts/check-forbidden-boundaries.sh",
            "apps/cellrebel-auto/gradlew",
            "apps/qianwangyou/gradlew",
        ).forEach { relative ->
            writeExecutable(root.resolve(relative), successfulGate)
        }
        Files.createDirectories(root.resolve("acceptance/scenarios"))

        val runner = root.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
        val currentRunMarker = root.resolve("current-host-gate-ran")
        writeExecutable(
            runner,
            "#!/bin/bash -p\n" +
                "/usr/bin/touch \"$currentRunMarker\"\n" +
                "exit 1\n",
        )
        writeExecutable(verifier, verifier.readText())

        runGit(root, "init", "-q")
        runGit(root, "config", "user.name", "Aggregate Gate Test")
        runGit(root, "config", "user.email", "aggregate-gate-test@example.invalid")
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

    private fun gradleFilesEntries(source: String, collectionName: String): List<String> {
        val startMarker = "val $collectionName = files(\n"
        val start = source.indexOf(startMarker)
        check(start >= 0 && start == source.lastIndexOf(startMarker)) {
            "Gradle files collection $collectionName must occur exactly once"
        }
        val bodyStart = start + startMarker.length
        val bodyEnd = source.indexOf("\n)\n", bodyStart)
        check(bodyEnd >= bodyStart) { "Gradle files collection $collectionName is unterminated" }
        return source.substring(bodyStart, bodyEnd)
            .lineSequence()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("//") }
            .map { line ->
                val match = Regex("""^    rootProject\.file\(\"([^\"]+)\"\),$""")
                    .matchEntire(line)
                check(match != null) {
                    "Gradle files collection $collectionName has an unparseable entry: $line"
                }
                match.groupValues[1]
            }
            .toList()
    }

    private fun hostGuardInputContractViolations(source: String): List<String> {
        val violations = mutableListOf<String>()
        val scripts = try {
            gradleFilesEntries(source, "hostGuardExternalScripts")
        } catch (failure: IllegalStateException) {
            violations += failure.message ?: "hostGuardExternalScripts is invalid"
            emptyList()
        }
        if (scripts.count { it == "../../scripts/validate-android-sdk-runtime.py" } != 1) {
            violations += "Android SDK validator is not exactly once in hostGuardExternalScripts"
        }

        val blockMarker =
            "tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {\n"
        val blockStart = source.indexOf(blockMarker)
        if (blockStart < 0 || blockStart != source.lastIndexOf(blockMarker)) {
            violations += "all-Test input block does not occur exactly once"
            return violations
        }
        var depth = 1
        var cursor = blockStart + blockMarker.length
        while (cursor < source.length && depth > 0) {
            when (source[cursor]) {
                '{' -> depth += 1
                '}' -> depth -= 1
            }
            cursor += 1
        }
        if (depth != 0) {
            violations += "all-Test input block is unterminated"
            return violations
        }
        val block = source.substring(blockStart, cursor)
        val inputStartMarker = "    inputs.files(\n"
        val inputStart = block.indexOf(inputStartMarker)
        val inputEnd = if (inputStart >= 0) {
            block.indexOf("\n    )\n", inputStart + inputStartMarker.length)
        } else {
            -1
        }
        if (inputStart < 0 || inputEnd < 0) {
            violations += "all-Test input block does not have one parseable inputs.files call"
        } else {
            val inputLines = block
                .substring(inputStart + inputStartMarker.length, inputEnd)
                .lineSequence()
                .filter { it.isNotBlank() && !it.trimStart().startsWith("//") }
                .toList()
            val inputPattern = Regex("""^        ([A-Za-z][A-Za-z0-9]*),$""")
            val actualInputs = inputLines.mapNotNull { line ->
                inputPattern.matchEntire(line)?.groupValues?.get(1)
            }
            val expectedInputs = listOf(
                "hostGuardExternalScripts",
                "hostGuardExternalAppSources",
                "hostGuardBuildScript",
                "hostGuardExternalDocs",
            )
            if (inputLines.size != actualInputs.size || actualInputs != expectedInputs) {
                violations += "all-Test inputs.files does not consume the exact host guard collections"
            }
        }
        val relativeLine =
            "        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)"
        if (block.lineSequence().count { it == relativeLine } != 1) {
            violations += "all-Test input block does not apply RELATIVE sensitivity exactly once"
        }
        return violations
    }

    private fun parseAggregateAttestationSpecs(source: String): List<GradleAttestationSpec> {
        val startMarker = "gradle_attestation_specs = {\n"
        val endMarker = "}\ngradle_attestation_keys = [\n"
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start + startMarker.length)
        check(start >= 0 && start == source.lastIndexOf(startMarker) && end > start) {
            "aggregate Gradle attestation spec literal is missing or ambiguous"
        }
        val lines = source.substring(start + startMarker.length, end).lines()
        val specs = mutableListOf<GradleAttestationSpec>()
        var index = 0
        val stageHeader = Regex("""^    \"(auto|qwy|harness)\": \($""")
        val quotedValue = Regex("""^ {8,}\"([^\"]+)\",$""")
        val inlineTupleValue = Regex("""^        \(\"([^\"]+)\",\),$""")
        while (index < lines.size) {
            if (lines[index].isBlank()) {
                index += 1
                continue
            }
            val header = stageHeader.matchEntire(lines[index])
                ?: error("unparseable aggregate attestation spec line: ${lines[index]}")
            val stage = header.groupValues[1]
            index += 1
            val values = mutableListOf<String>()
            while (index < lines.size && lines[index] != "    ),") {
                quotedValue.matchEntire(lines[index])?.let { values += it.groupValues[1] }
                inlineTupleValue.matchEntire(lines[index])?.let { values += it.groupValues[1] }
                index += 1
            }
            check(index < lines.size && values.size >= 3) {
                "aggregate attestation spec $stage is incomplete"
            }
            specs += GradleAttestationSpec(
                stage,
                values[1],
                values.drop(2).sorted().joinToString(","),
            )
            index += 1
        }
        return specs
    }

    private fun writeExecutable(path: Path, contents: String) {
        Files.createDirectories(path.parent)
        Files.write(path, contents.toByteArray())
        Files.setPosixFilePermissions(
            path,
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
    }

    private fun requireJava17Home(): Path {
        check(System.getProperty("java.specification.version") == "17") {
            "aggregate fixture requires the same real Java 17 runtime as the host gate"
        }
        return Paths.get(System.getProperty("java.home")).toRealPath()
    }

    private fun requireAndroidSdk35(): Path {
        val configured = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        check(!configured.isNullOrBlank()) {
            "aggregate fixture requires an explicit ANDROID_HOME or ANDROID_SDK_ROOT"
        }
        val sdk = Paths.get(configured).toRealPath()
        check(Files.isRegularFile(sdk.resolve("platforms/android-35/android.jar"))) {
            "aggregate fixture requires a real Android SDK 35: $sdk"
        }
        return sdk
    }

    private fun cleanSourceFixture(): SourceFixture {
        val root = Files.createTempDirectory("issue66-host-source-").toRealPath()
        val runner = root.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
        val javaProfiles = root.resolve("scripts/fixtures/issue66-java17-runtime-profiles.json")
        Files.createDirectories(runner.parent)
        Files.createDirectories(javaProfiles.parent)
        Files.write(runner, "#!/usr/bin/env bash\nexit 0\n".toByteArray())
        Files.write(
            javaProfiles,
            Files.readAllBytes(
                findRepoRoot().resolve("scripts/fixtures/issue66-java17-runtime-profiles.json"),
            ),
        )
        Files.write(root.resolve("tracked-source.txt"), "trusted\n".toByteArray())
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

    private fun validReceipt(source: SourceFixture, stateDir: Path): String {
        val attestationHashes = writeValidGradleAttestations(stateDir)
        return "{" +
            "\"schemaVersion\":4," +
            "\"sourceHead\":\"${source.head}\"," +
            "\"sourceTree\":\"${source.tree}\"," +
            "\"sourceState\":\"CLEAN\"," +
            "\"runnerSha256\":\"${source.runnerSha256}\"," +
            "\"runId\":\"$VALID_RUN_ID\"," +
            "\"jdkProfileId\":\"$VALID_JDK_PROFILE_ID\"," +
            "\"jdkRuntimeVersion\":\"$VALID_JDK_RUNTIME_VERSION\"," +
            "\"jdkTreeSha256\":\"$VALID_JDK_TREE_SHA256\"," +
            "\"gradleAttestationAutoSha256\":\"${attestationHashes.getValue("auto")}\"," +
            "\"gradleAttestationQwySha256\":\"${attestationHashes.getValue("qwy")}\"," +
            "\"gradleAttestationHarnessSha256\":\"${attestationHashes.getValue("harness")}\"," +
            "\"hostIntegration\":\"PASS\"," +
            "\"issue66Ac7\":\"NOT_PASSED\"," +
            "\"emulator\":\"NOT_RUN\"," +
            "\"physicalDevice\":\"NOT_RUN\"," +
            "\"deviceFull\":\"BLOCKED\"," +
            "\"overall\":\"BLOCKED\"," +
            "\"reason\":\"HOST_GATE_HAS_NO_DEVICE_EVIDENCE__BOTH_ADMISSION_LISTS_EMPTY__" +
            "ACTIVATION_CLEANUP_REBOOTS_AND_ADVERSARIAL_MUTATIONS_REQUIRE_ADDITIONAL_AUTHORIZATION\"}"
    }

    private fun writeValidGradleAttestations(stateDir: Path): Map<String, String> {
        val jdkHome = stateDir.resolve("jdk-runtime.${"c".repeat(32)}/home")
        return GRADLE_ATTESTATION_SPECS.associate { spec ->
            val path = stateDir.resolve("gradle-attestation-${spec.stage}-$VALID_RUN_ID.txt")
            val body = listOf(
                "schemaVersion=2",
                "runId=$VALID_RUN_ID",
                "stage=${spec.stage}",
                "taskPath=${spec.taskPath}",
                "jdkHome=$jdkHome",
                "jdkProfileId=$VALID_JDK_PROFILE_ID",
                "javaVendor=Eclipse Adoptium",
                "javaVmVendor=Eclipse Adoptium",
                "jdkRuntimeVersion=$VALID_JDK_RUNTIME_VERSION",
                "jdkTreeSha256=$VALID_JDK_TREE_SHA256",
                "jdkMajor=17",
                "testLauncherMajor=17",
                "testCount=1",
                "failureCount=0",
                "classes=${spec.classes}",
            ).joinToString("\n", postfix = "\n")
            Files.write(path, body.toByteArray())
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
            spec.stage to sha256(path)
        }
    }

    private data class GradleAttestationSpec(
        val stage: String,
        val taskPath: String,
        val classes: String,
    )

    private companion object {
        const val VALID_RUN_ID = "0123456789abcdef0123456789abcdef"
        const val VALID_JDK_PROFILE_ID = "darwin-aarch64-eclipse-temurin-17.0.20.1+1"
        const val LINUX_JDK_PROFILE_ID = "linux-x86_64-eclipse-temurin-17.0.20.1+1"
        const val VALID_JDK_RUNTIME_VERSION = "17.0.20.1+1"
        const val VALID_JDK_TREE_SHA256 =
            "f89313615112db89abbaf64f7c5769432f3450e2c2d6059144e14b11104413d8"
        val GRADLE_ATTESTATION_SPECS = listOf(
            GradleAttestationSpec(
                "auto",
                ":app:testDebugUnitTest",
                "com.example.cellrebelauto.automation.ProviderPrincipalRoutingRedTest",
            ),
            GradleAttestationSpec(
                "qwy",
                ":app:testDebugUnitTest",
                listOf(
                    "name.caiyao.fakegps.hook.oracle.Android15OracleHookPlanTest",
                    "name.caiyao.fakegps.hook.oracle.SystemServerOracleWiringGuardTest",
                    "name.caiyao.fakegps.integration.v1.AuthoritativeAdvanceProviderTest",
                    "name.caiyao.fakegps.integration.v1.AuthoritativeOracleProductionGuardTest",
                    "name.caiyao.fakegps.integration.v1.BinderAuthoritativeContinuitySourceTest",
                    "name.caiyao.fakegps.oracle.OracleBundleCodecTest",
                ).sorted().joinToString(","),
            ),
            GradleAttestationSpec(
                "harness",
                ":harness:testDebugUnitTest",
                listOf(
                    "io.github.terryyyc.fakexxx.integration.pr63issue66.HarnessBoundaryGuardTest",
                    "io.github.terryyyc.fakexxx.integration.pr63issue66.HostRunnerEnvironmentGuardTest",
                    "io.github.terryyyc.fakexxx.integration.pr63issue66.HostReceiptModeGuardTest",
                    "io.github.terryyyc.fakexxx.integration.pr63issue66.HostEphemeralCleanupGuardTest",
                ).sorted().joinToString(","),
            ),
        )
    }

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
