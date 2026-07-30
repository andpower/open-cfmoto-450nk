package dev.zanderp.opencfmoto

import android.net.Uri
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Mirrors net.easyconn.carman.common.base.QrResult.parseResult — the bike's QR is a URL
 * whose query string carries the connection params:
 *
 *   http://www.carbit.com.cn/...?modelid=...&sn=...&action=9
 *     &ssid=CFMOTO-xxxxxx&pwd=xxxxxx&auth=wpa2-psk
 *     &mac=xx:xx:xx:xx:xx:xx&name=CFMOTO-xxxxxx
 *
 * Moto Morini / MotoFun uses a different delimiter style (hash separators — not a URL fragment):
 *
 *   http://admin.motomorini.com/app.html?Wifi=ML174167#12345678#dc0d30da1b6c
 *     &MachineID=dc0d30da1b6c&ProductID=00297
 *
 * `action` is a bitmask: bit0=basic AP, bit1=AP+internet, bit3=Wi-Fi P2P, bit6=BT.
 */
data class QrData(
    val ssid: String,
    val pwd: String,
    val auth: String?,
    val mac: String?,
    val name: String?,
    val action: Int,
    val modelId: String?,
    val sn: String?,
    val channel: String?,
) {
    val supportsAp: Boolean get() = (action and 1) != 0 || (action and 2) != 0
    val supportsP2p: Boolean get() = (action and 8) != 0

    companion object {
        fun parse(raw: String): QrData? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            parseCarbit(trimmed)?.let { return it }
            parseMotoMorini(trimmed)?.let { return it }
            return null
        }

        /** Classic Carbit / EasyConnect `ssid=` + `pwd=` query params. */
        private fun parseCarbit(raw: String): QrData? {
            val q = queryParams(raw)
            val ssid = q["ssid"] ?: return null
            val pwd = q["pwd"] ?: return null
            return QrData(
                ssid = ssid,
                pwd = pwd,
                auth = q["auth"],
                mac = q["mac"],
                name = q["name"],
                action = q["action"]?.toIntOrNull() ?: 0,
                modelId = q["modelid"],
                sn = q["sn"],
                channel = q["channel"],
            )
        }

        /** Query map from a URL (before `#` fragment). Keys are lower-cased. */
        private fun queryParams(raw: String): Map<String, String> {
            val query = raw.substringAfter('?', missingDelimiterValue = "")
                .substringBefore('#')
            if (query.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (part in query.split('&')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                val key = if (eq >= 0) part.substring(0, eq) else part
                val value = if (eq >= 0) part.substring(eq + 1) else ""
                if (key.isEmpty()) continue
                out[key.lowercase()] = try {
                    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
                } catch (_: Exception) {
                    value
                }
            }
            return out
        }

        /**
         * Moto Morini / MotoFun dash QR.
         *
         * Uses `#` as field separators after `Wifi=`, so [Uri] fragment parsing would eat the
         * password — we scan the raw string instead.
         */
        private fun parseMotoMorini(raw: String): QrData? {
            val wifi = Regex("""(?i)(?:^|[?&])Wifi=([^&#\s]+)""").find(raw) ?: return null
            val ssid = wifi.groupValues[1].trim()
            if (ssid.isEmpty()) return null

            // After Wifi=<ssid> expect #<pwd>#<mac>&MachineID=…&ProductID=…
            val after = raw.substring(wifi.range.last + 1)
            if (!after.startsWith('#')) return null
            val hashParts = after.removePrefix("#").split('#', limit = 2)
            val pwd = hashParts.getOrNull(0)?.substringBefore('&')?.trim().orEmpty()
            if (pwd.isEmpty()) return null

            val macToken = hashParts.getOrNull(1)?.substringBefore('&')?.trim()
                ?.takeIf { it.isNotEmpty() }
            val machineId = Regex("""(?i)MachineID=([^&#\s]+)""").find(raw)?.groupValues?.get(1)
            val productId = Regex("""(?i)ProductID=([^&#\s]+)""").find(raw)?.groupValues?.get(1)

            return QrData(
                ssid = ssid,
                pwd = pwd,
                auth = "wpa2-psk",
                mac = formatMac(macToken) ?: formatMac(machineId),
                name = ssid,
                action = 1, // SoftAP
                modelId = productId,
                sn = machineId,
                channel = null,
            )
        }

        /** `aabbccddeeff` / `aa:bb:…` → colon form; null if not 12 hex digits. */
        private fun formatMac(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val hex = raw.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
            if (hex.length != 12) {
                // Already colon-separated or other — keep if it looks like a MAC.
                return raw.takeIf { it.contains(':') && it.length >= 11 }
            }
            return hex.chunked(2).joinToString(":") { it.lowercase() }
        }

        /**
         * Hint for failed scans (vehicle-info QR, app-download QR, etc.) — not a pairing code.
         */
        fun parseFailureHint(raw: String): String? {
            val t = raw.trim()
            when {
                t.contains("motomorini.com", ignoreCase = true) &&
                    !t.contains("Wifi=", ignoreCase = true) ->
                    return "Moto Morini QR has no Wifi= field — open the phone-link / MotoFun QR on the dash"
                Regex("""(?i)^code:.*engine:.*vin:""").containsMatchIn(t) ||
                    t.contains("color:", ignoreCase = true) && t.contains("vin:", ignoreCase = true) ->
                    return "that QR is bike info (color/VIN), not Wi‑Fi pairing — open MotoPlay / EasyConnect / MotoFun QR"
                t.startsWith("http", ignoreCase = true) &&
                    !t.contains("ssid=", ignoreCase = true) &&
                    !t.contains("Wifi=", ignoreCase = true) ->
                    return "URL QR without ssid/pwd — need the dash pairing QR (Carbit / EasyConnect / MotoFun)"
                else -> return null
            }
        }

        /**
         * Synthetic pairing URL for bikes that show SSID + password (e.g. Benelli TRK) instead of
         * a scannable Carbit QR. [action]=1 → SoftAP path (not Wi‑Fi Direct).
         * @return raw URL + parsed [QrData], or null if SSID/password blank.
         */
        fun buildManual(ssid: String, pwd: String, displayName: String? = null): Pair<String, QrData>? {
            val s = ssid.trim()
            val p = pwd // keep internal spaces; only require non-blank
            if (s.isEmpty() || p.isEmpty()) return null
            val name = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: s
            val raw = Uri.Builder()
                .scheme("http")
                .authority("manual.opencfmoto.local")
                .path("/")
                .appendQueryParameter("ssid", s)
                .appendQueryParameter("pwd", p)
                .appendQueryParameter("auth", "wpa2-psk")
                .appendQueryParameter("action", "1")
                .appendQueryParameter("name", name)
                .build()
                .toString()
            val qr = parse(raw) ?: return null
            return raw to qr
        }
    }
}
