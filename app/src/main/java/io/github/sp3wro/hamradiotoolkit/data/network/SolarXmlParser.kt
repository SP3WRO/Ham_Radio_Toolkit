package io.github.sp3wro.hamradiotoolkit.data.network

import android.util.Xml
import io.github.sp3wro.hamradiotoolkit.data.model.BandCondition
import io.github.sp3wro.hamradiotoolkit.data.model.SolarData
import io.github.sp3wro.hamradiotoolkit.data.model.VhfCondition
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

object SolarXmlParser {
    fun parse(xmlString: String): SolarData {
        var updated = ""
        var solarFlux = ""
        var aIndex = ""
        var kIndex = ""
        var sunspots = ""
        val hfConditions = mutableListOf<BandCondition>()
        val vhfConditions = mutableListOf<VhfCondition>()

        try {
            val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlString))

            var eventType = parser.eventType
            var currentText = ""
            
            // Atrybuty dla elementu band / phenomenon
            var bandName = ""
            var bandTime = ""
            var vhfName = ""
            var vhfLocation = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagname = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tagname == "band") {
                            bandName = parser.getAttributeValue(null, "name") ?: ""
                            bandTime = parser.getAttributeValue(null, "time") ?: ""
                        } else if (tagname == "phenomenon") {
                            vhfName = parser.getAttributeValue(null, "name") ?: ""
                            vhfLocation = parser.getAttributeValue(null, "location") ?: ""
                        }
                    }
                    XmlPullParser.TEXT -> {
                        currentText = parser.text.trim()
                    }
                    XmlPullParser.END_TAG -> {
                        when (tagname) {
                            "updated" -> updated = currentText
                            "solarflux" -> solarFlux = currentText
                            "aindex" -> aIndex = currentText
                            "kindex" -> kIndex = currentText
                            "sunspots" -> sunspots = currentText
                            "band" -> {
                                if (bandName.isNotEmpty()) {
                                    hfConditions.add(BandCondition(bandName, bandTime, currentText))
                                }
                            }
                            "phenomenon" -> {
                                if (vhfName.isNotEmpty()) {
                                    vhfConditions.add(VhfCondition(vhfName, vhfLocation, currentText))
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return SolarData(
            updated = updated,
            solarFlux = solarFlux,
            aIndex = aIndex,
            kIndex = kIndex,
            sunspots = sunspots,
            hfConditions = hfConditions,
            vhfConditions = vhfConditions
        )
    }
}