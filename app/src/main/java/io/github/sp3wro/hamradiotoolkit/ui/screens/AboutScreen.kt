package io.github.sp3wro.hamradiotoolkit.ui.screens

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val GITHUB_URL = "https://github.com/SP3WRO/Ham_Radio_Toolkit"
// HEAD – domyślna gałąź repozytorium, niezależnie od jej nazwy
private const val LICENSE_URL = "$GITHUB_URL/blob/HEAD/LICENSE"

/** Źródło danych albo projekt, z którego korzysta aplikacja. */
private data class Credit(val name: String, @StringRes val usage: Int, val url: String? = null, @StringRes val nameRes: Int = 0)

private data class CreditGroup(@StringRes val title: Int, val items: List<Credit>)

/** Biblioteki i kod innych autorów wbudowane w aplikację – każdy na własnej licencji. */
private val thirdPartyCode = listOf(
    Credit("ft8_lib – Kārlis Goba", R.string.src_ft8, "https://github.com/kgoba/ft8_lib"),
    Credit("Robot36 – Ahmet Inan", R.string.src_robot36, "https://github.com/xdsopl/robot36"),
    Credit("horusdemodlib – Project Horus (David Rowe, Brady O'Brien, Mark Jessop)",
        R.string.src_horus, "https://github.com/projecthorus/horusdemodlib"),
    Credit("pdf.js – Mozilla Foundation", R.string.src_pdfjs, "https://github.com/mozilla/pdf.js"),
    Credit("hessu/aprs-symbols – Heikki Hannikainen OH7LZB", R.string.src_aprs_symbols, "https://github.com/hessu/aprs-symbols")
)

private val dataSources = listOf(
    CreditGroup(
        R.string.src_g_propagation, listOf(
            Credit("HamQSL – Paul N0NBH", R.string.src_hamqsl, "https://www.hamqsl.com"),
            Credit("NOAA SWPC", R.string.src_noaa, "https://www.swpc.noaa.gov"),
            Credit("Tropocast", R.string.src_tropocast, "https://tropocast.eu")
        )
    ),
    CreditGroup(
        R.string.src_g_dx, listOf(
            Credit("DX Summit", R.string.src_dxsummit, "https://www.dxsummit.fi"),
            Credit("AD1C – country-files.com", R.string.src_cty, "https://www.country-files.com")
        )
    ),
    CreditGroup(
        R.string.src_g_activations, listOf(
            Credit("POTA", R.string.src_pota, "https://pota.app"),
            Credit("SOTA", R.string.src_sota, "https://www.sotadata.org.uk"),
            Credit("WWFF", R.string.src_wwff, "https://wwff.co")
        )
    ),
    CreditGroup(
        R.string.tool_aprs, listOf(
            Credit("APRS-IS (aprs2.net)", R.string.src_aprsis, "https://www.aprs2.net")
        )
    ),
    CreditGroup(
        R.string.src_g_repeaters, listOf(
            Credit("przemienniki.eu", R.string.src_rep_pl_eu, "https://przemienniki.eu"),
            Credit("przemienniki.net", R.string.src_rep_pl_eu, "https://przemienniki.net"),
            Credit("HearHam.com", R.string.src_rep_world, "https://hearham.com"),
            Credit("repeatermap.de", R.string.src_rep_world, "https://www.repeatermap.de"),
            Credit("RadioID.net", R.string.src_dmr_details, "https://radioid.net")
        )
    ),
    CreditGroup(
        R.string.src_g_lookup, listOf(
            Credit("UKE", R.string.src_uke, "https://amator.uke.gov.pl", R.string.src_uke_name),
            Credit("HamQTH", R.string.src_callbook, "https://www.hamqth.com"),
            Credit("HamDB.org", R.string.src_hamdb, "https://hamdb.org"),
            Credit("QRZCQ", R.string.src_callbook, "https://www.qrzcq.com"),
            Credit("RadioID.net", R.string.src_dmr_ids, "https://radioid.net"),
            Credit("PSK Reporter", R.string.src_pskreporter, "https://pskreporter.info")
        )
    ),
    CreditGroup(
        R.string.src_g_satellites, listOf(
            Credit("AMSAT", R.string.src_tle, "https://www.amsat.org"),
            Credit("CelesTrak", R.string.src_tle_backup, "https://celestrak.org"),
            Credit("SatNOGS DB", R.string.src_satnogs, "https://db.satnogs.org"),
            Credit("amateur-satellite-database (palewire)", R.string.src_satdb,
                "https://github.com/palewire/amateur-satellite-database")
        )
    ),
    CreditGroup(
        R.string.src_g_contests, listOf(
            Credit("SP9CXN", R.string.src_sp9cxn, "https://sp9cxn.pzk.pl", R.string.src_sp9cxn_name),
            Credit("PK-UKF / SP1CNV", R.string.src_pkukf, "https://pk-ukf.pl"),
            Credit("QRZ.RU", R.string.src_qrzru, "https://www.qrz.ru/contest/"),
            Credit("radiodyplom.pl – SP8TZ", R.string.src_radiodyplom, "https://radiodyplom.pl")
        )
    ),
    CreditGroup(
        R.string.src_g_log, listOf(
            Credit("QRZ.com Logbook", R.string.src_upload, "https://logbook.qrz.com"),
            Credit("eQSL.cc", R.string.src_upload, "https://www.eqsl.cc")
        )
    ),
    CreditGroup(
        R.string.src_g_bandplans, listOf(
            Credit("IARU", R.string.src_iaru, "https://www.iaru.org", R.string.src_iaru_name),
            Credit("CEPT/ECO", R.string.src_cept, "https://docdb.cept.org", R.string.src_cept_name),
            Credit("NCDXF / IARU", R.string.src_ncdxf, "https://www.ncdxf.org/beacon/")
        )
    ),
    CreditGroup(
        R.string.src_g_algorithms, listOf(
            Credit("Olgierd SQ3SWF – Coax Calculator", R.string.src_coax),
            Credit("L.B. Cebik W4RNL", R.string.src_moxon),
            Credit("ETSI ES 201 980 (DRM) + HamDRM – Francesco Lanza HB9TLK", R.string.src_drm)
        )
    )
)

