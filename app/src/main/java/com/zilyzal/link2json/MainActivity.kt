package com.zilyzal.link2json

import android.content.Intent
import android.graphics.Color as AColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val sharedText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        sharedText.value = extractShared(intent)
        setContent {
            Link2JsonTheme {
                AppScreen(incoming = sharedText.value, onConsumed = { sharedText.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractShared(intent)?.let { sharedText.value = it }
    }

    private fun extractShared(i: Intent?): String? =
        if (i?.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            i.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            null
        }
}

fun formatSize(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return if (i == 0) "$bytes B" else String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}

@Composable
fun AppScreen(incoming: String?, onConsumed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val scroll = rememberScrollState()

    var name by rememberSaveable { mutableStateOf("") }
    var links by rememberSaveable { mutableStateOf("") }

    var analysis by remember { mutableStateOf<Analysis?>(null) }
    var groups by remember { mutableStateOf<List<LinkGroup>>(emptyList()) }
    var excluded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var sizing by remember { mutableStateOf(false) }

    var resultTick by remember { mutableIntStateOf(0) }
    var viewportTop by remember { mutableFloatStateOf(0f) }
    var resultTop by remember { mutableFloatStateOf(0f) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    LaunchedEffect(incoming) {
        val t = incoming
        if (!t.isNullOrBlank()) {
            val found = LinkParser.parse(t).valid
            if (found.isNotEmpty()) links = found.joinToString("\n")
            onConsumed()
        }
    }

    LaunchedEffect(resultTick) {
        if (resultTick > 0) {
            delay(260)
            val target = (scroll.value + resultTop - viewportTop - 12f).toInt().coerceAtLeast(0)
            scroll.animateScrollTo(target)
        }
    }

    val parsed = remember(links) { LinkParser.parse(links) }
    val pageCount = parsed.valid.count { !LinkParser.isDirect(it) }
    val fileCount = parsed.valid.size - pageCount

    val autoName = analysis?.let { it.title ?: groups.firstOrNull()?.title }
        ?: parsed.valid.firstOrNull { LinkParser.isDirect(it) }
            ?.let { LinkParser.deriveName(LinkParser.fileNameOf(it)) }
        ?: ""

    val active = remember(groups, excluded) { groups.filter { it.id !in excluded } }
    val doc = remember(active, name, analysis) {
        if (active.isEmpty()) null else JsonBuilder.build(name, analysis, active)
    }

    fun generate() {
        if (busy || parsed.valid.isEmpty()) return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val urls = parsed.valid
        busy = true
        scope.launch {
            val a = runCatching { Analyzer.run(urls) }.getOrNull()
            if (a == null) {
                toast("Something went wrong, try again")
            } else {
                analysis = a
                groups = a.groups
                excluded = emptySet()
                resultTick++
            }
            busy = false
        }
    }

    fun fetchSizes() {
        if (sizing) return
        val targets = active.flatMap { it.parts }.filter { it.size == null && it.mime == null }
        if (targets.isEmpty()) {
            toast("Nothing more to check")
            return
        }
        sizing = true
        scope.launch {
            val probes = runCatching { LinkChecker.check(targets.map { it.url }) }.getOrNull()
            if (probes != null) {
                val byUrl = targets.map { it.url }.zip(probes).toMap()
                groups = groups.map { g ->
                    g.copy(parts = g.parts.map { p -> byUrl[p.url]?.let { p.copy(size = it.size, mime = it.mime) } ?: p })
                }
                if (probes.all { it.size == null }) toast("These hosts don't share file sizes")
            }
            sizing = false
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val d = doc
        if (uri != null && d != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(d.text.toByteArray()) }
            }.isSuccess
            toast(if (ok) "Saved ${d.fileName}" else "Couldn't save the file")
        }
    }

    fun share() {
        val d = doc ?: return
        runCatching {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val f = File(dir, d.fileName).apply { writeText(d.text) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share JSON"))
        }.onFailure { toast("Couldn't share the file") }
    }

    AuroraBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .onGloballyPositioned { viewportTop = it.positionInRoot().y }
                .verticalScroll(scroll)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()

            GlassCard {
                GlassField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Name (optional)",
                    placeholder = if (autoName.isNotEmpty()) "Auto: $autoName" else "Auto from the page or file name",
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                GlassField(
                    value = links,
                    onValueChange = { links = it },
                    label = "Links or page URL",
                    placeholder = "Paste download links, or a post URL…",
                    minLines = 4,
                    maxLines = 10,
                    mono = true,
                    keyboardType = KeyboardType.Uri,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val status = when {
                        parsed.valid.isEmpty() -> if (parsed.invalid > 0) "No valid links" else "Waiting for links"
                        pageCount > 0 && fileCount == 0 ->
                            if (pageCount == 1) "Page link · will fetch cover and archive links"
                            else "$pageCount page links · will fetch cover and archive links"
                        pageCount > 0 -> "$pageCount page + $fileCount file links"
                        fileCount == 1 -> "Single link"
                        else -> "Multi-part · $fileCount links"
                    }
                    Text(
                        text = status,
                        color = if (parsed.valid.isNotEmpty()) Mint else TextLo,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Pill("Paste", R.drawable.ic_paste) {
                        val t = clipboard.getText()?.text.orEmpty()
                        if (t.isBlank()) {
                            toast("Clipboard is empty")
                        } else {
                            links = if (links.isBlank()) t else links.trimEnd() + "\n" + t
                        }
                    }
                    if (links.isNotEmpty()) {
                        Pill("Clear", R.drawable.ic_close) { links = "" }
                    }
                }
                if (parsed.invalid > 0 && parsed.valid.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${parsed.invalid} unrecognized item${if (parsed.invalid == 1) "" else "s"} ignored",
                        color = Amber,
                        fontSize = 12.sp,
                    )
                }
            }

            GradientButton(
                text = when {
                    busy -> "Working…"
                    pageCount > 0 -> "Fetch & generate JSON"
                    else -> "Generate JSON"
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = parsed.valid.isNotEmpty() && !busy,
                loading = busy,
                iconRes = R.drawable.ic_bolt,
                onClick = { generate() },
            )

            AnimatedVisibility(
                visible = analysis != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                val a = analysis
                Column(
                    modifier = Modifier.onGloballyPositioned { resultTop = it.positionInRoot().y },
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (a?.image != null) {
                        CoverCard(
                            image = a.image,
                            title = name.trim().ifEmpty { a.title ?: groups.firstOrNull()?.title.orEmpty() },
                            referer = a.source,
                        )
                    }
                    a?.notes?.forEach { Text(it, color = Amber, fontSize = 12.sp) }

                    if (groups.size > 1) {
                        BatchesCard(
                            groups = groups,
                            excluded = excluded,
                            onToggle = { id -> excluded = if (id in excluded) excluded - id else excluded + id },
                            onOnly = { id -> excluded = groups.map { it.id }.filter { it != id }.toSet() },
                            onAll = { excluded = emptySet() },
                        )
                    }

                    ResultCard(
                        doc = doc,
                        sizing = sizing,
                        onSave = { doc?.let { saveLauncher.launch(it.fileName) } },
                        onShare = { share() },
                        onCopy = {
                            doc?.let {
                                clipboard.setText(AnnotatedString(it.text))
                                toast("Copied")
                            }
                        },
                        onSizes = { fetchSizes() },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun AuroraBackground(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Ink, Indigo, Color(0xFF07121F))))
            .drawBehind {
                val w = size.width
                val h = size.height
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Violet.copy(alpha = 0.38f), Color.Transparent),
                        center = Offset(w * 0.1f, h * 0.08f),
                        radius = w * 0.85f,
                    ),
                    radius = w * 0.85f,
                    center = Offset(w * 0.1f, h * 0.08f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Cyan.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(w * 0.95f, h * 0.45f),
                        radius = w * 0.8f,
                    ),
                    radius = w * 0.8f,
                    center = Offset(w * 0.95f, h * 0.45f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Pink.copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(w * 0.2f, h * 0.95f),
                        radius = w * 0.8f,
                    ),
                    radius = w * 0.8f,
                    center = Offset(w * 0.2f, h * 0.95f),
                )
            },
    ) {
        content()
    }
}

@Composable
fun Header() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(Violet, Cyan))),
            contentAlignment = Alignment.Center,
        ) {
            Text("{ }", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Column {
            Text(
                text = "Link2JSON",
                style = TextStyle(
                    brush = Brush.linearGradient(listOf(Color.White, Color(0xFFB9A8FF), Cyan)),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                ),
            )
            Text("Links or a post URL in, clean JSON out", color = TextLo, fontSize = 13.sp)
        }
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.11f), Color.White.copy(alpha = 0.04f))))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.06f))), shape)
            .padding(18.dp),
        content = content,
    )
}

