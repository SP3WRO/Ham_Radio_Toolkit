/* Copyright 2012 Mozilla Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Przeniesienie na Kotlin dekodera JPEG 2000 z pdf.js (src/core/jpx.js i arithmetic_decoder.js, wersja 3.11.174),
 * HamRadioToolkit 2026: zmieniona struktura klas, dodane składanie kafli w obraz i wynik w RGB/szarości.
 */
package io.github.sp3wro.hamradiotoolkit.dsstv

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class Jpeg2000Exception(message: String) : Exception("JPEG 2000: $message")

/** Zdekodowany obraz: [components] kanałów 8-bitowych, przeplecionych, wiersz po wierszu. */
class Jpeg2000Image(val width: Int, val height: Int, val components: Int, val pixels: ByteArray)

object Jpeg2000Decoder {

    fun decode(data: ByteArray): Jpeg2000Image {
        val d = IntArray(data.size) { data[it].toInt() and 0xFF }
        if (d.size >= 2 && readUint16(d, 0) == 0xFF4F) return Codestream().parse(d, 0, d.size)
        var position = 0
        while (position + 8 <= d.size) {
            var headerSize = 8
            var lbox = readUint32(d, position)
            val tbox = readUint32(d, position + 4).toInt()
            position += headerSize
            if (lbox == 1L) {
                lbox = readUint32(d, position) * 4294967296L + readUint32(d, position + 4)
                position += 8
                headerSize += 8
            }
            if (lbox == 0L) lbox = (d.size - position + headerSize).toLong()
            if (lbox < headerSize) throw Jpeg2000Exception("Invalid box field size")
            val dataLength = (lbox - headerSize).toInt()
            var jump = true
            when (tbox) {
                0x6a703268 -> jump = false // 'jp2h' – pudełka podrzędne
                0x6a703263 -> return Codestream().parse(d, position, minOf(d.size, position + dataLength)) // 'jp2c'
            }
            if (jump) position += dataLength
        }
        throw Jpeg2000Exception("Brak strumienia kodowego")
    }

    private fun readUint16(d: IntArray, p: Int) = (d[p] shl 8) or d[p + 1]
    private fun readUint32(d: IntArray, p: Int): Long =
        ((d[p].toLong() shl 24) or (d[p + 1].toLong() shl 16) or (d[p + 2].toLong() shl 8) or d[p + 3].toLong())

    private fun log2(x: Int): Int = if (x <= 0) 0 else ceil(kotlin.math.ln(x.toDouble()) / kotlin.math.ln(2.0) - 1e-12).toInt()

    // ------------------------------------------------------------------ struktury

    private class Siz(
        val xsiz: Int, val ysiz: Int, val xosiz: Int, val yosiz: Int,
        val xtsiz: Int, val ytsiz: Int, val xtosiz: Int, val ytosiz: Int, val csiz: Int
    )

    private class Component(val precision: Int, val isSigned: Boolean, val xrsiz: Int, val yrsiz: Int) {
        var x0 = 0; var x1 = 0; var y0 = 0; var y1 = 0
    }

    private class Spqcd(val epsilon: Int, val mu: Int)
    private class Qcd(val noQuantization: Boolean, val scalarExpounded: Boolean, val guardBits: Int, val spqcds: List<Spqcd>)

    private class Cod(
        val entropyCoderWithCustomPrecincts: Boolean,
        val sopMarkerUsed: Boolean,
        val ephMarkerUsed: Boolean,
        val progressionOrder: Int,
        val layersCount: Int,
        val multipleComponentTransform: Int,
        val decompositionLevelsCount: Int,
        val xcb: Int,
        val ycb: Int,
        val resetContextProbabilities: Boolean,
        val segmentationSymbolUsed: Boolean,
        val reversibleTransformation: Boolean,
        val precinctsSizes: List<IntArray>
    )

    private class TilePart(val index: Int, val dataEnd: Int, val partIndex: Int) {
        var cod: Cod? = null
        var coc = HashMap<Int, Cod>()
        var qcd: Qcd? = null
        var qcc = HashMap<Int, Qcd>()
    }

    private class Precinct(var cbxMin: Int, var cbyMin: Int, var cbxMax: Int, var cbyMax: Int) {
        var inclusionTree: InclusionTree? = null
        var zeroBitPlanesTree: TagTree? = null
    }

    private class CodeblockData(val start: Int, val end: Int, val codingpasses: Int)

    private class Codeblock(val cbx: Int, val cby: Int, val tbx0_: Int, val tby0_: Int, val tbx1_: Int, val tby1_: Int) {
        var precinctNumber = 0
        var subbandType = ""
        var lblock = 3
        lateinit var precinct: Precinct
        var included: Boolean? = null
        var zeroBitPlanes = 0
        var data: ArrayList<CodeblockData>? = null
    }

    private class PrecinctParameters(
        val precinctWidth: Int, val precinctHeight: Int, val numprecinctswide: Int, val numprecinctshigh: Int,
        val numprecincts: Int, val precinctWidthInSubband: Int, val precinctHeightInSubband: Int
    )

    private class Resolution(val trx0: Int, val try0: Int, val trx1: Int, val try1: Int, val resLevel: Int) {
        lateinit var precinctParameters: PrecinctParameters
        var subbands: List<Subband> = emptyList()
    }

    private class Subband(val type: String, val tbx0: Int, val tby0: Int, val tbx1: Int, val tby1: Int, val resolution: Resolution) {
        var codeblocks: List<Codeblock> = emptyList()
    }

    private class TileComponent(val tcx0: Int, val tcy0: Int, val tcx1: Int, val tcy1: Int) {
        lateinit var quantizationParameters: Qcd
        lateinit var codingStyleParameters: Cod
        var resolutions: List<Resolution> = emptyList()
        var subbands: List<Subband> = emptyList()
    }

    private class Tile(val tx0: Int, val ty0: Int, val tx1: Int, val ty1: Int) {
        val components = ArrayList<TileComponent>()
        var codingStyleDefaultParameters: Cod? = null
        var packetsIterator: PacketIterator? = null
    }

    private class Packet(val layerNumber: Int, val codeblocks: List<Codeblock>)

    private fun interface PacketIterator {
        fun nextPacket(): Packet
    }

    private fun ceilDiv(a: Int, b: Int): Int = ceil(a.toDouble() / b).toInt()

    // ------------------------------------------------------------------ strumień kodowy

    private class Codestream {
        var siz: Siz? = null
        val components = ArrayList<Component>()
        val tiles = ArrayList<Tile>()
        var cod: Cod? = null
        var qcd: Qcd? = null
        var qcc = HashMap<Int, Qcd>()
        var coc = HashMap<Int, Cod>()
        var mainHeader = false
        var currentTile: TilePart? = null