private val MIT_LICENSE_TEXT = """
MIT License

Copyright (c) 2026 Mateusz SP3WRO

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
""".trim()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onSettingsClick: () -> Unit = {}
) {
    val context = LocalContext.current
    // Wersja z build.gradle.kts (versionName) – jedno miejsce do zmiany przy wydaniu
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    var authorClicks by remember { mutableIntStateOf(0) }
    var showEasterEgg by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    val open = { url: String -> openUrl(context, url) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.tool_about)) },
                    actions = {
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Ham Radio Toolkit",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.about_version, versionName),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                FilledTonalButton(onClick = { open(GITHUB_URL) }) {
                    Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.about_github))
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Karta autora z ukrytym licznikiem Easter Egg
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            authorClicks++
                            if (authorClicks >= 5) {
                                showEasterEgg = true
                            }
                        },
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        CardTitle(stringResource(R.string.about_author))
                        Text(stringResource(R.string.about_author_text), style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.about_author_joke),
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        CardTitle(stringResource(R.string.about_license_title))
                        Text(
                            stringResource(R.string.about_license_text),
                                
                                
                                
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.about_as_is),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row {
                            TextButton(onClick = { showLicense = true }) { Text(stringResource(R.string.about_full_license)) }
                            LinkButton(stringResource(R.string.about_repository)) { open(GITHUB_URL) }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(stringResource(R.string.about_third_party), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.about_third_party_note),
                                
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        thirdPartyCode.forEach { CreditRow(it, open) }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { showSources = !showSources }
                        ) {
                            Text(
                                stringResource(R.string.about_sources),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                if (showSources) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (showSources) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.about_sources_note, dataSources.sumOf { it.items.size }, dataSources.size),
                                
                                
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (showSources) {
                            dataSources.forEach { group ->
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    stringResource(group.title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                group.items.forEach { CreditRow(it, open) }
                            }
                        } else {
                            TextButton(onClick = { showSources = true }) { Text(stringResource(R.string.about_show_sources)) }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = stringResource(R.string.about_thanks),
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        if (showLicense) {
            AlertDialog(
                onDismissRequest = { showLicense = false },
                title = { Text(stringResource(R.string.about_mit_title)) },
                text = {
                    Text(
                        MIT_LICENSE_TEXT,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                },
                confirmButton = { TextButton(onClick = { showLicense = false }) { Text(stringResource(R.string.common_close)) } },
                dismissButton = { TextButton(onClick = { open(LICENSE_URL) }) { Text(stringResource(R.string.about_on_github)) } }
            )
        }

        if (showEasterEgg) {
            KukiEasterEggScreen(onDismiss = {
                showEasterEgg = false
                authorClicks = 0
            })
        }
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}

@Composable
private fun CreditRow(credit: Credit, open: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (credit.url != null) Modifier.clickable { open(credit.url) } else Modifier)
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(if (credit.nameRes != 0) stringResource(credit.nameRes) else credit.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(stringResource(credit.usage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (credit.url != null) {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = stringResource(R.string.common_open_page),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
