package io.github.terryyyc.fakexxx.integration.pr63issue66

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostEphemeralCleanupGuardTest {

    @Test
    fun `runner cleanup removes owned nested read only directories`() {
        val runner = findRepoRoot()
            .resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
            .readText()
        val cleanup = shellFunction(
            runner,
            "remove_ephemeral_private_home",
            "remove_ephemeral_gradle_home",
        )
        val parent = Files.createTempDirectory("issue66-host-cleanup-parent-").toRealPath()
        val target = parent.resolve("child-home.0123456789abcdef0123456789abcdef")
        val nested = target.resolve("readonly/deeper")
        val probe = Files.createTempFile("issue66-host-cleanup-probe-", ".sh")
        try {
            Files.setPosixFilePermissions(parent, OWNER_ONLY)
            Files.createDirectories(nested)
            Files.setPosixFilePermissions(target, OWNER_ONLY)
            Files.write(nested.resolve("payload"), byteArrayOf(1, 2, 3))
            Files.setPosixFilePermissions(nested, READ_EXECUTE_ONLY)
            Files.setPosixFilePermissions(nested.parent, READ_EXECUTE_ONLY)
            Files.write(
                probe,
                (
                    "#!/bin/bash -p\n" +
                        "set -euo pipefail\n" +
                        cleanup + "\n" +
                        "remove_ephemeral_private_home \"\$1\" \"\$2\" child-home\n" +
                        "[[ ! -e \"\$2\" && ! -L \"\$2\" ]]\n"
                    ).toByteArray(),
            )
            probe.toFile().setExecutable(true, true)

            val validation = ProcessBuilder(
                "/usr/bin/env",
                "-i",
                "PATH=/usr/bin:/bin",
                "/bin/bash",
                "-p",
                probe.toString(),
                parent.toString(),
                target.toString(),
            ).redirectErrorStream(true).start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }

            assertEquals("read-only private-home cleanup failed:\n$output", 0, validation.waitFor())
            assertFalse("read-only private home survived cleanup", Files.exists(target))
        } finally {
            restoreOwnerPermissions(target)
            probe.toFile().delete()
            target.toFile().deleteRecursively()
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `runner private home walkers enforce ceilings during streaming enumeration`() {
        val runner = findRepoRoot()
            .resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
            .readText()
        val cleanup = shellFunction(
            runner,
            "remove_ephemeral_private_home",
            "remove_ephemeral_gradle_home",
        )
        val validator = shellFunction(
            runner,
            "validate_clean_gradle_user_home",
            "run_clean_gradle_command",
        )

        listOf("cleanup" to cleanup, "Gradle-home validator" to validator).forEach { (label, source) ->
            assertFalse(
                "$label materializes an unbounded directory before enforcing its ceiling",
                "os.listdir(" in source,
            )
            assertTrue(
                "$label has no bounded streaming directory enumerator",
                "os.scandir(" in source && "remaining" in source,
            )
        }
    }

    private fun shellFunction(source: String, startName: String, nextName: String): String {
        val start = source.indexOf("$startName() {")
        val end = source.indexOf("\n}\n\n$nextName() {", start)
        check(start >= 0 && end > start) { "$startName shell function is missing" }
        return source.substring(start, end + 2)
    }

    private fun restoreOwnerPermissions(root: Path) {
        if (!Files.exists(root)) return
        root.toFile().walkTopDown().filter { it.isDirectory }.forEach {
            it.setReadable(true, true)
            it.setWritable(true, true)
            it.setExecutable(true, true)
        }
    }

    private fun findRepoRoot(): Path {
        var cursor = Paths.get("").toAbsolutePath().normalize()
        repeat(8) {
            if (Files.isRegularFile(cursor.resolve("scripts/verify-a-plus.sh"))) return cursor
            cursor = cursor.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }

    private companion object {
        val OWNER_ONLY = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        val READ_EXECUTE_ONLY = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE,
        )
    }
}