        fun parse(data: IntArray, start: Int, end: Int): Jpeg2000Image {
            dataRef = data
            try {
                var position = start
                while (position + 1 < end) {
                    val code = readUint16(data, position)
                    position += 2
                    var length = 0
                    when (code) {
                        0xFF4F -> mainHeader = true
                        0xFFD9 -> {}
                        0xFF51 -> {
                            length = readUint16(data, position)
                            val s = Siz(
                                readUint32(data, position + 4).toInt(), readUint32(data, position + 8).toInt(),
                                readUint32(data, position + 12).toInt(), readUint32(data, position + 16).toInt(),
                                readUint32(data, position + 20).toInt(), readUint32(data, position + 24).toInt(),
                                readUint32(data, position + 28).toInt(), readUint32(data, position + 32).toInt(),
                                readUint16(data, position + 36)
                            )
                            var j = position + 38
                            for (i in 0 until s.csiz) {
                                val c = Component((data[j] and 0x7f) + 1, data[j] and 0x80 != 0, data[j + 1], data[j + 2])
                                j += 3
                                c.x0 = ceilDiv(s.xosiz, c.xrsiz); c.x1 = ceilDiv(s.xsiz, c.xrsiz)
                                c.y0 = ceilDiv(s.yosiz, c.yrsiz); c.y1 = ceilDiv(s.ysiz, c.yrsiz)
                                components += c
                            }
                            siz = s
                            calculateTileGrids(s)
                            qcc = HashMap(); coc = HashMap()
                        }
                        0xFF5C, 0xFF5D -> {
                            length = readUint16(data, position)
                            var j = position + 2
                            var cqcc = -1
                            if (code == 0xFF5D) {
                                if (siz!!.csiz < 257) cqcc = data[j++] else { cqcc = readUint16(data, j); j += 2 }
                            }
                            val sqcd = data[j++]
                            val (spqcdSize, scalarExpounded) = when (sqcd and 0x1f) {
                                0 -> 8 to true
                                1 -> 16 to false
                                2 -> 16 to true
                                else -> throw Jpeg2000Exception("Invalid SQcd value $sqcd")
                            }
                            val spqcds = ArrayList<Spqcd>()
                            while (j < length + position) {
                                if (spqcdSize == 8) {
                                    spqcds += Spqcd(data[j++] shr 3, 0)
                                } else {
                                    spqcds += Spqcd(data[j] shr 3, ((data[j] and 0x7) shl 8) or data[j + 1])
                                    j += 2
                                }
                            }
                            val q = Qcd(spqcdSize == 8, scalarExpounded, sqcd shr 5, spqcds)
                            if (code == 0xFF5C) {
                                if (mainHeader) qcd = q else { currentTile!!.qcd = q; currentTile!!.qcc = HashMap() }
                            } else {
                                if (mainHeader) qcc[cqcc] = q else currentTile!!.qcc[cqcc] = q
                            }
                        }
                        0xFF52 -> {
                            length = readUint16(data, position)
                            var j = position + 2
                            val scod = data[j++]
                            val progressionOrder = data[j++]
                            val layersCount = readUint16(data, j); j += 2
                            val mct = data[j++]
                            val levels = data[j++]
                            val xcb = (data[j++] and 0xf) + 2
                            val ycb = (data[j++] and 0xf) + 2
                            val blockStyle = data[j++]
                            val reversible = data[j++]
                            val sizes = ArrayList<IntArray>()
                            if (scod and 1 != 0) {
                                while (j < length + position) {
                                    val ps = data[j++]
                                    sizes += intArrayOf(ps and 0xf, ps shr 4)
                                }
                            }
                            if (blockStyle and (1 or 4 or 8 or 16) != 0) {
                                throw Jpeg2000Exception("nieobsługiwane opcje kodowania bloków ($blockStyle)")
                            }
                            val c = Cod(
                                scod and 1 != 0, scod and 2 != 0, scod and 4 != 0, progressionOrder, layersCount, mct,
                                levels, xcb, ycb, blockStyle and 2 != 0, blockStyle and 32 != 0, reversible != 0, sizes
                            )
                            if (mainHeader) cod = c else { currentTile!!.cod = c; currentTile!!.coc = HashMap() }
                        }
                        0xFF90 -> {
                            length = readUint16(data, position)
                            val tileLength = readUint32(data, position + 4).toInt()
                            val t = TilePart(readUint16(data, position + 2), tileLength + position - 2, data[position + 8])
                            mainHeader = false
                            if (t.partIndex == 0) {
                                t.cod = cod
                                t.coc = HashMap(coc)
                                t.qcd = qcd
                                t.qcc = HashMap(qcc)
                            }
                            currentTile = t
                        }
                        0xFF93 -> {
                            val t = currentTile!!
                            if (t.partIndex == 0) {
                                initializeTile(t.index)
                                buildPackets()
                            }
                            length = t.dataEnd - position
                            parseTilePackets(data, position, length)
                        }
                        0xFF53, 0xFF55, 0xFF57, 0xFF58, 0xFF64 -> length = readUint16(data, position)
                        else -> throw Jpeg2000Exception("Unknown codestream code: ${code.toString(16)}")
                    }
                    position += length
                }
            } catch (e: Jpeg2000Exception) {
                if (siz == null || tiles.isEmpty() || tiles.all { it.packetsIterator == null }) throw e
            } catch (e: IndexOutOfBoundsException) {
                // Ucięty strumień (np. niepełny odbiór) – dekodujemy to, co przyszło
                if (siz == null || tiles.all { it.packetsIterator == null }) throw Jpeg2000Exception("ucięty strumień")
            }
            return assemble()
        }

        private fun calculateTileGrids(s: Siz) {
            val numX = ceilDiv(s.xsiz - s.xtosiz, s.xtsiz)
            val numY = ceilDiv(s.ysiz - s.ytosiz, s.ytsiz)
            for (q in 0 until numY) for (p in 0 until numX) {
                tiles += Tile(
                    max(s.xtosiz + p * s.xtsiz, s.xosiz), max(s.ytosiz + q * s.ytsiz, s.yosiz),
                    min(s.xtosiz + (p + 1) * s.xtsiz, s.xsiz), min(s.ytosiz + (q + 1) * s.ytsiz, s.ysiz)
                )
            }
            for (c in components) for (t in tiles) {
                t.components += TileComponent(ceilDiv(t.tx0, c.xrsiz), ceilDiv(t.ty0, c.yrsiz), ceilDiv(t.tx1, c.xrsiz), ceilDiv(t.ty1, c.yrsiz))
            }
        }

        private fun initializeTile(tileIndex: Int) {
            val tile = tiles[tileIndex]
            val cur = currentTile!!
            for (c in 0 until siz!!.csiz) {
                val comp = tile.components[c]
                comp.quantizationParameters = cur.qcc[c] ?: cur.qcd!!
                comp.codingStyleParameters = cur.coc[c] ?: cur.cod!!
            }
            tile.codingStyleDefaultParameters = cur.cod
        }

        private fun blocksDimensions(component: TileComponent, r: Int): IntArray {
            val cod = component.codingStyleParameters
            val ppx: Int
            val ppy: Int
            if (!cod.entropyCoderWithCustomPrecincts) {
                ppx = 15; ppy = 15
            } else {
                ppx = cod.precinctsSizes[r][0]; ppy = cod.precinctsSizes[r][1]
            }
            val xcb = if (r > 0) min(cod.xcb, ppx - 1) else min(cod.xcb, ppx)
            val ycb = if (r > 0) min(cod.ycb, ppy - 1) else min(cod.ycb, ppy)
            return intArrayOf(ppx, ppy, xcb, ycb)
        }

        private fun buildPrecincts(resolution: Resolution, dim: IntArray) {
            val pw = 1 shl dim[0]
            val ph = 1 shl dim[1]
            val isZero = resolution.resLevel == 0
            val pwSub = 1 shl (dim[0] + if (isZero) 0 else -1)
            val phSub = 1 shl (dim[1] + if (isZero) 0 else -1)
            val wide = if (resolution.trx1 > resolution.trx0) ceilDiv(resolution.trx1, pw) - resolution.trx0 / pw else 0
            val high = if (resolution.try1 > resolution.try0) ceilDiv(resolution.try1, ph) - resolution.try0 / ph else 0
            resolution.precinctParameters = PrecinctParameters(pw, ph, wide, high, wide * high, pwSub, phSub)
        }

