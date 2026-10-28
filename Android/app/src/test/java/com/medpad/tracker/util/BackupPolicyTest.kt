package com.medpad.tracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * P10 — Android backup policy regression guard.
 *
 * These tests parse the REAL committed files (not copies):
 *   src/main/res/xml/backup_rules.xml          (fullBackupContent, API 24–30)
 *   src/main/res/xml/data_extraction_rules.xml (API 31+)
 *   src/main/AndroidManifest.xml               (wiring)
 *
 * so any future edit that weakens the policy — removing an exclude and
 * letting the Room DB (medical PHI), visit voice memos or the session token
 * blob leak into Google cloud backup, or unlinking the rules from the
 * manifest — FAILS the build instead of shipping silently.
 *
 * Plain JVM test (no Robolectric needed): the policy lives in files, not in
 * runtime state. Running from Gradle, the working directory is the app
 * module dir; `moduleFile()` also tolerates being run from the repo root.
 */
class BackupPolicyTest {

    // ── the P10 policy contract ──────────────────────────────────────────
    // "domain|path" entries that must NEVER reach Google cloud backup.
    private val neverInCloudBackup = setOf(
        "database|.",                     // Room DB: customers (PHI), visits+GPS, offline queue
        "file|voice",                     // recorded visit voice memos awaiting upload
        "sharedpref|medpad_session.xml",  // server URL + token blob + employee identity
    )

    // Entries that must never take part in device-to-device transfer.
    // Deliberately ONLY the session file: the Keystore key never transfers,
    // and field data (offline queue, customer cache) SHOULD survive a phone
    // upgrade — that is the whole reason allowBackup stays true.
    private val neverInDeviceTransfer = setOf(
        "sharedpref|medpad_session.xml",
    )

    // ── tests ────────────────────────────────────────────────────────────

    @Test
    fun legacyBackupRulesExcludeAllSensitiveData() {
        val root = parse(moduleFile("src/main/res/xml/backup_rules.xml"))
        assertEquals("full-backup-content", root.tagName)

        val include = children(root, "include")
        assertTrue(
            "policy is exclude-by-list: no <include> rules expected, found ${include.size}",
            include.isEmpty(),
        )
        assertEquals(neverInCloudBackup, excludes(root))
    }

    @Test
    fun dataExtractionRulesKeepPhiOutOfCloudButAllowDeviceTransfer() {
        val root = parse(moduleFile("src/main/res/xml/data_extraction_rules.xml"))
        assertEquals("data-extraction-rules", root.tagName)

        val cloud = children(root, "cloud-backup")
        assertEquals("exactly one <cloud-backup> block expected", 1, cloud.size)
        assertEquals(
            "cloud backup must be disabled when the transport cannot encrypt",
            "true", cloud[0].getAttribute("disableIfNoEncryptionCapabilities"),
        )
        assertEquals(neverInCloudBackup, excludes(cloud[0]))

        val transfer = children(root, "device-transfer")
        assertEquals("exactly one <device-transfer> block expected", 1, transfer.size)
        assertEquals(neverInDeviceTransfer, excludes(transfer[0]))
        // implied by the equality above, asserted explicitly for a readable
        // failure message: Room DB and voice memos MUST stay transferable.
        assertTrue(
            "device transfer must NOT exclude the Room DB (offline queue dies on phone upgrade)",
            "database|." !in excludes(transfer[0]),
        )
        assertTrue(
            "device transfer must NOT exclude voice memos",
            "file|voice" !in excludes(transfer[0]),
        )
    }

    @Test
    fun manifestWiresBothRuleFilesAndKeepsAllowBackup() {
        val root = parse(moduleFile("src/main/AndroidManifest.xml"))
        val apps = children(root, "application")
        assertEquals("exactly one <application> element expected", 1, apps.size)
        val app = apps[0]

        // allowBackup must stay true: turning it off would silently kill
        // device-to-device transfer of the offline queue on phone upgrades.
        assertEquals("true", app.getAttribute("android:allowBackup"))
        assertEquals("@xml/backup_rules", app.getAttribute("android:fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", app.getAttribute("android:dataExtractionRules"))

        // the referenced rule files must actually exist in the source tree
        assertTrue(moduleFile("src/main/res/xml/backup_rules.xml").isFile)
        assertTrue(moduleFile("src/main/res/xml/data_extraction_rules.xml").isFile)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Resolve a repo-relative file from the app-module working dir (Gradle
     *  default) or the repo root; fail loudly instead of silently skipping. */
    private fun moduleFile(rel: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOfNotNull(cwd, cwd.parentFile)
            .flatMap { dir -> listOf(File(dir, rel), File(dir, "Android/app/$rel")) }
            .distinct()
        return candidates.firstOrNull { it.isFile }
            ?: error(
                "cannot locate $rel (cwd=${cwd.absolutePath}) — unit tests must be able " +
                    "to resolve the app module source tree",
            )
    }

    private fun parse(file: File): Element {
        val dbf = DocumentBuilderFactory.newInstance() // XXE-safe hardening
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        dbf.setExpandEntityReferences(false)
        return dbf.newDocumentBuilder().parse(file).documentElement
    }

    private fun children(el: Element, tag: String): List<Element> =
        (0 until el.childNodes.length)
            .mapNotNull { el.childNodes.item(it) as? Element }
            .filter { it.tagName == tag }

    private fun excludes(el: Element): Set<String> =
        children(el, "exclude")
            .map { "${it.getAttribute("domain")}|${it.getAttribute("path")}" }
            .toSet()
}
