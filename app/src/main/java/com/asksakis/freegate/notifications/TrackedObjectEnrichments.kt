package com.asksakis.freegate.notifications

import android.graphics.Bitmap
import org.json.JSONObject

/**
 * Remembers which notification belongs to which tracked object, so that what Frigate learns
 * later can be folded into a notification that is already in the shade.
 *
 * Frigate reports a review as soon as it starts, which is when the notification goes out,
 * and only afterwards does it recognise a face, read a licence plate or generate a
 * description. All three arrive on the `tracked_object_update` topic keyed by the tracked
 * object id, while the notification is keyed by the review id, so the two have to be tied
 * together at the moment we notify. A review payload lists its tracked object ids in
 * `data.detections`, and that list is what [register] stores.
 *
 * Measured on a live instance, a description lands between 7 and 61 seconds after the review
 * starts, and a recognised name took 34 seconds, because both follow the end of the tracked
 * object rather than its start. Entries therefore have to outlive the event by minutes, and
 * [EXPIRY_MS] is set from that rather than from an assumption.
 */
class TrackedObjectEnrichments {

    /**
     * One posted notification, with whatever has been learned about it since.
     *
     * [alert] is replaced rather than mutated as facts arrive, so a recognised name reaches
     * the notification title through the same path that a name already present on the review
     * would have taken.
     */
    data class Entry(
        val notificationId: Int,
        var alert: AlertFilter.Alert,
        val snapshot: Bitmap?,
        var description: String? = null,
        val expiresAt: Long,
    )

    /** One thing Frigate learned about a tracked object. */
    sealed interface Update {
        val eventId: String

        data class Name(override val eventId: String, val name: String) : Update
        data class Plate(override val eventId: String, val plate: String) : Update
        data class Description(override val eventId: String, val text: String) : Update
    }

    private val lock = Any()

    /** Tracked object id to the notification that reported it. */
    private val entries = LinkedHashMap<String, Entry>()

    /**
     * Tie every tracked object in this review to the notification just posted. A review
     * usually carries one id, but a single review can cover several objects, and any of
     * them can be the one Frigate later recognises.
     */
    fun register(
        alert: AlertFilter.Alert,
        notificationId: Int,
        snapshot: Bitmap?,
        now: Long = System.currentTimeMillis(),
    ) {
        if (alert.detectionIds.isEmpty()) return
        val entry = Entry(
            notificationId = notificationId,
            alert = alert,
            snapshot = snapshot,
            expiresAt = now + EXPIRY_MS,
        )
        synchronized(lock) {
            purgeLocked(now)
            alert.detectionIds.forEach { entries[it] = entry }
            // Bitmaps are held for the lifetime of an entry, so the map is capped by count
            // as well as by age. Oldest first, since a newer alert is the one still on
            // screen and therefore the one worth updating.
            while (entries.size > MAX_ENTRIES) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
            }
        }
    }

    /**
     * Fold [update] into the notification that reported the same tracked object, and return
     * that notification so the caller can repost it. Returns null when nothing was waiting
     * for this object, which is the common case: most updates belong to reviews that were
     * filtered out, muted, or that predate the app being opened.
     */
    fun apply(update: Update, now: Long = System.currentTimeMillis()): Entry? {
        synchronized(lock) {
            purgeLocked(now)
            val entry = entries[update.eventId] ?: return null
            when (update) {
                is Update.Name -> entry.alert = entry.alert.copy(subLabel = update.name)
                is Update.Plate -> entry.alert = entry.alert.copy(plate = update.plate)
                is Update.Description -> entry.description = update.text
            }
            return entry
        }
    }

    /** Drop everything, for a listener restart where the posted notifications are gone. */
    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    private fun purgeLocked(now: Long) {
        val expired = entries.entries.filter { it.value.expiresAt <= now }.map { it.key }
        expired.forEach { entries.remove(it) }
    }

    companion object {
        /**
         * How long a notification stays eligible for an update. Generous on purpose: a
         * description follows the end of the tracked object, so a person who lingers at the
         * door for two minutes produces a description well after the notification.
         */
        private const val EXPIRY_MS = 10L * 60 * 1000

        /** Entries hold a snapshot bitmap each, so the map is bounded by count too. */
        private const val MAX_ENTRIES = 16

        /**
         * Read one `tracked_object_update` payload.
         *
         * Face updates are published repeatedly as Frigate works on the object, with a null
         * name and a zero score until it resolves one. Those carry no information, and
         * acting on them would replace a good notification with an empty one, so they are
         * dropped here rather than at the call site.
         */
        fun parseFrame(envelope: JSONObject): Update? {
            // Frigate wraps the MQTT payload as a JSON *string* inside the websocket
            // envelope, so it has to be parsed a second time. Older frames nest it as an
            // object, which the first branch keeps working.
            val payload = envelope.optJSONObject("payload")
                ?: envelope.optString("payload").takeIf { it.isNotEmpty() }
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return null
            val eventId = payload.optString("id").takeIf { it.isNotEmpty() } ?: return null
            return when (payload.optString("type")) {
                "description" -> payload.optString("description")
                    .takeIf { it.isNotEmpty() && it != "null" }
                    ?.let { Update.Description(eventId, it) }

                "face" -> payload.optString("name")
                    .takeIf { it.isNotEmpty() && it != "null" }
                    ?.takeIf { payload.optDouble("score", 0.0) >= MIN_FACE_SCORE }
                    ?.let { Update.Name(eventId, it) }

                "lpr" -> payload.optString("plate")
                    .takeIf { it.isNotEmpty() && it != "null" }
                    ?.let { Update.Plate(eventId, it) }

                else -> null
            }
        }

        /**
         * Below this, Frigate has not settled on a name. Its own recognition threshold is
         * configured server side; this is only a floor against the zero-score placeholders.
         */
        private const val MIN_FACE_SCORE = 0.01
    }
}