        private fun buildCodeblocks(subband: Subband, dim: IntArray) {
            val xcb = dim[2]
            val ycb = dim[3]
            val cw = 1 shl xcb
            val ch = 1 shl ycb
            val cbx0 = subband.tbx0 shr xcb
            val cby0 = subband.tby0 shr ycb
            val cbx1 = (subband.tbx1 + cw - 1) shr xcb
            val cby1 = (subband.tby1 + ch - 1) shr ycb
            val pp = subband.resolution.precinctParameters
            val codeblocks = ArrayList<Codeblock>()
            val precincts = HashMap<Int, Precinct>()
            for (j in cby0 until cby1) for (i in cbx0 until cbx1) {
                val cb = Codeblock(
                    i, j, max(subband.tbx0, cw * i), max(subband.tby0, ch * j),
                    min(subband.tbx1, cw * (i + 1)), min(subband.tby1, ch * (j + 1))
                )
                val pi = (cb.tbx0_ - subband.tbx0) / pp.precinctWidthInSubband
                val pj = (cb.tby0_ - subband.tby0) / pp.precinctHeightInSubband
                cb.precinctNumber = pi + pj * pp.numprecinctswide
                cb.subbandType = subband.type
                if (cb.tbx1_ <= cb.tbx0_ || cb.tby1_ <= cb.tby0_) continue
                codeblocks += cb
                var precinct = precincts[cb.precinctNumber]
                if (precinct != null) {
                    if (i < precinct.cbxMin) precinct.cbxMin = i else if (i > precinct.cbxMax) precinct.cbxMax = i
                    if (j < precinct.cbyMin) precinct.cbxMin = j else if (j > precinct.cbyMax) precinct.cbyMax = j
                } else {
                    precinct = Precinct(i, j, i, j)
                    precincts[cb.precinctNumber] = precinct
                }
                cb.precinct = precinct
            }
            subband.codeblocks = codeblocks
        }

        private fun createPacket(resolution: Resolution, precinctNumber: Int, layerNumber: Int): Packet {
            val list = ArrayList<Codeblock>()
            for (sb in resolution.subbands) for (cb in sb.codeblocks) if (cb.precinctNumber == precinctNumber) list += cb
            return Packet(layerNumber, list)
        }

        private fun buildPackets() {
            val tile = tiles[currentTile!!.index]
            for (component in tile.components) {
                val levels = component.codingStyleParameters.decompositionLevelsCount
                val resolutions = ArrayList<Resolution>()
                val subbands = ArrayList<Subband>()
                for (r in 0..levels) {
                    val dim = blocksDimensions(component, r)
                    val scale = 1 shl (levels - r)
                    val res = Resolution(
                        ceilDiv(component.tcx0, scale), ceilDiv(component.tcy0, scale),
                        ceilDiv(component.tcx1, scale), ceilDiv(component.tcy1, scale), r
                    )
                    buildPrecincts(res, dim)
                    resolutions += res
                    if (r == 0) {
                        val sb = Subband(
                            "LL", ceilDiv(component.tcx0, scale), ceilDiv(component.tcy0, scale),
                            ceilDiv(component.tcx1, scale), ceilDiv(component.tcy1, scale), res
                        )
                        buildCodeblocks(sb, dim)
                        subbands += sb
                        res.subbands = listOf(sb)
                    } else {
                        val bscale = (1 shl (levels - r + 1)).toDouble()
                        fun c(v: Int, half: Boolean) = ceil(v / bscale - if (half) 0.5 else 0.0).toInt()
                        val hl = Subband("HL", c(component.tcx0, true), c(component.tcy0, false), c(component.tcx1, true), c(component.tcy1, false), res)
                        val lh = Subband("LH", c(component.tcx0, false), c(component.tcy0, true), c(component.tcx1, false), c(component.tcy1, true), res)
                        val hh = Subband("HH", c(component.tcx0, true), c(component.tcy0, true), c(component.tcx1, true), c(component.tcy1, true), res)
                        // Prekursory muszą znać rezolucję przed podziałem na bloki
                        res.subbands = listOf(hl, lh, hh)
                        for (sb in res.subbands) {
                            buildCodeblocks(sb, dim)
                            subbands += sb
                        }
                    }
                }
                component.resolutions = resolutions
                component.subbands = subbands
            }
            val cod = tile.codingStyleDefaultParameters!!
            tile.packetsIterator = when (cod.progressionOrder) {
                0 -> lrcp(tile, cod.layersCount)
                1 -> rlcp(tile, cod.layersCount)
                2 -> rpcl(tile, cod.layersCount)
                3 -> pcrl(tile, cod.layersCount)
                4 -> cprl(tile, cod.layersCount)
                else -> throw Jpeg2000Exception("Unsupported progression order ${cod.progressionOrder}")
            }
        }

        private fun maxLevels(tile: Tile) = tile.components.maxOf { it.codingStyleParameters.decompositionLevelsCount }

        // Iteratory pakietów (B.12.1) jako sekwencje
        private fun iteratorOf(seq: Sequence<Packet>): PacketIterator {
            val it = seq.iterator()
            return PacketIterator { if (it.hasNext()) it.next() else throw Jpeg2000Exception("Out of packets") }
        }

        private fun lrcp(tile: Tile, layers: Int) = iteratorOf(sequence {
            val maxL = maxLevels(tile)
            for (l in 0 until layers) for (r in 0..maxL) for (comp in tile.components) {
                if (r > comp.codingStyleParameters.decompositionLevelsCount) continue
                val res = comp.resolutions[r]
                for (k in 0 until res.precinctParameters.numprecincts) yield(createPacket(res, k, l))
            }
        })

        private fun rlcp(tile: Tile, layers: Int) = iteratorOf(sequence {
            val maxL = maxLevels(tile)
            for (r in 0..maxL) for (l in 0 until layers) for (comp in tile.components) {
                if (r > comp.codingStyleParameters.decompositionLevelsCount) continue
                val res = comp.resolutions[r]
                for (k in 0 until res.precinctParameters.numprecincts) yield(createPacket(res, k, l))
            }
        })

        private fun rpcl(tile: Tile, layers: Int) = iteratorOf(sequence {
            val maxL = maxLevels(tile)
            val maxPrec = IntArray(maxL + 1) { r ->
                tile.components.filter { r < it.resolutions.size }.maxOfOrNull { it.resolutions[r].precinctParameters.numprecincts } ?: 0
            }
            for (r in 0..maxL) for (p in 0 until maxPrec[r]) for (comp in tile.components) {
                if (r > comp.codingStyleParameters.decompositionLevelsCount) continue
                val res = comp.resolutions[r]
                if (p >= res.precinctParameters.numprecincts) continue
                for (l in 0 until layers) yield(createPacket(res, p, l))
            }
        })

        private class SizeInfo(val width: Int, val height: Int)
        private class CompSizes(val resolutions: Array<SizeInfo?>, val minWidth: Int, val minHeight: Int, val maxNumWide: Int, val maxNumHigh: Int)

        private fun precinctSizes(tile: Tile): Pair<List<CompSizes>, IntArray> {
            var minW = Int.MAX_VALUE; var minH = Int.MAX_VALUE; var maxWide = 0; var maxHigh = 0
            val list = tile.components.map { comp ->
                val levels = comp.codingStyleParameters.decompositionLevelsCount
                val perRes = arrayOfNulls<SizeInfo>(levels + 1)
                var cMinW = Int.MAX_VALUE; var cMinH = Int.MAX_VALUE; var cMaxW = 0; var cMaxH = 0
                var scale = 1
                for (r in levels downTo 0) {
                    val res = comp.resolutions[r]
                    val w = scale * res.precinctParameters.precinctWidth
                    val h = scale * res.precinctParameters.precinctHeight
                    cMinW = min(cMinW, w); cMinH = min(cMinH, h)
                    cMaxW = max(cMaxW, res.precinctParameters.numprecinctswide)
                    cMaxH = max(cMaxH, res.precinctParameters.numprecinctshigh)
                    perRes[r] = SizeInfo(w, h)
                    scale = scale shl 1
                }
                minW = min(minW, cMinW); minH = min(minH, cMinH)
                maxWide = max(maxWide, cMaxW); maxHigh = max(maxHigh, cMaxH)
                CompSizes(perRes, cMinW, cMinH, cMaxW, cMaxH)
            }
            return list to intArrayOf(minW, minH, maxWide, maxHigh)
        }

