package com.ollacore.app.ui.contacts

import com.ollacore.app.data.local.LocalContact
import com.ollacore.app.data.model.ContactUser

/**
 * Pure contacts logic (no Android/Compose dependencies so it is unit-testable
 * on the JVM): phone normalization + validation, form validation, search
 * matching, inbox/local merge, alphabetical sort, section keys.
 */

/** A single row in the contacts list, merged from inbox peers + saved contacts. */
data class ContactRow(
    /** Stable key: "uid:<userId>" for Ollacore users, "local:<LocalContact.id>" otherwise. */
    val key: String,
    val firstName: String,
    val lastName: String,
    val displayName: String,
    /** Phone as it should be shown. */
    val phone: String,
    val normalizedPhone: String,
    val username: String? = null,
    val photoUri: String? = null,
    /** Ollacore userId when known (inbox peer or resolved lookup). Null = not on Ollacore (yet). */
    val userId: String? = null,
    /** LocalContact.id when this row has a saved local record. */
    val localId: String? = null,
    val onOllacore: Boolean = userId != null
)

enum class ContactFormError {
    FIRST_REQUIRED,
    PHONE_REQUIRED,
    PHONE_INVALID,
    DUPLICATE
}

fun ContactFormError.message(): String = when (this) {
    ContactFormError.FIRST_REQUIRED -> "First name is required"
    ContactFormError.PHONE_REQUIRED -> "Phone number is required"
    ContactFormError.PHONE_INVALID -> "Enter a valid phone number (7-15 digits, optional leading +)"
    ContactFormError.DUPLICATE -> "A contact with this number already exists"
}

object ContactBook {

    /**
     * Canonical form: trimmed; single leading "+" kept; "00" prefix becomes "+";
     * every other non-digit dropped. Examples:
     * "+1 (555) 000-1111" -> "+15550001111", "0044 20 7946" -> "+44207946".
     */
    fun normalizePhone(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        var plus = false
        if (s.startsWith("+")) {
            plus = true
            s = s.substring(1)
        }
        var digits = s.filter { it.isDigit() }
        if (!plus && digits.startsWith("00") && digits.length > 2) {
            plus = true
            digits = digits.drop(2)
        }
        if (digits.isEmpty()) return ""
        return (if (plus) "+" else "") + digits
    }

    fun isValidPhone(normalized: String): Boolean {
        if (normalized.isEmpty()) return false
        val digits = normalized.removePrefix("+")
        if (!normalized.startsWith("+") && normalized.any { !it.isDigit() }) return false
        return digits.length in 7..15 && digits.all { it.isDigit() }
    }

