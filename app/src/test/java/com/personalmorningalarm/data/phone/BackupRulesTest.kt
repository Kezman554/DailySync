package com.personalmorningalarm.data.phone

import android.content.Context
import android.content.res.XmlResourceParser
import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

/**
 * Lists kept on the phone go in Android's backup, so a lost phone doesn't lose them
 * while backup is on. Read from the rules the manifest names, for both Android 12+
 * (cloud backup and device transfer) and Android 11 and earlier.
 *
 * Android's rules: with any include in a section, only what's included is backed up,
 * and an exclude wins over an include. A database include also takes its journal.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRulesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private data class Rule(val section: String, val kind: String, val domain: String, val path: String)

    private fun rules(xml: Int): List<Rule> {
        val parser: XmlResourceParser = context.resources.getXml(xml)
        val rules = mutableListOf<Rule>()
        var section = "full-backup-content"
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer" -> section = parser.name
                "include", "exclude" -> rules += Rule(
                    section,
                    parser.name,
                    parser.getAttributeValue(null, "domain"),
                    parser.getAttributeValue(null, "path")
                )
            }
        }
        return rules
    }

    /** Whether a rule for [domain] at [path] reaches the file [name] in that domain's folder. */
    private fun Rule.covers(name: String): Boolean =
        domain == "database" && (path == "." || path == name || name.startsWith(path.trimEnd('/') + "/"))

    private fun assertPhoneListsBackedUp(rules: List<Rule>, section: String) {
        val inSection = rules.filter { it.section == section }
        assertTrue("$section has no rules", inSection.isNotEmpty())
        val includes = inSection.filter { it.kind == "include" }
        assertTrue(
            "$section doesn't include ${PhoneListDatabase.DB_NAME}",
            includes.isEmpty() || includes.any { it.covers(PhoneListDatabase.DB_NAME) }
        )
        assertTrue(
            "$section excludes ${PhoneListDatabase.DB_NAME}",
            inSection.none { it.kind == "exclude" && it.covers(PhoneListDatabase.DB_NAME) }
        )
    }

    @Test
    fun `Android 12 and later back up the phone lists, to the cloud and to a new phone`() {
        val rules = rules(R.xml.data_extraction_rules)

        assertPhoneListsBackedUp(rules, "cloud-backup")
        assertPhoneListsBackedUp(rules, "device-transfer")
    }

    @Test
    fun `Android 11 and earlier back up the phone lists`() {
        assertPhoneListsBackedUp(rules(R.xml.backup_rules), "full-backup-content")
    }

    @Test
    fun `the two files say the same, and everything backed up before still is`() {
        val modern = rules(R.xml.data_extraction_rules)
        val legacy = rules(R.xml.backup_rules).map { it.copy(section = "") }
        val everything = listOf("database", "sharedpref", "file").map { Rule("", "include", it, ".") }

        assertEquals(legacy, modern.filter { it.section == "cloud-backup" }.map { it.copy(section = "") })
        assertEquals(legacy, modern.filter { it.section == "device-transfer" }.map { it.copy(section = "") })
        assertEquals(everything, legacy)
    }
}