        private fun precinctIndex(px: Int, py: Int, size: SizeInfo, minW: Int, minH: Int, res: Resolution): Int? {
            val posX = px * minW
            val posY = py * minH
            if (posX % size.width != 0 || posY % size.height != 0) return null
            val startRow = (posY / size.width) * res.precinctParameters.numprecinctswide
            return posX / size.height + startRow
        }

        private fun pcrl(tile: Tile, layers: Int) = iteratorOf(sequence {
            val (sizes, g) = precinctSizes(tile)
            for (py in 0 until g[3]) for (px in 0 until g[2]) for ((c, comp) in tile.components.withIndex()) {
                for (r in 0..comp.codingStyleParameters.decompositionLevelsCount) {
                    val res = comp.resolutions[r]
                    val k = precinctIndex(px, py, sizes[c].resolutions[r]!!, g[0], g[1], res) ?: continue
                    for (l in 0 until layers) yield(createPacket(res, k, l))
                }
            }
        })

        private fun cprl(tile: Tile, layers: Int) = iteratorOf(sequence {
            val (sizes, _) = precinctSizes(tile)
            for ((c, comp) in tile.components.withIndex()) {
                val s = sizes[c]
                for (py in 0 until s.maxNumHigh) for (px in 0 until s.maxNumWide) {
                    for (r in 0..comp.codingStyleParameters.decompositionLevelsCount) {
                        val res = comp.resolutions[r]
                        val k = precinctIndex(px, py, s.resolutions[r]!!, s.minWidth, s.minHeight, res) ?: continue
                        for (l in 0 until layers) yield(createPacket(res, k, l))
                    }
                }
            }
        })

        // ------------------------------------------------------------------ pakiety (B.10)

        private fun parseTilePackets(data: IntArray, offset: Int, dataLength: Int) {
            var position = 0
            var buffer = 0L
            var bufferSize = 0
            var skipNextBit = false
            fun byteAt(i: Int) = if (i < data.size) data[i] else 0
            fun readBits(count: Int): Int {
                while (bufferSize < count) {
                    val b = byteAt(offset + position)
                    position++
                    if (skipNextBit) {
                        buffer = (buffer shl 7) or b.toLong()
                        bufferSize += 7
                        skipNextBit = false
                    } else {
                        buffer = (buffer shl 8) or b.toLong()
                        bufferSize += 8
                    }
                    if (b == 0xff) skipNextBit = true
                }
                bufferSize -= count
                return ((buffer ushr bufferSize) and ((1L shl count) - 1)).toInt()
            }
            fun skipMarkerIfEqual(value: Int): Boolean {
                if (byteAt(offset + position - 1) == 0xff && byteAt(offset + position) == value) {
                    position += 1
                    return true
                } else if (byteAt(offset + position) == 0xff && byteAt(offset + position + 1) == value) {
                    position += 2
                    return true
                }
                return false
            }
            fun alignToByte() {
                bufferSize = 0
                if (skipNextBit) {
                    position++
                    skipNextBit = false
                }
            }
            fun readCodingpasses(): Int {
                if (readBits(1) == 0) return 1
                if (readBits(1) == 0) return 2
                var value = readBits(2)
                if (value < 3) return value + 3
                value = readBits(5)
                if (value < 31) return value + 6
                value = readBits(7)
                return value + 37
            }
            val tile = tiles[currentTile!!.index]
            val sop = cod!!.sopMarkerUsed
            val eph = cod!!.ephMarkerUsed
            val iterator = tile.packetsIterator!!
            while (position < dataLength) {
                alignToByte()
                if (sop && skipMarkerIfEqual(0x91)) position += 4
                val packet = iterator.nextPacket()
                if (readBits(1) == 0) continue
                val layerNumber = packet.layerNumber
                val queue = ArrayList<Triple<Codeblock, Int, Int>>()
                for (codeblock in packet.codeblocks) {
                    var precinct = codeblock.precinct
                    val column = codeblock.cbx - precinct.cbxMin
                    val row = codeblock.cby - precinct.cbyMin
                    var included = false
                    var firstTime = false
                    if (codeblock.included != null) {
                        included = readBits(1) != 0
                    } else {
                        precinct = codeblock.precinct
                        val inclusionTree: InclusionTree
                        if (precinct.inclusionTree != null) {
                            inclusionTree = precinct.inclusionTree!!
                        } else {
                            val width = precinct.cbxMax - precinct.cbxMin + 1
                            val height = precinct.cbyMax - precinct.cbyMin + 1
                            inclusionTree = InclusionTree(width, height, layerNumber)
                            precinct.inclusionTree = inclusionTree
                            precinct.zeroBitPlanesTree = TagTree(width, height)
                            for (l in 0 until layerNumber) if (readBits(1) != 0) throw Jpeg2000Exception("Invalid tag tree")
                        }
                        if (inclusionTree.reset(column, row, layerNumber)) {
                            while (true) {
                                if (readBits(1) != 0) {
                                    if (!inclusionTree.nextLevel()) {
                                        codeblock.included = true
                                        included = true
                                        firstTime = true
                                        break
                                    }
                                } else {
                                    inclusionTree.incrementValue(layerNumber)
                                    break
                                }
                            }
                        }
                    }
                    if (!included) continue
                    if (firstTime) {
                        val zb = precinct.zeroBitPlanesTree!!
                        zb.reset(column, row)
                        while (true) {
                            if (readBits(1) != 0) {
                                if (!zb.nextLevel()) break
                            } else {
                                zb.incrementValue()
                            }
                        }
                        codeblock.zeroBitPlanes = zb.value
                    }
                    val codingpasses = readCodingpasses()
                    while (readBits(1) != 0) codeblock.lblock++
                    val log = log2(codingpasses)
                    val bits = (if (codingpasses < (1 shl log)) log - 1 else log) + codeblock.lblock
                    queue += Triple(codeblock, codingpasses, readBits(bits))
                }
                alignToByte()
                if (eph) skipMarkerIfEqual(0x92)
                for ((codeblock, passes, len) in queue) {
                    val list = codeblock.data ?: ArrayList<CodeblockData>().also { codeblock.data = it }
                    list += CodeblockData(offset + position, offset + position + len, passes)
                    position += len
                }
            }
        }

        // ------------------------------------------------------------------ współczynniki i falki