    fun displayNameOf(firstName: String, lastName: String): String =
        listOf(firstName.trim(), lastName.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * Validate an add/edit form. [existing] are the saved local contacts;
     * [editingId] exempts the record being edited from the duplicate check.
     * Returns null when the form is valid.
     */
    fun validateForm(
        firstName: String,
        phone: String,
        existing: List<LocalContact>,
        editingId: String? = null
    ): ContactFormError? {
        if (firstName.trim().isEmpty()) return ContactFormError.FIRST_REQUIRED
        if (phone.trim().isEmpty()) return ContactFormError.PHONE_REQUIRED
        val normalized = normalizePhone(phone)
        if (!isValidPhone(normalized)) return ContactFormError.PHONE_INVALID
        if (existing.any { it.normalizedPhone == normalized && it.id != editingId }) {
            return ContactFormError.DUPLICATE
        }
        return null
    }

    /**
     * Case-insensitive match against first/last/full name, phone (raw and
     * normalized digits), and username. Digit queries also match the
     * normalized number so formatted input still finds contacts.
     */
    fun matchesQuery(row: ContactRow, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val qLower = q.lowercase()
        if (row.firstName.lowercase().contains(qLower)) return true
        if (row.lastName.lowercase().contains(qLower)) return true
        if (row.displayName.lowercase().contains(qLower)) return true
        if (row.username != null) {
            // "@mia" and "mia" both find username "mia.m".
            val userQuery = qLower.removePrefix("@")
            if (userQuery.isNotEmpty() && row.username.lowercase().contains(userQuery)) return true
        }
        if (row.phone.contains(q)) return true
        val qDigits = q.filter { it.isDigit() }
        if (qDigits.isNotEmpty()) {
            val rowDigits = row.normalizedPhone.filter { it.isDigit() }
            if (rowDigits.contains(qDigits)) return true
        }
        return false
    }

    fun filterRows(rows: List<ContactRow>, query: String): List<ContactRow> {
        if (query.isBlank()) return rows
        return rows.filter { matchesQuery(it, query) }
    }

    /**
     * Merge inbox peers (have userId) with saved local contacts. A local
     * record whose normalized number matches an inbox peer merges into that
     * row (local name/photo win); the rest become local-only rows. Result is
     * alphabetical by display name.
     */
    fun mergeRows(inbox: List<ContactUser>, local: List<LocalContact>): List<ContactRow> {
        val byPhone = LinkedHashMap<String, ContactRow>()
        inbox.forEach { peer ->
            val name = peer.displayName?.trim().orEmpty().ifEmpty { peer.phone }
            val normalized = normalizePhone(peer.phone)
            val row = ContactRow(
                key = "uid:${peer.userId}",
                firstName = name,
                lastName = "",
                displayName = name,
                phone = peer.phone,
                normalizedPhone = normalized,
                userId = peer.userId,
                onOllacore = true
            )
            // First peer wins per userId; keep a phone index for local merging.
            byPhone.putIfAbsent(normalized, row)
        }
        val merged = byPhone.toMutableMap()
        val localOnly = mutableListOf<ContactRow>()
        local.forEach { c ->
            val display = displayNameOf(c.firstName, c.lastName).ifEmpty { c.phone }
            val existing = merged[c.normalizedPhone]
            if (existing != null) {
                merged[c.normalizedPhone] = existing.copy(
                    firstName = c.firstName.trim(),
                    lastName = c.lastName.trim(),
                    displayName = display,
                    username = c.username?.trim()?.ifEmpty { null },
                    photoUri = c.photoUri ?: existing.photoUri,
                    localId = c.id,
                    userId = existing.userId ?: c.resolvedUserId,
                    onOllacore = true
                )
            } else {
                localOnly.add(
                    ContactRow(
                        key = "local:${c.id}",
                        firstName = c.firstName.trim(),
                        lastName = c.lastName.trim(),
                        displayName = display,
                        phone = c.phone,
                        normalizedPhone = c.normalizedPhone,
                        username = c.username?.trim()?.ifEmpty { null },
                        photoUri = c.photoUri,
                        userId = c.resolvedUserId,
                        localId = c.id,
                        onOllacore = c.resolvedUserId != null
                    )
                )
            }
        }
        return (merged.values + localOnly).sortedBy { it.displayName.lowercase() }
    }

    /** Alphabet section header: upper-case first letter, "#" for non-letters. */
    fun sectionKey(displayName: String): String {
        val first = displayName.trim().firstOrNull() ?: return "#"
        return if (first.isLetter()) first.uppercase() else "#"
    }

    /**
     * Combine a country dial code ("91") with a locally typed number into an
     * international number ("+9198…"). An already-international input wins.
     */
    fun fullPhoneNumber(dialDigits: String, localNumber: String): String {
        val typed = normalizePhone(localNumber)
        if (typed.startsWith("+")) return typed
        val dial = dialDigits.filter { it.isDigit() }
        var digits = typed.filter { it.isDigit() }
        // Drop a single trunk-prefix zero (e.g. 098… -> +91 98…).
        if (digits.startsWith("0")) digits = digits.drop(1)
        if (digits.isEmpty()) return ""
        return "+$dial$digits"
    }
}
