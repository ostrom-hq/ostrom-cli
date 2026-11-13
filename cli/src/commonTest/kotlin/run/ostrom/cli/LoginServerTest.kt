// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The server `ostrom login`, `logout` and an agent talk to, and the sign-in page they show (S2-35, 09:45). */
class LoginServerTest : CliKit() {
    @Test
    fun aServerIsReachedOverHttpsOrOnThisMachineOnly() {
        // 09:45: the one-time code and the token never travel in clear
        val home = mapOf("HOME" to "/home/maya")
        for (url in listOf("http://ostrom.example", "http://localhost.evil.example", "http://localhost@evil.example")) {
            console.err.clear()
            assertEquals(64, cli(environment = home, api = serverOf(deviceFlow)).run(arrayOf("login", "--server", url)))
            val said = "ostrom: ${url.replace("localhost@", "")} is not an HTTPS address: " +
                "the sign-in code and the token would travel in clear."
            assertTrue(console.err.startsWith(said), console.err.toString())
        }
        // a password in the address is never printed
        console.err.clear()
        val secret = arrayOf("login", "--server", "http://deploy:s3@cret@ostrom.example")
        assertEquals(64, cli(environment = home).run(secret))
        assertTrue(console.err.startsWith("ostrom: http://ostrom.example is not"), console.err.toString())
        assertTrue(requests.isEmpty(), "nothing was sent")
        // the scheme in any case, as `ostrom config set server` reads it
        val local =
            listOf("HTTPS://ostrom.example", "http://localhost:8080/", "HTTP://127.0.0.1:4000", "http://[::1]:9")
        for (url in local) {
            assertEquals(0, cli(environment = home, api = serverOf(deviceFlow)).run(arrayOf("login", "--server", url)))
            assertEquals(url.trimEnd('/'), requests.last().first)
        }
    }

    @Test
    fun logoutFromAServerInClearForgetsTheTokenButDoesNotTellIt() {
        val clear = mapOf("HOME" to "/home/maya", "OSTROM_URL" to "http://ostrom.example")
        signIn("/home/maya/.config/ostrom", server = "http://ostrom.example")
        assertEquals(64, cli(environment = clear, api = serverOf(emptyMap())).run(arrayOf("logout")))
        assertTrue(requests.isEmpty(), "the server is not told")
        assertTrue(secretsKept.isEmpty(), "the token is forgotten")
        assertTrue(console.err.startsWith("ostrom: http://ostrom.example is not an HTTPS address: the token"))
        // nor does an agent start with it
        console.err.clear()
        signIn("/home/maya/.config/ostrom", server = "http://ostrom.example")
        assertEquals(64, cli(environment = clear).run(arrayOf("run", "claude")))
        assertTrue(console.err.toString().contains("the device's token would travel in clear"), console.err.toString())
    }

    @Test
    fun theSignInPageMustBeOnTheGitHubConfigured() {
        // S2-35: OSTROM_GITHUB_WEB_URL names the GitHub of a GitHub Enterprise; github.com's page is then refused
        val enterprise = withServer + ("OSTROM_GITHUB_WEB_URL" to "https://ghe.acme.example/")
        val page = """{"deviceCode":"dc","userCode":"X","verificationUri":"https://ghe.acme.example/login/device"}"""
        val flow = deviceFlow + ("POST /api/v1/auth/device" to (200 to page))
        assertEquals(0, cli(environment = enterprise, api = serverOf(flow)).run(arrayOf("login")))
        assertTrue(console.err.contains("Open https://ghe.acme.example/login/device"), console.err.toString())
        val github = """{"deviceCode":"dc","verificationUri":"https://github.com/login/device"}"""
        val elsewhere = deviceFlow + ("POST /api/v1/auth/device" to (200 to github))
        assertEquals(69, cli(environment = enterprise, api = serverOf(elsewhere)).run(arrayOf("login")))
        assertTrue(console.err.contains("a sign-in page that is neither GitHub's nor its own"), console.err.toString())
        // the console of OSTROM_CONSOLE_URL is a page of Ostrom's own: the server sends there
        val consolePage = """{"deviceCode":"dc","userCode":"X",""" +
            """"verificationUri":"https://console.ostrom.example/device"}"""
        val consoleFlow = deviceFlow + ("POST /api/v1/auth/device" to (200 to consolePage))
        val withConsole = withServer + ("OSTROM_CONSOLE_URL" to "https://console.ostrom.example/")
        assertEquals(0, cli(environment = withConsole, api = serverOf(consoleFlow)).run(arrayOf("login")))
        assertTrue(console.err.contains("Open https://console.ostrom.example/device"), console.err.toString())
    }
}