        private fun copyCoefficients(
            coefficients: FloatArray, levelWidth: Int, subband: Subband, delta: Float, mb: Int,
            reversible: Boolean, segmentationSymbolUsed: Boolean, resetContextProbabilities: Boolean, data: IntArray
        ) {
            val x0 = subband.tbx0
            val y0 = subband.tby0
            val width = subband.tbx1 - subband.tbx0
            val right = if (subband.type[0] == 'H') 1 else 0
            val bottom = if (subband.type[1] == 'H') levelWidth else 0
            for (codeblock in subband.codeblocks) {
                val blockWidth = codeblock.tbx1_ - codeblock.tbx0_
                val blockHeight = codeblock.tby1_ - codeblock.tby0_
                if (blockWidth == 0 || blockHeight == 0) continue
                val items = codeblock.data ?: continue
                val bitModel = BitModel(blockWidth, blockHeight, codeblock.subbandType, codeblock.zeroBitPlanes)
                var passType = 2
                var totalLength = 0
                var codingpasses = 0
                for (it in items) {
                    totalLength += it.end - it.start
                    codingpasses += it.codingpasses
                }
                val encoded = IntArray(totalLength)
                var p = 0
                for (it in items) for (i in it.start until it.end) encoded[p++] = if (i < data.size) data[i] else 0
                bitModel.decoder = ArithmeticDecoder(encoded, 0, totalLength)
                try {
                    for (j in 0 until codingpasses) {
                        when (passType) {
                            0 -> bitModel.runSignificancePropagationPass()
                            1 -> bitModel.runMagnitudeRefinementPass()
                            2 -> {
                                bitModel.runCleanupPass()
                                if (segmentationSymbolUsed) bitModel.checkSegmentationSymbol()
                            }
                        }
                        if (resetContextProbabilities) bitModel.reset()
                        passType = (passType + 1) % 3
                    }
                } catch (e: Jpeg2000Exception) {
                    // Uszkodzony blok – zostawiamy to, co już zdekodowane
                }
                var off = codeblock.tbx0_ - x0 + (codeblock.tby0_ - y0) * width
                val sign = bitModel.coefficentsSign
                val magnitude = bitModel.coefficentsMagnitude
                val bitsDecoded = bitModel.bitsDecoded
                val correction = if (reversible) 0f else 0.5f
                val interleave = subband.type != "LL"
                var pos = 0
                for (j in 0 until blockHeight) {
                    val row = off / width
                    val levelOffset = 2 * row * (levelWidth - width) + right + bottom
                    for (k in 0 until blockWidth) {
                        var n = magnitude[pos].toFloat()
                        if (n != 0f) {
                            n = (n + correction) * delta
                            if (sign[pos] != 0) n = -n
                            val nb = bitsDecoded[pos]
                            val dst = if (interleave) levelOffset + (off shl 1) else off
                            coefficients[dst] = if ((reversible && nb >= mb) || nb > mb) n else n * (1 shl (mb - nb)).toFloat()
                        }
                        off++
                        pos++
                    }
                    off += width - blockWidth
                }
            }
        }

        private class Coefficients(val width: Int, val height: Int, var items: FloatArray)

        private fun transformTile(tile: Tile, c: Int, data: IntArray): Coefficients {
            val component = tile.components[c]
            val cod = component.codingStyleParameters
            val q = component.quantizationParameters
            val levels = cod.decompositionLevelsCount
            val precision = components[c].precision
            val reversible = cod.reversibleTransformation
            val subbandCoefficients = ArrayList<Coefficients>()
            var b = 0
            for (i in 0..levels) {
                val resolution = component.resolutions[i]
                val width = resolution.trx1 - resolution.trx0
                val height = resolution.try1 - resolution.try0
                val coefficients = FloatArray(width * height)
                for (subband in resolution.subbands) {
                    val mu: Int
                    val epsilon: Int
                    if (!q.scalarExpounded) {
                        mu = q.spqcds[0].mu
                        epsilon = q.spqcds[0].epsilon + if (i > 0) 1 - i else 0
                    } else {
                        mu = q.spqcds[b].mu
                        epsilon = q.spqcds[b].epsilon
                        b++
                    }
                    val gainLog2 = when (subband.type) { "LL" -> 0; "HH" -> 2; else -> 1 }
                    val delta = if (reversible) 1f else (2.0.pow(precision + gainLog2 - epsilon) * (1 + mu / 2048.0)).toFloat()
                    val mb = q.guardBits + epsilon - 1
                    copyCoefficients(coefficients, width, subband, delta, mb, reversible, cod.segmentationSymbolUsed, cod.resetContextProbabilities, data)
                }
                subbandCoefficients += Coefficients(width, height, coefficients)
            }
            return calculateTransform(subbandCoefficients, component.tcx0, component.tcy0, reversible)
        }

        private fun calculateTransform(subbands: List<Coefficients>, u0: Int, v0: Int, reversible: Boolean): Coefficients {
            var ll = subbands[0]
            for (i in 1 until subbands.size) ll = iterate(ll, subbands[i], u0, v0, reversible)
            return ll
        }

        private fun extend(buffer: FloatArray, offset: Int, size: Int) {
            var i1 = offset - 1; var j1 = offset + 1
            var i2 = offset + size - 2; var j2 = offset + size
            buffer[i1--] = buffer[j1++]; buffer[j2++] = buffer[i2--]
            buffer[i1--] = buffer[j1++]; buffer[j2++] = buffer[i2--]
            buffer[i1--] = buffer[j1++]; buffer[j2++] = buffer[i2--]
            buffer[i1] = buffer[j1]; buffer[j2] = buffer[i2]
        }

        private fun iterate(ll: Coefficients, hlLhHh: Coefficients, u0: Int, v0: Int, reversible: Boolean): Coefficients {
            val llWidth = ll.width
            val llHeight = ll.height
            val llItems = ll.items
            val width = hlLhHh.width
            val height = hlLhHh.height
            val items = hlLhHh.items
            var k = 0
            for (i in 0 until llHeight) {
                var l = i * 2 * width
                for (j in 0 until llWidth) {
                    items[l] = llItems[k]
                    k++
                    l += 2
                }
            }
            ll.items = FloatArray(0)
            val pad = 4
            val rowBuffer = FloatArray(width + 2 * pad)
            if (width == 1) {
                if (u0 and 1 != 0) {
                    var kk = 0
                    for (v in 0 until height) { items[kk] *= 0.5f; kk += width }
                }
            } else {
                var kk = 0
                for (v in 0 until height) {
                    System.arraycopy(items, kk, rowBuffer, pad, width)
                    extend(rowBuffer, pad, width)
                    filter(rowBuffer, pad, width, reversible)
                    System.arraycopy(rowBuffer, pad, items, kk, width)
                    kk += width
                }
            }
            if (height == 1) {
                if (v0 and 1 != 0) for (u in 0 until width) items[u] *= 0.5f
            } else {
                val colBuffer = FloatArray(height + 2 * pad)
                for (u in 0 until width) {
                    for (v in 0 until height) colBuffer[pad + v] = items[u + v * width]
                    extend(colBuffer, pad, height)
                    filter(colBuffer, pad, height, reversible)
                    for (v in 0 until height) items[u + v * width] = colBuffer[pad + v]
                }
            }
            return Coefficients(width, height, items)
        }

        private fun filter(x: FloatArray, offset: Int, length: Int, reversible: Boolean) {
            val len = length shr 1
            if (reversible) {
                var j = offset
                for (n in 0 until len + 1) {
                    x[j] -= ((x[j - 1] + x[j + 1] + 2).toInt() shr 2).toFloat()
                    j += 2
                }
                j = offset + 1
                for (n in 0 until len) {
                    x[j] += ((x[j - 1] + x[j + 1]).toInt() shr 1).toFloat()
                    j += 2
                }
                return
            }
            val alpha = -1.586134342059924f
            val beta = -0.052980118572961f
            val gamma = 0.882911075530934f
            val delta = 0.443506852043971f
            val kk = 1.230174104914001f
            val kInv = 1 / kk
            var j = offset - 3
            for (n in 0 until len + 4) { x[j] *= kInv; j += 2 }
            liftingStep(x, offset - 2, len + 3, delta, scaleFirst = kk)
            liftingStep(x, offset - 1, len + 2, gamma, scaleFirst = null)
            liftingStep(x, offset, len + 1, beta, scaleFirst = null)
            if (len != 0) liftingStep(x, offset + 1, len, alpha, scaleFirst = null)
        }

