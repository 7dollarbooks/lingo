package com.livetranslate.headphones.navigation

data class LatLng(val lat: Double, val lon: Double)

data class Maneuver(
    val instruction: String,
    val lengthKm: Double,
    val beginShapeIndex: Int,
)

data class OsrmRoute(
    val maneuvers: List<Maneuver>,
    val shape: List<LatLng>,
)

enum class TravelMode(val costing: String, val announceMeters: Double, val offRouteMeters: Double) {
    WALK("pedestrian", 30.0, 40.0),
    BIKE("bicycle", 40.0, 50.0),
    CAR("auto", 80.0, 80.0),
}

object ValhallaRoute {
    fun parse(json: String): OsrmRoute? {
        val root = Json.parse(json) as? Map<*, *> ?: return null
        val trip = root["trip"] as? Map<*, *> ?: return null
        val legs = trip["legs"] as? List<*> ?: return null
        val maneuvers = mutableListOf<Maneuver>()
        val shape = mutableListOf<LatLng>()
        for (leg in legs) {
            val map = leg as? Map<*, *> ?: continue
            val encoded = map["shape"] as? String
            if (!encoded.isNullOrBlank()) shape += decodePolyline(encoded, PRECISION_6)
            val items = map["maneuvers"] as? List<*> ?: continue
            for (item in items) {
                val maneuver = item as? Map<*, *> ?: continue
                val instruction = maneuver["instruction"] as? String ?: continue
                if (instruction.isBlank()) continue
                maneuvers += Maneuver(
                    instruction = instruction,
                    lengthKm = (maneuver["length"] as? Double) ?: 0.0,
                    beginShapeIndex = (maneuver["begin_shape_index"] as? Double)?.toInt() ?: 0,
                )
            }
        }
        if (maneuvers.isEmpty()) return null
        return OsrmRoute(maneuvers, shape)
    }

    fun nextInstruction(route: OsrmRoute, index: Int): String? =
        route.maneuvers.getOrNull(index)?.instruction

    /** Valhalla's default shape precision is 1e6. The classic test vector uses 1e5. */
    fun decodePolyline(encoded: String, precision: Double): List<LatLng> {
        val points = mutableListOf<LatLng>()
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            val latDelta = decodeChunk(encoded, index) ?: return points
            index = latDelta.second
            lat += latDelta.first
            val lonDelta = decodeChunk(encoded, index) ?: return points
            index = lonDelta.second
            lon += lonDelta.first
            points += LatLng(lat / precision, lon / precision)
        }
        return points
    }

    private fun decodeChunk(encoded: String, start: Int): Pair<Int, Int>? {
        var shift = 0
        var result = 0
        var index = start
        var byte: Int
        do {
            if (index >= encoded.length) return null
            byte = encoded[index++].code - 63
            result = result or ((byte and 0x1f) shl shift)
            shift += 5
        } while (byte >= 0x20)
        val delta = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        return delta to index
    }

    fun haversineMeters(a: LatLng, b: LatLng): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h))
    }

    fun distanceToShapeMeters(point: LatLng, shape: List<LatLng>): Double {
        if (shape.isEmpty()) return Double.POSITIVE_INFINITY
        if (shape.size == 1) return haversineMeters(point, shape[0])
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until shape.lastIndex) {
            best = minOf(best, distanceToSegmentMeters(point, shape[i], shape[i + 1]))
        }
        return best
    }

    private fun distanceToSegmentMeters(point: LatLng, start: LatLng, end: LatLng): Double {
        val ax = metersEast(start, start)
        val ay = 0.0
        val bx = metersEast(start, end)
        val by = metersNorth(start, end)
        val px = metersEast(start, point)
        val py = metersNorth(start, point)
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
        val closestEast = dx * t
        val closestNorth = dy * t
        val offEast = px - closestEast
        val offNorth = py - closestNorth
        return kotlin.math.sqrt(offEast * offEast + offNorth * offNorth)
    }

    private fun metersEast(origin: LatLng, point: LatLng): Double =
        Math.toRadians(point.lon - origin.lon) * 6_371_000.0 * kotlin.math.cos(Math.toRadians(origin.lat))

    private fun metersNorth(origin: LatLng, point: LatLng): Double =
        Math.toRadians(point.lat - origin.lat) * 6_371_000.0

    private const val PRECISION_6 = 1.0e6
}

/**
 * Just enough JSON for a Valhalla trip and a Nominatim result. No Android dependency.
 */
internal object Json {
    fun parse(text: String): Any? = Reader(text).parseValue()

    private class Reader(private val s: String) {
        private var i = 0

        fun parseValue(): Any? {
            skip()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val out = linkedMapOf<String, Any?>()
            i++
            skip()
            if (i < s.length && s[i] == '}') {
                i++
                return out
            }
            while (i < s.length) {
                skip()
                val key = parseString()
                skip()
                if (i < s.length && s[i] == ':') i++
                out[key] = parseValue()
                skip()
                if (i < s.length && s[i] == ',') {
                    i++
                    continue
                }
                if (i < s.length && s[i] == '}') i++
                break
            }
            return out
        }

        private fun parseArray(): List<Any?> {
            val out = mutableListOf<Any?>()
            i++
            skip()
            if (i < s.length && s[i] == ']') {
                i++
                return out
            }
            while (i < s.length) {
                out += parseValue()
                skip()
                if (i < s.length && s[i] == ',') {
                    i++
                    continue
                }
                if (i < s.length && s[i] == ']') i++
                break
            }
            return out
        }

        private fun parseString(): String {
            if (i >= s.length || s[i] != '"') return ""
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') break
                if (c != '\\') {
                    sb.append(c)
                    continue
                }
                if (i >= s.length) break
                when (val e = s[i++]) {
                    '"', '\\', '/' -> sb.append(e)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = s.substring(i, (i + 4).coerceAtMost(s.length))
                        i += hex.length
                        sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                    }
                    else -> sb.append(e)
                }
            }
            return sb.toString()
        }

        private fun parseNumber(): Double {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) {
                if ((s[i] == '+' || s[i] == '-') && i > start && s[i - 1] != 'e' && s[i - 1] != 'E') break
                i++
            }
            return s.substring(start, i).toDoubleOrNull() ?: 0.0
        }

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
