package com.aartiq.aavija

/**
 * Pairs a gesture on THIS phone with a gesture from the other phone.
 *
 * This is the Kotlin twin of the Mac's `GestureMatcher`. The rule is physical:
 * one side GRABS (picks the file up), the other side RELEASES (puts it down)
 * within ten seconds.
 *
 * In an Android-to-Android session the CONNECTING phone drives the transfers
 * (the listener is passive, exactly like the Mac drives a phone session), so
 * only the peer's gestures need to travel over the wire:
 *
 *   - peer grabbed, we released   → pull the peer's queued file
 *   - we grabbed,   peer released → push our held file to the peer
 *
 * Every event can be used once. That is what stops one grab/release pair from
 * firing a second transfer on the next heartbeat.
 *
 * Pure Kotlin (no Android imports) so the whole matching table is unit-tested
 * on the JVM without two phones on a desk.
 */
class PeerGestureMatcher {

    enum class Kind { GRAB, RELEASE }

    enum class Action { PULL_FROM_PEER, SEND_TO_PEER }

    companion object {
        /** How far apart a grab and a release may be and still count. */
        const val WINDOW_MS = PeerProtocol.GESTURE_WINDOW_MS
    }

    private var localGrabAt: Long? = null
    private var localGrabUsed = false
    private var localReleaseAt: Long? = null
    private var localReleaseUsed = false

    private var remoteGrab: Pair<Long, Long>? = null // id to happened-at
    private var remoteGrabUsed = false
    private var remoteRelease: Pair<Long, Long>? = null
    private var remoteReleaseUsed = false

    /** Forget all state, e.g. when Gesture mode is switched off. */
    fun reset() {
        localGrabAt = null
        localGrabUsed = false
        localReleaseAt = null
        localReleaseUsed = false
        remoteGrab = null
        remoteGrabUsed = false
        remoteRelease = null
        remoteReleaseUsed = false
    }

    /** Feed a gesture detected on THIS phone. */
    fun onLocal(kind: Kind, atMs: Long): Action? {
        when (kind) {
            Kind.GRAB -> {
                localGrabAt = atMs
                localGrabUsed = false
                return null
            }

            Kind.RELEASE -> {
                localReleaseAt = atMs
                localReleaseUsed = false
                val grab = remoteGrab ?: return null
                if (remoteGrabUsed) return null
                val age = atMs - grab.second
                if (age < 0 || age > WINDOW_MS) return null
                remoteGrabUsed = true
                localReleaseUsed = true
                return Action.PULL_FROM_PEER
            }
        }
    }

    /** True the first time a repeated heartbeat offer is seen. */
    fun isNew(offer: GestureOffer): Boolean = when (offer.type) {
        "grab" -> remoteGrab?.first != offer.id
        "release" -> remoteRelease?.first != offer.id
        else -> false
    }

    /** Feed a gesture the other phone reported on a heartbeat. */
    fun onRemote(offer: GestureOffer, nowMs: Long): Action? {
        val happenedAt = nowMs - offer.ageMs

        return when (offer.type) {
            "grab" -> {
                if (remoteGrab?.first == offer.id) return null
                remoteGrab = offer.id to happenedAt
                remoteGrabUsed = false

                // We may already have released while the peer's grab was in
                // flight. If it still fits the window, act now.
                val releaseAt = localReleaseAt ?: return null
                if (localReleaseUsed) return null
                val age = releaseAt - happenedAt
                if (age < 0 || age > WINDOW_MS) return null
                localReleaseUsed = true
                remoteGrabUsed = true
                Action.PULL_FROM_PEER
            }

            "release" -> {
                if (remoteRelease?.first == offer.id) return null
                remoteRelease = offer.id to happenedAt
                remoteReleaseUsed = false

                // We grabbed earlier and the peer has now released: send what
                // we are holding.
                val grabAt = localGrabAt ?: return null
                if (localGrabUsed) return null
                val age = happenedAt - grabAt
                if (age < 0 || age > WINDOW_MS) return null
                localGrabUsed = true
                remoteReleaseUsed = true
                Action.SEND_TO_PEER
            }

            else -> null
        }
    }
}