        /** x[j] = s·x[j] − c·(x[j−1] + x[j+1]) dla j co 2 (s = 1, gdy [scaleFirst] == null). */
        private fun liftingStep(x: FloatArray, start: Int, count: Int, c: Float, scaleFirst: Float?) {
            var j = start
            var current = c * x[j - 1]
            var n = count
            while (n-- > 0) {
                val next = c * x[j + 1]
                x[j] = if (scaleFirst != null) scaleFirst * x[j] - current - next else x[j] - (current + next)
                if (n-- > 0) {
                    j += 2
                    current = c * x[j + 1]
                    x[j] = if (scaleFirst != null) scaleFirst * x[j] - current - next else x[j] - (current + next)
                    j += 2
                } else break
            }
        }

        // ------------------------------------------------------------------ składanie obrazu

        private fun assemble(): Jpeg2000Image {
            val s = siz ?: throw Jpeg2000Exception("brak SIZ")
            val count = s.csiz
            val width = s.xsiz - s.xosiz
            val height = s.ysiz - s.yosiz
            val out = ByteArray(width * height * count)
            // Dane potrzebne do dekodowania bloków: cały strumień trzymamy w polach bloków (indeksy w data)
            for (tile in tiles) {
                if (tile.packetsIterator == null) continue
                val transformed = (0 until count).map { transformTile(tile, it, dataRef!!) }
                val t0 = transformed[0]
                val px = IntArray(t0.items.size * count)
                val cod = tile.codingStyleDefaultParameters!!
                if (cod.multipleComponentTransform != 0 && count >= 3) {
                    val shift = components[0].precision - 8
                    val offset = (128 shl shift) + 0.5f
                    val y0i = transformed[0].items; val y1i = transformed[1].items; val y2i = transformed[2].items
                    var pos = 0
                    val alpha01 = count - 3
                    if (!tile.components[0].codingStyleParameters.reversibleTransformation) {
                        for (j in y0i.indices) {
                            val y0 = y0i[j] + offset; val y1 = y1i[j]; val y2 = y2i[j]
                            px[pos++] = (y0 + 1.402f * y2).toInt() shr shift
                            px[pos++] = (y0 - 0.34413f * y1 - 0.71414f * y2).toInt() shr shift
                            px[pos++] = (y0 + 1.772f * y1).toInt() shr shift
                            pos += alpha01
                        }
                    } else {
                        for (j in y0i.indices) {
                            val y0 = y0i[j] + offset; val y1 = y1i[j]; val y2 = y2i[j]
                            val g = y0 - ((y2 + y1).toInt() shr 2)
                            px[pos++] = (g + y2).toInt() shr shift
                            px[pos++] = g.toInt() shr shift
                            px[pos++] = (g + y1).toInt() shr shift
                            pos += alpha01
                        }
                    }
                    if (count == 4) {
                        val y3 = transformed[3].items
                        var p = 3
                        for (j in y3.indices) { px[p] = (y3[j] + offset).toInt() shr shift; p += 4 }
                    }
                } else {
                    for (c in 0 until count) {
                        val items = transformed[c].items
                        val shift = components[c].precision - 8
                        val offset = (128 shl shift) + 0.5f
                        var pos = c
                        for (j in items.indices) {
                            px[pos] = (items[j] + offset).toInt() shr shift
                            pos += count
                        }
                    }
                }
                // Kopiowanie kafla w obraz
                val left = tile.components[0].tcx0 - s.xosiz
                val top = tile.components[0].tcy0 - s.yosiz
                val tw = t0.width
                for (y in 0 until t0.height) {
                    val iy = top + y
                    if (iy !in 0 until height) continue
                    for (x in 0 until tw) {
                        val ix = left + x
                        if (ix !in 0 until width) continue
                        for (c in 0 until count) {
                            out[(iy * width + ix) * count + c] = px[(y * tw + x) * count + c].coerceIn(0, 255).toByte()
                        }
                    }
                }
            }
            return Jpeg2000Image(width, height, count, out)
        }

        var dataRef: IntArray? = null
    }

    // ------------------------------------------------------------------ drzewa znaczników (B.10.2)

    private class TagTree(width0: Int, height0: Int) {
        private class Level(val width: Int, val height: Int) {
            val items = IntArray(width * height) { UNSET }
            var index = 0
        }
        private val levels = ArrayList<Level>()
        private var currentLevel = 0
        var value = 0

        init {
            var w = width0; var h = height0
            val n = log2(max(w, h)) + 1
            for (i in 0 until n) {
                levels += Level(w, h)
                w = ceilDiv(w, 2); h = ceilDiv(h, 2)
            }
        }

        fun reset(i0: Int, j0: Int) {
            var i = i0; var j = j0
            var cl = 0
            var v = 0
            while (cl < levels.size) {
                val level = levels[cl]
                val index = i + j * level.width
                if (level.items[index] != UNSET) {
                    v = level.items[index]
                    break
                }
                level.index = index
                i = i shr 1; j = j shr 1
                cl++
            }
            cl--
            val level = levels[cl]
            level.items[level.index] = v
            currentLevel = cl
        }

        fun incrementValue() {
            val level = levels[currentLevel]
            level.items[level.index]++
        }

        fun nextLevel(): Boolean {
            var cl = currentLevel
            var level = levels[cl]
            val v = level.items[level.index]
            cl--
            if (cl < 0) {
                value = v
                return false
            }
            currentLevel = cl
            level = levels[cl]
            level.items[level.index] = v
            return true
        }

        companion object { const val UNSET = Int.MIN_VALUE }
    }

    private class InclusionTree(width0: Int, height0: Int, defaultValue: Int) {
        private class Level(val width: Int, val height: Int, def: Int) {
            val items = IntArray(width * height) { def }
            var index = 0
        }
        private val levels = ArrayList<Level>()
        private var currentLevel = 0

        init {
            var w = width0; var h = height0
            val n = log2(max(w, h)) + 1
            for (i in 0 until n) {
                levels += Level(w, h, defaultValue and 0xff)
                w = ceilDiv(w, 2); h = ceilDiv(h, 2)
            }
        }

        fun reset(i0: Int, j0: Int, stopValue: Int): Boolean {
            var i = i0; var j = j0
            var cl = 0
            while (cl < levels.size) {
                val level = levels[cl]
                val index = i + j * level.width
                level.index = index
                val v = level.items[index]
                if (v == 0xff) break
                if (v > stopValue) {
                    currentLevel = cl
                    propagateValues()
                    return false
                }
                i = i shr 1; j = j shr 1
                cl++
            }
            currentLevel = cl - 1
            return true
        }

        fun incrementValue(stopValue: Int) {
            val level = levels[currentLevel]
            level.items[level.index] = (stopValue + 1) and 0xff
            propagateValues()
        }

        private fun propagateValues() {
            var li = currentLevel
            var level = levels[li]
            val v = level.items[level.index]
            while (--li >= 0) {
                level = levels[li]
                level.items[level.index] = v
            }
        }

        fun nextLevel(): Boolean {
            var cl = currentLevel
            var level = levels[cl]
            val v = level.items[level.index]
            level.items[level.index] = 0xff
            cl--
            if (cl < 0) return false
            currentLevel = cl
            level = levels[cl]
            level.items[level.index] = v
            return true
        }
    }

    // ------------------------------------------------------------------ modelowanie bitów (D)

