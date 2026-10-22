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
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
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

@Composable
fun AppScreen(incoming: String?, onConsumed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val scroll = rememberScrollState()

    var multi by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var links by rememberSaveable { mutableStateOf("") }
    var fetchSizes by rememberSaveable { mutableStateOf(false) }
    var sortParts by rememberSaveable { mutableStateOf(true) }
    var json by rememberSaveable { mutableStateOf("") }
    var fileName by rememberSaveable { mutableStateOf("links.json") }
    var busy by remember { mutableStateOf(false) }
    var resultTick by remember { mutableIntStateOf(0) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    LaunchedEffect(incoming) {
        val t = incoming
        if (!t.isNullOrBlank()) {
            val found = LinkParser.parse(t).valid
            if (found.isNotEmpty()) {
                links = found.joinToString("\n")
                multi = found.size > 1
            }
            onConsumed()
        }
    }

    LaunchedEffect(resultTick) {
        if (resultTick > 0) {
            delay(180)
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    val parsed = remember(links) { LinkParser.parse(links) }
    val ordered = remember(parsed, multi, sortParts) { LinkParser.arrange(parsed.valid, multi, sortParts) }
    val autoName = remember(ordered) {
        ordered.firstOrNull()?.let {
            LinkParser.deriveName(LinkParser.fileNameOf(it).ifEmpty { LinkParser.hostOf(it) })
        } ?: ""
    }

    fun generate() {
        if (busy || ordered.isEmpty()) return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val snapshot = ordered
        val nameSnapshot = name
        val wantSizes = fetchSizes
        busy = true
        scope.launch {
            val probes = if (wantSizes) LinkChecker.check(snapshot) else null
            val parts = snapshot.mapIndexed { i, u ->
                PartInfo(
                    url = u,
                    file = LinkParser.fileNameOf(u).ifEmpty { LinkParser.hostOf(u) },
                    size = probes?.get(i)?.size,
                    mime = probes?.get(i)?.mime,
                )
            }
            val doc = JsonBuilder.build(nameSnapshot, parts)
            json = doc.text
            fileName = doc.fileName
            busy = false
            resultTick++
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
            }.isSuccess
            toast(if (ok) "Saved $fileName" else "Couldn't save the file")
        }
    }

    fun share() {
        runCatching {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val f = File(dir, fileName).apply { writeText(json) }
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
                .verticalScroll(scroll)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()

            ModeSwitch(multi = multi, onChange = { multi = it })

            GlassCard {
                GlassField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Name (optional)",
                    placeholder = if (autoName.isNotEmpty()) "Auto: $autoName" else "Give it a name",
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                GlassField(
                    value = links,
                    onValueChange = { links = it },
                    label = if (multi) "Part links" else "Link",
                    placeholder = if (multi) "One link per line…" else "Paste a download link…",
                    minLines = if (multi) 5 else 2,
                    maxLines = 10,
                    mono = true,
                    keyboardType = KeyboardType.Uri,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val countText = when {
                        parsed.valid.isEmpty() && parsed.invalid == 0 -> "Waiting for links"
                        parsed.valid.isEmpty() -> "No valid links"
                        parsed.valid.size == 1 -> "1 link"
                        else -> "${parsed.valid.size} links"
                    }
                    Text(
                        text = countText,
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
                            if (LinkParser.parse(links).valid.size > 1) multi = true
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
                if (!multi && parsed.valid.size > 1) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Single link mode uses only the first link. Switch to Multi-part to keep all.",
                        color = Amber,
                        fontSize = 12.sp,
                    )
                }
            }

            GlassCard {
                OptionRow(
                    title = "Fetch file sizes",
                    subtitle = "Checks each link online for size and type",
                    checked = fetchSizes,
                    onChecked = { fetchSizes = it },
                )
                if (multi) {
                    OptionRow(
                        title = "Sort parts naturally",
                        subtitle = "part2 comes before part10",
                        checked = sortParts,
                        onChecked = { sortParts = it },
                    )
                }
            }

            GradientButton(
                text = if (busy) "Working…" else "Generate JSON",
                modifier = Modifier.fillMaxWidth(),
                enabled = ordered.isNotEmpty() && !busy,
                loading = busy,
                iconRes = R.drawable.ic_bolt,
                onClick = { generate() },
            )

            AnimatedVisibility(
                visible = json.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                ResultCard(
                    json = json,
                    fileName = fileName,
                    onSave = { saveLauncher.launch(fileName) },
                    onShare = { share() },
                    onCopy = {
                        clipboard.setText(AnnotatedString(json))
                        toast("Copied")
                    },
                )
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
            Text("Turn download links into clean JSON", color = TextLo, fontSize = 13.sp)
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
fun ModeSwitch(multi: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape),
    ) {
        val half = maxWidth / 2
        val slide by animateDpAsState(
            targetValue = if (multi) half else 0.dp,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
            label = "modeSlide",
        )
        Box(
            modifier = Modifier
                .offset(x = slide)
                .width(half)
                .fillMaxHeight()
                .padding(4.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.horizontalGradient(listOf(Violet, Blue))),
        )
        Row(modifier = Modifier.fillMaxSize()) {
            SegmentLabel("Single link", !multi, Modifier.weight(1f)) { onChange(false) }
            SegmentLabel("Multi-part", multi, Modifier.weight(1f)) { onChange(true) }
        }
    }
}

@Composable
private fun SegmentLabel(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else TextLo,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 15.sp,
        )
    }
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
fun OptionRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Switch) { onChecked(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextLo, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Violet,
                uncheckedThumbColor = TextLo,
                uncheckedTrackColor = Color.White.copy(alpha = 0.08f),
                uncheckedBorderColor = Color.White.copy(alpha = 0.22f),
            ),
        )
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
fun GlassButton(text: String, iconRes: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .height(50.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.09f))
            .border(1.dp, Color.White.copy(alpha = 0.2f), shape)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(18.dp), tint = TextHi)
        Spacer(Modifier.width(7.dp))
        Text(text, color = TextHi, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun ResultCard(json: String, fileName: String, onSave: () -> Unit, onShare: () -> Unit, onCopy: () -> Unit) {
    val highlighted = remember(json) { highlightJson(json) }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Result", color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(fileName, color = Cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(12.dp))
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
        Spacer(Modifier.height(14.dp))
        GradientButton(
            text = "Save .json file",
            modifier = Modifier.fillMaxWidth(),
            iconRes = R.drawable.ic_download,
            onClick = onSave,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("Share", R.drawable.ic_share, Modifier.weight(1f), onShare)
            GlassButton("Copy", R.drawable.ic_copy, Modifier.weight(1f), onCopy)
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
