package app.hushai.android.actions

import android.content.Context
import android.provider.ContactsContract

data class ResolvedContact(
    val name: String,
    val phone: String,
    val email: String = ""
)

object ContactResolver {

    fun resolve(context: Context, query: String): ResolvedContact? {
        if (query.isBlank()) return null
        val q = query.trim()
        val qLower = q.lowercase()

        // 1. Check relationship labels first (Mom → MOTHER, Dad → FATHER, etc.)
        val relationResult = resolveByRelationship(context, qLower)
        if (relationResult != null) return relationResult

        // 2. Search by display name with ranking
        val candidates = mutableListOf<Pair<ResolvedContact, Int>>() // contact, score

        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$q%"),
            null
        )

        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: continue
                val phone = it.getString(1) ?: continue
                val nameLower = name.lowercase()
                val score = when {
                    nameLower == qLower -> 100                    // exact match
                    nameLower.startsWith(qLower) -> 80            // starts with
                    nameLower.split(" ").any { w -> w == qLower } -> 70  // exact word match
                    nameLower.split(" ").any { w -> w.startsWith(qLower) } -> 60 // word starts with
                    else -> 40                                     // contains
                }
                candidates.add(ResolvedContact(name, phone) to score)
            }
        }

        // 3. Try nickname field if no name matches
        if (candidates.isEmpty()) {
            val nickResult = resolveByNickname(context, q)
            if (nickResult != null) return nickResult
        }

        // Return highest-scored match
        return candidates.maxByOrNull { it.second }?.first
    }

    private fun resolveByRelationship(context: Context, query: String): ResolvedContact? {
        val relationType = when (query) {
            "mom", "mother", "mama", "mum", "mummy" -> ContactsContract.CommonDataKinds.Relation.TYPE_MOTHER
            "dad", "father", "papa", "daddy" -> ContactsContract.CommonDataKinds.Relation.TYPE_FATHER
            "wife", "spouse" -> ContactsContract.CommonDataKinds.Relation.TYPE_SPOUSE
            "husband" -> ContactsContract.CommonDataKinds.Relation.TYPE_SPOUSE
            "sister", "sis" -> ContactsContract.CommonDataKinds.Relation.TYPE_SISTER
            "brother", "bro" -> ContactsContract.CommonDataKinds.Relation.TYPE_BROTHER
            else -> return null
        }

        val cursor = context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Relation.TYPE} = ?",
            arrayOf(ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE, relationType.toString()),
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val contactId = it.getString(0)
                return resolvePhoneById(context, contactId)
            }
        }
        return null
    }

    private fun resolveByNickname(context: Context, query: String): ResolvedContact? {
        val cursor = context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Nickname.NAME, ContactsContract.Data.CONTACT_ID),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Nickname.NAME} LIKE ?",
            arrayOf(ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE, "%$query%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                return resolvePhoneById(context, it.getString(1))
            }
        }
        return null
    }

    private fun resolvePhoneById(context: Context, contactId: String): ResolvedContact? {
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                return ResolvedContact(
                    name = it.getString(0) ?: "",
                    phone = it.getString(1) ?: ""
                )
            }
        }
        return null
    }
}