    private class BitModel(val width: Int, val height: Int, subband: String, zeroBitPlanes: Int) {
        private val labels: IntArray = when (subband) {
            "HH" -> HH_LABELS
            "HL" -> HL_LABELS
            else -> LL_LH_LABELS
        }
        val neighborsSignificance = IntArray(width * height)
        val coefficentsSign = IntArray(width * height)
        val coefficentsMagnitude = IntArray(width * height)
        val processingFlags = IntArray(width * height)
        val bitsDecoded = IntArray(width * height) { zeroBitPlanes }
        lateinit var decoder: ArithmeticDecoder
        private var contexts = IntArray(19)

        init { reset() }

        fun reset() {
            contexts = IntArray(19)
            contexts[0] = (4 shl 1)
            contexts[UNIFORM_CONTEXT] = (46 shl 1)
            contexts[RUNLENGTH_CONTEXT] = (3 shl 1)
        }

        private fun setNeighborsSignificance(row: Int, column: Int, index: Int) {
            val left = column > 0
            val right = column + 1 < width
            if (row > 0) {
                val i = index - width
                if (left) neighborsSignificance[i - 1] += 0x10
                if (right) neighborsSignificance[i + 1] += 0x10
                neighborsSignificance[i] += 0x04
            }
            if (row + 1 < height) {
                val i = index + width
                if (left) neighborsSignificance[i - 1] += 0x10
                if (right) neighborsSignificance[i + 1] += 0x10
                neighborsSignificance[i] += 0x04
            }
            if (left) neighborsSignificance[index - 1] += 0x01
            if (right) neighborsSignificance[index + 1] += 0x01
            neighborsSignificance[index] = neighborsSignificance[index] or 0x80
        }

        private fun label(index: Int): Int {
            val ns = neighborsSignificance[index] and 0xff
            return if (ns < labels.size) labels[ns] else 0
        }

        fun runSignificancePropagationPass() {
            var i0 = 0
            while (i0 < height) {
                for (j in 0 until width) {
                    var index = i0 * width + j
                    for (i1 in 0 until 4) {
                        val i = i0 + i1
                        if (i >= height) break
                        processingFlags[index] = processingFlags[index] and 1.inv()
                        if (coefficentsMagnitude[index] != 0 || neighborsSignificance[index] == 0) {
                            index += width
                            continue
                        }
                        if (decoder.readBit(contexts, label(index)) != 0) {
                            coefficentsSign[index] = decodeSignBit(i, j, index)
                            coefficentsMagnitude[index] = 1
                            setNeighborsSignificance(i, j, index)
                            processingFlags[index] = processingFlags[index] or 2
                        }
                        bitsDecoded[index]++
                        processingFlags[index] = processingFlags[index] or 1
                        index += width
                    }
                }
                i0 += 4
            }
        }

        private fun decodeSignBit(row: Int, column: Int, index: Int): Int {
            var contribution: Int
            var significance1 = column > 0 && coefficentsMagnitude[index - 1] != 0
            if (column + 1 < width && coefficentsMagnitude[index + 1] != 0) {
                val sign1 = coefficentsSign[index + 1]
                contribution = if (significance1) 1 - sign1 - coefficentsSign[index - 1] else 1 - sign1 - sign1
            } else if (significance1) {
                val sign0 = coefficentsSign[index - 1]
                contribution = 1 - sign0 - sign0
            } else contribution = 0
            val horizontal = 3 * contribution
            significance1 = row > 0 && coefficentsMagnitude[index - width] != 0
            contribution = if (row + 1 < height && coefficentsMagnitude[index + width] != 0) {
                val sign1 = coefficentsSign[index + width]
                if (significance1) 1 - sign1 - coefficentsSign[index - width] + horizontal else 1 - sign1 - sign1 + horizontal
            } else if (significance1) {
                val sign0 = coefficentsSign[index - width]
                1 - sign0 - sign0 + horizontal
            } else horizontal
            return if (contribution >= 0) {
                decoder.readBit(contexts, 9 + contribution)
            } else {
                decoder.readBit(contexts, 9 - contribution) xor 1
            }
        }

        fun runMagnitudeRefinementPass() {
            val length = width * height
            val width4 = width * 4
            var index0 = 0
            while (index0 < length) {
                val indexNext = min(length, index0 + width4)
                for (j in 0 until width) {
                    var index = index0 + j
                    while (index < indexNext) {
                        if (coefficentsMagnitude[index] == 0 || processingFlags[index] and 1 != 0) {
                            index += width
                            continue
                        }
                        var contextLabel = 16
                        if (processingFlags[index] and 2 != 0) {
                            processingFlags[index] = processingFlags[index] xor 2
                            val significance = neighborsSignificance[index] and 127
                            contextLabel = if (significance == 0) 15 else 14
                        }
                        val bit = decoder.readBit(contexts, contextLabel)
                        coefficentsMagnitude[index] = (coefficentsMagnitude[index] shl 1) or bit
                        bitsDecoded[index]++
                        processingFlags[index] = processingFlags[index] or 1
                        index += width
                    }
                }
                index0 = indexNext
            }
        }

        fun runCleanupPass() {
            val oneRowDown = width
            val twoRowsDown = width * 2
            val threeRowsDown = width * 3
            var i0 = 0
            while (i0 < height) {
                val iNext = min(i0 + 4, height)
                val indexBase = i0 * width
                val checkAllEmpty = i0 + 3 < height
                for (j in 0 until width) {
                    val index0 = indexBase + j
                    val allEmpty = checkAllEmpty &&
                        processingFlags[index0] == 0 && processingFlags[index0 + oneRowDown] == 0 &&
                        processingFlags[index0 + twoRowsDown] == 0 && processingFlags[index0 + threeRowsDown] == 0 &&
                        neighborsSignificance[index0] == 0 && neighborsSignificance[index0 + oneRowDown] == 0 &&
                        neighborsSignificance[index0 + twoRowsDown] == 0 && neighborsSignificance[index0 + threeRowsDown] == 0
                    var i1 = 0
                    var index = index0
                    var i = i0
                    if (allEmpty) {
                        val has = decoder.readBit(contexts, RUNLENGTH_CONTEXT)
                        if (has == 0) {
                            bitsDecoded[index0]++
                            bitsDecoded[index0 + oneRowDown]++
                            bitsDecoded[index0 + twoRowsDown]++
                            bitsDecoded[index0 + threeRowsDown]++
                            continue
                        }
                        i1 = (decoder.readBit(contexts, UNIFORM_CONTEXT) shl 1) or decoder.readBit(contexts, UNIFORM_CONTEXT)
                        if (i1 != 0) {
                            i = i0 + i1
                            index += i1 * width
                        }
                        coefficentsSign[index] = decodeSignBit(i, j, index)
                        coefficentsMagnitude[index] = 1
                        setNeighborsSignificance(i, j, index)
                        processingFlags[index] = processingFlags[index] or 2
                        index = index0
                        for (i2 in i0..i) {
                            bitsDecoded[index]++
                            index += width
                        }
                        i1++
                    }
                    i = i0 + i1
                    while (i < iNext) {
                        if (coefficentsMagnitude[index] != 0 || processingFlags[index] and 1 != 0) {
                            i++
                            index += width
                            continue
                        }
                        if (decoder.readBit(contexts, label(index)) == 1) {
                            coefficentsSign[index] = decodeSignBit(i, j, index)
                            coefficentsMagnitude[index] = 1
                            setNeighborsSignificance(i, j, index)
                            processingFlags[index] = processingFlags[index] or 2
                        }
                        bitsDecoded[index]++
                        i++
                        index += width
                    }
                }
                i0 = iNext
            }
        }