@Composable
fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    mono: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else maxLines,
        textStyle = TextStyle(
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            fontSize = if (mono) 13.sp else 16.sp,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextHi,
            unfocusedTextColor = TextHi,
            focusedBorderColor = Violet,
            unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
            focusedContainerColor = Color.Black.copy(alpha = 0.22f),
            unfocusedContainerColor = Color.Black.copy(alpha = 0.16f),
            cursorColor = Cyan,
            focusedLabelColor = Cyan,
            unfocusedLabelColor = TextLo,
            focusedPlaceholderColor = TextLo.copy(alpha = 0.7f),
            unfocusedPlaceholderColor = TextLo.copy(alpha = 0.7f),
        ),
    )
}

@Composable
fun Pill(text: String, iconRes: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(16.dp), tint = TextHi)
        Text(text, color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun TextChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = TextHi, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    iconRes: Int? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(56.dp)
            .alpha(if (enabled || loading) 1f else 0.45f)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(listOf(Violet, Blue, Cyan)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp, color = Color.White)
            } else if (iconRes != null) {
                Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp), tint = Color.White)
            }
            Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
fun GlassButton(
    text: String,
    iconRes: Int,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .height(50.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.09f))
            .border(1.dp, Color.White.copy(alpha = 0.2f), shape)
            .clickable(enabled = !loading, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = TextHi)
        } else {
            Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(18.dp), tint = TextHi)
        }
        Spacer(Modifier.width(7.dp))
        Text(text, color = TextHi, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun CoverCard(image: String, title: String, referer: String?) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(image)
                .apply { if (referer != null) addHeader("Referer", referer) }
                .addHeader("User-Agent", Net.UA)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.82f))),
        )
        if (title.isNotBlank()) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            )
        }
    }
}

