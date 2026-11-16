package com.astroscout.data

import com.astroscout.data.remote.dto.LightPollutionResponse
import com.astroscout.data.remote.dto.NominatimPlace
import com.astroscout.data.remote.dto.OverpassResponse
import com.astroscout.data.remote.mapper.ParkingMapper
import com.astroscout.domain.model.GeoPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtoParsingTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }
    private fun el(s: String) = json.decodeFromString(JsonElement.serializer(), s)

    @Test
    fun `light pollution - canonical shape`() {
        val r = LightPollutionResponse.fromJson(el("""{"sqm": 21.45, "bortle": 3}"""))
        assertEquals(21.45, r.sqm!!, 1e-9)
        assertEquals(3, r.bortle)
    }

    @Test
    fun `light pollution - alternative keys, strings and nesting`() {
        val r = LightPollutionResponse.fromJson(el("""{"status":"ok","data":{"sqm_zenith":"20.9","bortle_class":4.0}}"""))
        assertEquals(20.9, r.sqm!!, 1e-9)
        assertEquals(4, r.bortle)
    }

    @Test
    fun `light pollution - out of range values are rejected`() {
        val r = LightPollutionResponse.fromJson(el("""{"sqm": -1, "bortle": 42}"""))
        assertTrue(r.isEmpty)
    }

    @Test
    fun `overpass - nodes ways and remark`() {
        val body = """
        {"version":0.6,"generator":"Overpass API","elements":[
          {"type":"node","id":1,"lat":45.001,"lon":10.0,"tags":{"amenity":"parking","name":"P Rifugio","capacity":"~40","fee":"no"}},
          {"type":"way","id":2,"center":{"lat":45.02,"lon":10.0},"tags":{"amenity":"parking","fee":"yes","surface":"gravel"}},
          {"type":"way","id":3,"tags":{"amenity":"parking"}}
        ]}
        """
        val resp = json.decodeFromString(OverpassResponse.serializer(), body)
        val list = ParkingMapper.toParkings(resp, GeoPoint(45.0, 10.0))
        assertEquals(2, list.size) // il way senza center viene scartato
        assertEquals("P Rifugio", list[0].name)
        assertEquals(40, list[0].capacity)
        assertEquals(111.0, list[0].distanceMeters, 2.0)
        assertNull(list[1].name)
        assertEquals("yes", list[1].fee)
        assertTrue(list[0].distanceMeters < list[1].distanceMeters)

        val err = json.decodeFromString(OverpassResponse.serializer(), """{"elements":[],"remark":"runtime error: Query timed out"}""")
        assertTrue(err.remark!!.contains("error"))
    }

    @Test
    fun `overpass query uses around filter and center output`() {
        val q = ParkingMapper.buildQuery(GeoPoint(45.5, 9.25), 3000)
        assertTrue(q.contains("""nwr["amenity"="parking"]"""))
        assertTrue(q.contains("(around:3000,45.500000,9.250000)"))
        assertTrue(q.contains("out center tags;"))
    }

    @Test
    fun `nominatim lat lon are strings`() {
        val list = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(NominatimPlace.serializer()),
            """[{"place_id":123,"lat":"46.1","lon":"9.9","display_name":"Sondrio, Lombardia, Italia","category":"boundary","type":"administrative","importance":0.6}]""",
        )
        assertEquals("46.1", list[0].lat)
        assertEquals("Sondrio, Lombardia, Italia", list[0].displayName)
    }
}