        fun checkSegmentationSymbol() {
            val symbol = (decoder.readBit(contexts, UNIFORM_CONTEXT) shl 3) or
                (decoder.readBit(contexts, UNIFORM_CONTEXT) shl 2) or
                (decoder.readBit(contexts, UNIFORM_CONTEXT) shl 1) or
                decoder.readBit(contexts, UNIFORM_CONTEXT)
            if (symbol != 0xa) throw Jpeg2000Exception("Invalid segmentation symbol")
        }

        companion object {
            const val UNIFORM_CONTEXT = 17
            const val RUNLENGTH_CONTEXT = 18
            // Tabela D-1: indeks 0dddvvhh
            val LL_LH_LABELS = intArrayOf(
                0, 5, 8, 0, 3, 7, 8, 0, 4, 7, 8, 0, 0, 0, 0, 0, 1, 6, 8, 0, 3, 7, 8, 0, 4,
                7, 8, 0, 0, 0, 0, 0, 2, 6, 8, 0, 3, 7, 8, 0, 4, 7, 8, 0, 0, 0, 0, 0, 2, 6,
                8, 0, 3, 7, 8, 0, 4, 7, 8, 0, 0, 0, 0, 0, 2, 6, 8, 0, 3, 7, 8, 0, 4, 7, 8
            )
            val HL_LABELS = intArrayOf(
                0, 3, 4, 0, 5, 7, 7, 0, 8, 8, 8, 0, 0, 0, 0, 0, 1, 3, 4, 0, 6, 7, 7, 0, 8,
                8, 8, 0, 0, 0, 0, 0, 2, 3, 4, 0, 6, 7, 7, 0, 8, 8, 8, 0, 0, 0, 0, 0, 2, 3,
                4, 0, 6, 7, 7, 0, 8, 8, 8, 0, 0, 0, 0, 0, 2, 3, 4, 0, 6, 7, 7, 0, 8, 8, 8
            )
            val HH_LABELS = intArrayOf(
                0, 1, 2, 0, 1, 2, 2, 0, 2, 2, 2, 0, 0, 0, 0, 0, 3, 4, 5, 0, 4, 5, 5, 0, 5,
                5, 5, 0, 0, 0, 0, 0, 6, 7, 7, 0, 7, 7, 7, 0, 7, 7, 7, 0, 0, 0, 0, 0, 8, 8,
                8, 0, 8, 8, 8, 0, 8, 8, 8, 0, 0, 0, 0, 0, 8, 8, 8, 0, 8, 8, 8, 0, 8, 8, 8
            )
        }
    }

    // ------------------------------------------------------------------ dekoder arytmetyczny MQ (C.3)

    private class ArithmeticDecoder(private val data: IntArray, start: Int, private val dataEnd: Int) {
        private var bp = start
        private var chigh: Int
        private var clow = 0
        private var ct = 0
        private var a: Int

        init {
            chigh = byteAt(start)
            byteIn()
            chigh = ((chigh shl 7) and 0xffff) or ((clow shr 9) and 0x7f)
            clow = (clow shl 7) and 0xffff
            ct -= 7
            a = 0x8000
        }

        private fun byteAt(i: Int) = if (i < data.size) data[i] else 0xff

        private fun byteIn() {
            var p = bp
            if (byteAt(p) == 0xff) {
                if (byteAt(p + 1) > 0x8f) {
                    clow += 0xff00
                    ct = 8
                } else {
                    p++
                    clow += byteAt(p) shl 9
                    ct = 7
                    bp = p
                }
            } else {
                p++
                clow += if (p < dataEnd) byteAt(p) shl 8 else 0xff00
                ct = 8
                bp = p
            }
            if (clow > 0xffff) {
                chigh += clow shr 16
                clow = clow and 0xffff
            }
        }

        fun readBit(contexts: IntArray, pos: Int): Int {
            var cxIndex = contexts[pos] shr 1
            var cxMps = contexts[pos] and 1
            val qe = QE[cxIndex]
            val qeIcx = qe[0]
            val d: Int
            var aa = a - qeIcx
            if (chigh < qeIcx) {
                if (aa < qeIcx) {
                    aa = qeIcx
                    d = cxMps
                    cxIndex = qe[1]
                } else {
                    aa = qeIcx
                    d = 1 xor cxMps
                    if (qe[3] == 1) cxMps = d
                    cxIndex = qe[2]
                }
            } else {
                chigh -= qeIcx
                if (aa and 0x8000 != 0) {
                    a = aa
                    return cxMps
                }
                if (aa < qeIcx) {
                    d = 1 xor cxMps
                    if (qe[3] == 1) cxMps = d
                    cxIndex = qe[2]
                } else {
                    d = cxMps
                    cxIndex = qe[1]
                }
            }
            do {
                if (ct == 0) byteIn()
                aa = aa shl 1
                chigh = ((chigh shl 1) and 0xffff) or ((clow shr 15) and 1)
                clow = (clow shl 1) and 0xffff
                ct--
            } while (aa and 0x8000 == 0)
            a = aa
            contexts[pos] = (cxIndex shl 1) or cxMps
            return d
        }

        companion object {
            // Tabela C-2: {Qe, NMPS, NLPS, SWITCH}
            val QE = arrayOf(
                intArrayOf(0x5601, 1, 1, 1), intArrayOf(0x3401, 2, 6, 0), intArrayOf(0x1801, 3, 9, 0),
                intArrayOf(0x0ac1, 4, 12, 0), intArrayOf(0x0521, 5, 29, 0), intArrayOf(0x0221, 38, 33, 0),
                intArrayOf(0x5601, 7, 6, 1), intArrayOf(0x5401, 8, 14, 0), intArrayOf(0x4801, 9, 14, 0),
                intArrayOf(0x3801, 10, 14, 0), intArrayOf(0x3001, 11, 17, 0), intArrayOf(0x2401, 12, 18, 0),
                intArrayOf(0x1c01, 13, 20, 0), intArrayOf(0x1601, 29, 21, 0), intArrayOf(0x5601, 15, 14, 1),
                intArrayOf(0x5401, 16, 14, 0), intArrayOf(0x5101, 17, 15, 0), intArrayOf(0x4801, 18, 16, 0),
                intArrayOf(0x3801, 19, 17, 0), intArrayOf(0x3401, 20, 18, 0), intArrayOf(0x3001, 21, 19, 0),
                intArrayOf(0x2801, 22, 19, 0), intArrayOf(0x2401, 23, 20, 0), intArrayOf(0x2201, 24, 21, 0),
                intArrayOf(0x1c01, 25, 22, 0), intArrayOf(0x1801, 26, 23, 0), intArrayOf(0x1601, 27, 24, 0),
                intArrayOf(0x1401, 28, 25, 0), intArrayOf(0x1201, 29, 26, 0), intArrayOf(0x1101, 30, 27, 0),
                intArrayOf(0x0ac1, 31, 28, 0), intArrayOf(0x09c1, 32, 29, 0), intArrayOf(0x08a1, 33, 30, 0),
                intArrayOf(0x0521, 34, 31, 0), intArrayOf(0x0441, 35, 32, 0), intArrayOf(0x02a1, 36, 33, 0),
                intArrayOf(0x0221, 37, 34, 0), intArrayOf(0x0141, 38, 35, 0), intArrayOf(0x0111, 39, 36, 0),
                intArrayOf(0x0085, 40, 37, 0), intArrayOf(0x0049, 41, 38, 0), intArrayOf(0x0025, 42, 39, 0),
                intArrayOf(0x0015, 43, 40, 0), intArrayOf(0x0009, 44, 41, 0), intArrayOf(0x0005, 45, 42, 0),
                intArrayOf(0x0001, 45, 43, 0), intArrayOf(0x5601, 46, 46, 0)
            )
        }
    }
}
