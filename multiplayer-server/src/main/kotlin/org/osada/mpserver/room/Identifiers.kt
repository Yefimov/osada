package org.osada.mpserver.room

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Room codes, participant ids and reconnect tokens.
 *
 * The room-code alphabet matches the client's local two-tab mode so a player can read a code aloud
 * without confusing O/0 or I/1.
 */
object Identifiers {
    const val ROOM_CODE_LENGTH = 6
    const val MAX_DISPLAY_NAME_LENGTH = 40

    private const val ROOM_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val RECONNECT_TOKEN_BYTES = 32
    private const val PARTICIPANT_ID_BYTES = 9

    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val roomCodePattern = Regex("[A-Z2-9]{$ROOM_CODE_LENGTH}")

    fun roomCode(): String =
        buildString(ROOM_CODE_LENGTH) {
            repeat(ROOM_CODE_LENGTH) { append(ROOM_ALPHABET[random.nextInt(ROOM_ALPHABET.length)]) }
        }

    fun isRoomCode(value: String): Boolean = roomCodePattern.matches(value)

    fun participantId(): String = "p_" + encoder.encodeToString(ByteArray(PARTICIPANT_ID_BYTES).also(random::nextBytes))

    fun reconnectToken(): String = encoder.encodeToString(ByteArray(RECONNECT_TOKEN_BYTES).also(random::nextBytes))

    /** Only the hash is kept in memory, so a heap dump cannot be replayed into someone's seat. */
    fun hashToken(token: String): String =
        encoder.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
        )

    /**
     * Display names are rendered into the DOM of the other player, so control characters and
     * over-long values are removed here rather than trusted from the client.
     */
    fun sanitizeDisplayName(value: String?): String {
        val cleaned =
            value
                ?.filter { !it.isISOControl() }
                ?.trim()
                ?.take(MAX_DISPLAY_NAME_LENGTH)
                .orEmpty()
        return cleaned.ifEmpty { "Commander" }
    }

    /**
     * Scenario file names come from the host client and are echoed to the guest, which appends them
     * to `resources/scenarios/data/`. Separators are already impossible under this pattern, so a
     * name cannot leave that directory; requiring the real `.xml` suffix and rejecting a bare `..`
     * keeps the value from being anything but a scenario at all.
     */
    fun isScenarioFile(value: String): Boolean =
        value.length in 1..MAX_SCENARIO_FILE_LENGTH &&
            scenarioFilePattern.matches(value) &&
            !value.contains("..")

    private const val MAX_SCENARIO_FILE_LENGTH = 64
    private val scenarioFilePattern = Regex("[A-Za-z0-9_\\-]+(\\.[A-Za-z0-9_\\-]+)*\\.xml")
}
