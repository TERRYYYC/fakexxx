package name.caiyao.fakegps.probe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HookAcceptancePayloadTest {

    @Test
    fun `acceptance schema is linked to the production writer`() {
        assertEquals(
            name.caiyao.fakegps.config.ConfigPrefsSync.SCHEMA_VERSION,
            HookAcceptancePayload.schemaVersion,
        )
    }

    @Test
    fun acceptsCanonicalCurrentSchemaEnvelopeWithoutLosingLongValues() {
        val raw = """
            {
              "schemaVersion": 5,
              "acceptanceSessionId": "acceptance-123",
              "mode": "always_on",
              "fields": {
                "mcc": 310,
                "mnc": 260,
                "nci": 68719400000,
                "operator_name": "HOOK-SESSION:acceptance-123",
                "is_roaming": 0
              },
              "unavailable": []
            }
        """.trimIndent()

        val validated = HookAcceptancePayload.validate("acceptance-123", raw)
        val root = Json.parseToJsonElement(validated.json).jsonObject
        val fields = root.getValue("fields").jsonObject

        assertEquals("acceptance-123", validated.sessionId)
        assertEquals("HOOK-SESSION:acceptance-123", validated.publicMarker)
        assertEquals(
            "acceptance-123",
            root.getValue("acceptanceSessionId").jsonPrimitive.content,
        )
        assertEquals(5, root.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("always_on", root.getValue("mode").jsonPrimitive.content)
        assertEquals(68_719_400_000L, fields.getValue("nci").jsonPrimitive.content.toLong())
        assertEquals(
            "HOOK-SESSION:acceptance-123",
            fields.getValue("operator_name").jsonPrimitive.content,
        )
        assertEquals(0, fields.getValue("is_roaming").jsonPrimitive.content.toInt())
        assertEquals(true, validated.isLoadedByPublicMarker("HOOK-SESSION:acceptance-123"))
        assertEquals(false, validated.isLoadedByPublicMarker("HOOK-SESSION:acceptance-old"))
    }

    @Test
    fun rejectsEnvelopeOrPublicMarkerFromAnotherSession() {
        val wrongEnvelopeSession = assertThrows(IllegalArgumentException::class.java) {
            HookAcceptancePayload.validate(
                "acceptance-123",
                """
                    {
                      "schemaVersion": 5,
                      "acceptanceSessionId": "acceptance-old",
                      "mode": "always_on",
                      "fields": {"operator_name": "HOOK-SESSION:acceptance-old", "tac": 4095}
                    }
                """.trimIndent(),
            )
        }
        assertEquals(
            "acceptanceSessionId must match the activity session",
            wrongEnvelopeSession.message,
        )

        val wrongPublicMarker = assertThrows(IllegalArgumentException::class.java) {
            HookAcceptancePayload.validate(
                "acceptance-123",
                """
                    {
                      "schemaVersion": 5,
                      "acceptanceSessionId": "acceptance-123",
                      "mode": "always_on",
                      "fields": {"operator_name": "HOOK-LAB", "tac": 4095}
                    }
                """.trimIndent(),
            )
        }
        assertEquals(
            "operator_name must carry the acceptance session marker",
            wrongPublicMarker.message,
        )
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HookAcceptancePayload.validate(
                "acceptance-123",
                """{"schemaVersion":1,"mode":"always_on","fields":{"tac":4095}}""",
            )
        }

        assertEquals("schemaVersion must be 5", failure.message)
    }

    @Test
    fun rejectsBlankOrUnsafeSessionIds() {
        val raw = """{"schemaVersion":5,"mode":"always_on","fields":{"tac":4095}}"""

        assertThrows(IllegalArgumentException::class.java) {
            HookAcceptancePayload.validate("", raw)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HookAcceptancePayload.validate("acceptance id with spaces", raw)
        }
    }

    @Test
    fun rejectsNonObjectFieldsAndNonScalarValues() {
        assertThrows(IllegalArgumentException::class.java) {
            HookAcceptancePayload.validate(
                "acceptance-123",
                """{"schemaVersion":5,"acceptanceSessionId":"acceptance-123","mode":"always_on","fields":[]}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HookAcceptancePayload.validate(
                "acceptance-123",
                """{"schemaVersion":5,"acceptanceSessionId":"acceptance-123","mode":"always_on","fields":{"operator_name":"HOOK-SESSION:acceptance-123","tac":{"value":4095}}}""",
            )
        }
    }

    @Test
    fun rejectsFieldsOutsideTheWifiAndCellularProfileContract() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HookAcceptancePayload.validate(
                "acceptance-123",
                """{"schemaVersion":5,"acceptanceSessionId":"acceptance-123","mode":"always_on","fields":{"operator_name":"HOOK-SESSION:acceptance-123","not_a_profile_field":"rejected"}}""",
            )
        }

        assertEquals(
            "unsupported acceptance fields: not_a_profile_field",
            failure.message,
        )
        // wifi_channel is parsed by Snapshot but consumed by NO hook getter, so
        // the strict validator rejects it: a published channel could never be
        // verified per-field by the acceptance probe.
        val channelFailure = assertThrows(IllegalArgumentException::class.java) {
            HookAcceptancePayload.validate(
                "acceptance-123",
                """{"schemaVersion":5,"acceptanceSessionId":"acceptance-123","mode":"always_on","fields":{"operator_name":"HOOK-SESSION:acceptance-123","wifi_channel":6}}""",
            )
        }

        assertEquals(
            "unsupported acceptance fields: wifi_channel",
            channelFailure.message,
        )
    }

    @Test
    fun acceptsWifiFieldsForTheWifiMatrix() {
        // --wifi-matrix publishes the same strict acceptance envelope with the
        // wifi_* profile columns the hook layer consumes (Snapshot.fromJson).
        // Red-first against the cellular-only ALLOWED_FIELDS contract.
        val validated = HookAcceptancePayload.validate(
            "acceptance-wifi-123",
            """
                {
                  "schemaVersion": 5,
                  "acceptanceSessionId": "acceptance-wifi-123",
                  "mode": "always_on",
                  "fields": {
                    "operator_name": "HOOK-SESSION:acceptance-wifi-123",
                    "wifi_ssid": "hook-lab-wifi",
                    "wifi_bssid": "aa:bb:cc:dd:ee:ff",
                    "wifi_rssi": -55,
                    "wifi_frequency": 5180,
                    "wifi_link_speed": 866,
                    "wifi_tx_link_speed": 433,
                    "wifi_rx_link_speed": 433,
                    "wifi_standard": 6,
                    "wifi_security_type": 3,
                    "wifi_mac": "02:11:22:33:44:55",
                    "wifi_ip": "192.168.77.42",
                    "wifi_hidden": 0,
                    "wifi_enabled": 1
                  },
                  "unavailable": []
                }
            """.trimIndent(),
        )
        val fields = Json.parseToJsonElement(validated.json).jsonObject
            .getValue("fields").jsonObject

        assertEquals(
            "hook-lab-wifi",
            fields.getValue("wifi_ssid").jsonPrimitive.content,
        )
        assertEquals(
            1,
            fields.getValue("wifi_enabled").jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun acceptsCellularControlsAndNeighborJsonAsScalarFields() {
        val validated = HookAcceptancePayload.validate(
            "acceptance-neighbors",
            """
                {
                  "schemaVersion": 5,
                  "acceptanceSessionId": "acceptance-neighbors",
                  "mode": "always_on",
                  "fields": {
                    "operator_name": "HOOK-SESSION:acceptance-neighbors",
                    "signal_fluctuation_enabled": 0,
                    "signal_fluctuation_range_db": 6,
                    "neighbor_cells_json": "[{\"type\":\"gsm\",\"cid\":2222}]"
                  },
                  "unavailable": []
                }
            """.trimIndent(),
        )

        assertEquals(
            """{"schemaVersion":5,"acceptanceSessionId":"acceptance-neighbors","mode":"always_on","fields":{"operator_name":"HOOK-SESSION:acceptance-neighbors","signal_fluctuation_enabled":0,"signal_fluctuation_range_db":6,"neighbor_cells_json":"[{\"type\":\"gsm\",\"cid\":2222}]"},"unavailable":[]}""",
            validated.json,
        )
    }

    @Test
    fun acceptsOrthogonalUnavailableFieldsAndRejectsIntersections() {
        val validated = HookAcceptancePayload.validate(
            "acceptance-unavailable",
            """
                {
                  "schemaVersion": 5,
                  "acceptanceSessionId": "acceptance-unavailable",
                  "mode": "always_on",
                  "fields": {
                    "operator_name": "HOOK-SESSION:acceptance-unavailable",
                    "ci": 12345678
                  },
                  "unavailable": ["tac", "lte_rsrp"]
                }
            """.trimIndent(),
        )
        val root = Json.parseToJsonElement(validated.json).jsonObject
        assertEquals(
            listOf("lte_rsrp", "tac"),
            root.getValue("unavailable").let {
                (it as kotlinx.serialization.json.JsonArray).map { entry ->
                    entry.jsonPrimitive.content
                }
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            HookAcceptancePayload.validate(
                "acceptance-unavailable",
                """
                    {
                      "schemaVersion": 5,
                      "acceptanceSessionId": "acceptance-unavailable",
                      "mode": "always_on",
                      "fields": {
                        "operator_name": "HOOK-SESSION:acceptance-unavailable",
                        "tac": 4095
                      },
                      "unavailable": ["tac"]
                    }
                """.trimIndent(),
            )
        }
    }
}