@Composable
fun BatchesCard(
    groups: List<LinkGroup>,
    excluded: Set<String>,
    onToggle: (String) -> Unit,
    onOnly: (String) -> Unit,
    onAll: () -> Unit,
) {
    val selectedParts = groups.filter { it.id !in excluded }.sumOf { it.parts.size }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Batches", color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "$selectedParts file${if (selectedParts == 1) "" else "s"} selected · tap to include or exclude",
                    color = TextLo,
                    fontSize = 12.sp,
                )
            }
            if (excluded.isNotEmpty()) TextChip("Select all", onAll)
        }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            groups.forEach { g ->
                BatchRow(
                    group = g,
                    included = g.id !in excluded,
                    onToggle = { onToggle(g.id) },
                    onOnly = { onOnly(g.id) },
                )
            }
        }
    }
}

@Composable
fun BatchRow(group: LinkGroup, included: Boolean, onToggle: () -> Unit, onOnly: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val sizes = group.parts.mapNotNull { it.size }
    val total = if (sizes.size == group.parts.size) sizes.sum() else null
    val n = group.parts.size
    val detail = buildString {
        if (group.host.isNotEmpty()) append(group.host).append(" · ")
        append(if (n == 1) "1 file" else "$n parts")
        if (total != null) append(" · ").append(formatSize(total))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (included) 1f else 0.5f)
            .clip(shape)
            .background(if (included) Violet.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f))
            .border(1.dp, if (included) Violet.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.14f), shape)
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .then(
                    if (included) Modifier.background(Brush.linearGradient(listOf(Violet, Cyan)))
                    else Modifier.border(1.5.dp, TextLo, CircleShape)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (included) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                group.title,
                color = TextHi,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(detail, color = TextLo, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextChip("Only", onOnly)
    }
}

@Composable
fun ResultCard(
    doc: JsonDoc?,
    sizing: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onSizes: () -> Unit,
) {
    val highlighted = remember(doc?.text) { doc?.let { highlightJson(it.text) } }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Package JSON", color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (doc != null) {
                Text(
                    doc.fileName,
                    color = Cyan,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (highlighted == null) {
            Text("Select at least one batch to build the JSON.", color = Amber, fontSize = 13.sp)
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.38f)),
            ) {
                SelectionContainer {
                    Text(
                        text = highlighted,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                        color = TextHi,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        GradientButton(
            text = "Save .json file",
            modifier = Modifier.fillMaxWidth(),
            enabled = doc != null,
            iconRes = R.drawable.ic_download,
            onClick = onSave,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("Sizes", R.drawable.ic_ruler, Modifier.weight(1f), loading = sizing, onClick = onSizes)
            GlassButton("Share", R.drawable.ic_share, Modifier.weight(1f), onClick = onShare)
            GlassButton("Copy", R.drawable.ic_copy, Modifier.weight(1f), onClick = onCopy)
        }
    }
}

private val stringOrNumber = Regex(""""(?:[^"\\]|\\.)*"(\s*:)?|-?\d+(?:\.\d+)?""")

private fun highlightJson(json: String): AnnotatedString = buildAnnotatedString {
    append(json)
    for (m in stringOrNumber.findAll(json)) {
        val color = when {
            !m.value.startsWith('"') -> Amber
            m.groups[1] != null -> Cyan
            else -> Mint
        }
        addStyle(SpanStyle(color = color), m.range.first, m.range.last + 1)
    }
}
