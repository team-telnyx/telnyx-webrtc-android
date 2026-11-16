package com.telnyx.webrtc.sdk.peer

import com.telnyx.webrtc.sdk.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t029 regressions: the shared ICE server plan ([Peer.iceServerPlan]) is the single source
 * of truth, and the real server construction ([Peer.buildIceServers]) consumes the entire
 * plan — URLs, order and the original credential policy — so the reported effective count
 * always matches the built list by construction, never by a separately maintained constant.
 */
class IceServerPlanTest {

    @Test fun `default production plan keeps the documented endpoint constants`() {
        val plan = Peer.iceServerPlan(Config.DEFAULT_TURN, Config.DEFAULT_STUN)
        assertEquals(
            listOf(
                Config.DEFAULT_STUN,
                Config.GOOGLE_STUN,
                Config.DEFAULT_TURN_UDP,
                Config.DEFAULT_TURN,
                Config.DEFAULT_TURNS_443,
            ),
            plan.map { it.url },
        )
    }

    @Test fun `default development plan keeps the documented dev endpoint constants`() {
        val plan = Peer.iceServerPlan(Config.DEV_TURN, Config.DEFAULT_STUN)
        assertEquals(
            listOf(
                Config.DEFAULT_STUN,
                Config.GOOGLE_STUN,
                Config.DEV_TURN_UDP,
                Config.DEV_TURN,
                Config.DEV_TURNS_443,
            ),
            plan.map { it.url },
        )
    }

    @Test fun `custom plan derives udp, tcp and turns443 variants from the provided TURN`() {
        val custom = "turn:turn.example.com:3478?transport=tcp"
        val plan = Peer.iceServerPlan(custom, "stun:stun.example.net:3478")
        assertEquals(
            listOf(
                "stun:stun.example.net:3478",
                Config.GOOGLE_STUN,
                "turn:turn.example.com:3478?transport=udp",
                "turn:turn.example.com:3478?transport=tcp",
                "turns:turn.example.com:443?transport=tcp",
            ),
            plan.map { it.url },
        )
    }

    @Test fun `plan carries the original credential policy per role`() {
        val plan = Peer.iceServerPlan(Config.DEFAULT_TURN, Config.DEFAULT_STUN)
        assertEquals(IceServerRole.PRIMARY_STUN, plan[0].role)
        assertEquals(IceServerRole.REDUNDANT_STUN, plan[1].role)
        assertEquals(IceServerRole.TURN_UDP, plan[2].role)
        assertEquals(IceServerRole.TURN_TCP, plan[3].role)
        assertEquals(IceServerRole.TURNS_443, plan[4].role)
        assertEquals("every planned server carries the TURN credentials except the redundant public STUN",
            listOf(true, false, true, true, true), plan.map { it.withCredentials })
    }

    @Test fun `real builder consumes the entire plan for every provenance path`() {
        val paths = listOf(
            Config.DEFAULT_TURN to Config.DEFAULT_STUN,
            Config.DEV_TURN to Config.DEFAULT_STUN,
            "turn:turn.example.com:3478?transport=tcp" to "stun:stun.example.net:3478",
        )
        for ((turn, stun) in paths) {
            val plan = Peer.iceServerPlan(turn, stun)
            val built = Peer.buildIceServers(plan)
            assertEquals(plan.size, built.size)
            assertEquals("the built servers must carry the plan URLs in the plan order",
                plan.map { it.url }, built.map { it.urls.single() })
            for ((entry, server) in plan.zip(built)) {
                if (entry.withCredentials) {
                    assertEquals(Config.USERNAME, server.username)
                    assertEquals(Config.PASSWORD, server.password)
                } else {
                    assertTrue("the redundant public STUN server stays credential-free",
                        server.username.isNullOrBlank() && server.password.isNullOrBlank())
                }
            }
        }
    }

    @Test fun `effective count and URL list derive from the same plan`() {
        val plan = Peer.iceServerPlan(Config.DEFAULT_TURN, Config.DEFAULT_STUN)
        assertEquals(plan.size, Peer.effectiveIceServerCount(Config.DEFAULT_TURN, Config.DEFAULT_STUN))
        assertEquals(plan.map { it.url }, Peer.iceServerPlanUrls(Config.DEFAULT_TURN, Config.DEFAULT_STUN))
    }

    @Test fun `udp and tcp transport variants follow the documented derivation`() {
        assertEquals(Pair("turn:t.example:3478?transport=udp", "turn:t.example:3478?transport=tcp"),
            Peer.turnTransportVariants("turn:t.example:3478"))
        assertEquals(Pair("turn:t.example:3478?transport=udp", "turn:t.example:3478?transport=tcp"),
            Peer.turnTransportVariants("turn:t.example:3478?transport=tcp"))
        assertEquals(Pair("turn:t.example:3478?transport=udp", "turn:t.example:3478?transport=udp"),
            Peer.turnTransportVariants("turn:t.example:3478?transport=udp"))
    }
}
