package com.ayra.ai.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ayra.ai.app.ai.Model3dMeshCatalog
import com.ayra.ai.app.ui.theme.GlassSurfaceDark
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.ui.theme.NeonGreen
import com.ayra.ai.app.ui.theme.NeonPurple
import com.ayra.ai.app.ui.theme.NeonRose
import com.ayra.ai.app.ui.theme.TextPrimary
import com.ayra.ai.app.ui.theme.TextSecondary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

enum class Model3dKind(val label: String, val icon: ImageVector) {
    CYBER_CORE("Core", Icons.Default.ViewInAr),
    CAR("Car", Icons.Default.DirectionsCar),
    SPACESHIP("Ship", Icons.Default.RocketLaunch),
    ROBOT("Robot", Icons.Default.SmartToy),
    DIAMOND("Diamond", Icons.Default.Diamond),
    SPHERE("Sphere", Icons.Default.Public)
}

@Composable
fun ThreeDModelCard(
    craftPayload: String? = null,
    title: String = "3D Holographic Geometry",
    modifier: Modifier = Modifier
) {
    // Parse title & kind from payload if provided
    var parsedTitle by remember(craftPayload) {
        val t = try {
            if (!craftPayload.isNullOrBlank()) {
                val json = JSONObject(craftPayload)
                json.optString("title", title)
            } else title
        } catch (_: Exception) {
            title
        }
        mutableStateOf(t)
    }

    var selectedKind by remember(craftPayload) {
        val lower = (parsedTitle + " " + (craftPayload ?: "")).lowercase()
        val kind = when {
            lower.contains("car") || lower.contains("গাড়ি") || lower.contains("sports") -> Model3dKind.CAR
            lower.contains("ship") || lower.contains("space") || lower.contains("rocket") || lower.contains("মহাকাশ") -> Model3dKind.SPACESHIP
            lower.contains("robot") || lower.contains("রোবট") || lower.contains("droid") -> Model3dKind.ROBOT
            lower.contains("diamond") || lower.contains("crystal") || lower.contains("হীরা") -> Model3dKind.DIAMOND
            lower.contains("sphere") || lower.contains("planet") || lower.contains("ball") || lower.contains("গোলক") -> Model3dKind.SPHERE
            else -> Model3dKind.CYBER_CORE
        }
        mutableStateOf(kind)
    }

    val context = LocalContext.current
    val customMesh = remember(craftPayload) {
        runCatching {
            if (!craftPayload.isNullOrBlank()) {
                val json = JSONObject(craftPayload)
                if (json.optString("kind") == "CUSTOM_AI") {
                    Model3dMeshCatalog.parseWavefrontObj(json.optString("objContent"), "CUSTOM_AI")
                } else null
            } else null
        }.getOrNull()
    }

    var useCustomMesh by remember(craftPayload) { mutableStateOf(customMesh != null) }
    var rotX by remember { mutableFloatStateOf(20f) }
    var rotY by remember { mutableFloatStateOf(35f) }
    var userScale by remember { mutableFloatStateOf(1.0f) }
    var isAutoSpin by remember { mutableStateOf(true) }
    var useWebGlPbr by remember(craftPayload) { mutableStateOf(true) }
    var exportedObjPath by remember { mutableStateOf<String?>(null) }

    // Auto-spin transition
    val infiniteTransition = rememberInfiniteTransition(label = "3d_spin")
    val autoSpinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin_angle"
    )

    val currentYaw = if (isAutoSpin) rotY + autoSpinAngle else rotY

    data class ModelFace(val indices: List<Int>, val tint: Color)

    val activeCustomMesh = if (useCustomMesh) customMesh else null
    val activeMesh = remember(selectedKind, activeCustomMesh) {
        activeCustomMesh ?: Model3dMeshCatalog.getMesh(selectedKind.name)
    }

    val (vertices, edges, faces) = remember(activeMesh) {
        val palette = listOf(NeonCyan, NeonPurple, NeonRose)
        val coloredFaces = activeMesh.faces.mapIndexed { idx, indices ->
            ModelFace(indices, palette[idx % palette.size])
        }
        Triple(activeMesh.vertices, activeMesh.edges, coloredFaces)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(GlassSurfaceDark)
            .border(
                1.3.dp,
                Brush.linearGradient(listOf(NeonCyan, NeonPurple, NeonRose)),
                RoundedCornerShape(18.dp)
            )
            .padding(14.dp)
    ) {
        // Top Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(NeonCyan.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = selectedKind.icon,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = parsedTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    fontSize = 13.5.sp
                )
                Text(
                    text = "Interactive 3D Mesh • ${vertices.size} Vertices • ${edges.size} Edges • ${faces.size} Faces",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeonCyan,
                    fontSize = 10.5.sp
                )
            }

            // Export .OBJ + Renderer Mode + Auto-spin controls
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        try {
                            val dir = File(context.filesDir, "generated_3d").apply { mkdirs() }
                            val safeName = selectedKind.label.lowercase() + "_" + System.currentTimeMillis() + ".obj"
                            val outFile = File(dir, safeName)
                            outFile.writeText(Model3dMeshCatalog.buildWavefrontObj(activeMesh, parsedTitle))
                            exportedObjPath = outFile.name
                            Toast.makeText(context, "Exported 3D OBJ: ${outFile.name}", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .minimumInteractiveComponentSize()
                        .testTag("export_3d_obj_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Export Wavefront OBJ",
                        tint = NeonGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (useWebGlPbr) NeonPurple.copy(alpha = 0.25f) else Color(0xFF131B2E))
                        .border(1.dp, if (useWebGlPbr) NeonPurple else Color(0x3300FFFF), RoundedCornerShape(8.dp))
                        .clickable { useWebGlPbr = !useWebGlPbr }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (useWebGlPbr) "WebGL PBR" else "Wireframe",
                        color = if (useWebGlPbr) NeonPurple else TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isAutoSpin) NeonCyan.copy(alpha = 0.25f) else Color(0xFF131B2E))
                        .border(1.dp, if (isAutoSpin) NeonCyan else Color(0x3300FFFF), RoundedCornerShape(8.dp))
                        .clickable { isAutoSpin = !isAutoSpin }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Spin",
                            tint = if (isAutoSpin) NeonCyan else TextSecondary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isAutoSpin) "Auto" else "Manual",
                            color = if (isAutoSpin) NeonCyan else TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        if (exportedObjPath != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Saved Wavefront .OBJ: generated_3d/$exportedObjPath",
                color = NeonGreen,
                fontSize = 10.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3D Viewport (Local WebGL2 PBR Shader or Native Compose Wireframe)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(225.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF050811))
                .border(1.dp, Color(0x3300FFFF), RoundedCornerShape(14.dp))
                .then(
                    if (!useWebGlPbr) {
                        Modifier.pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                isAutoSpin = false
                                rotY += dragAmount.x * 0.45f
                                rotX = (rotX - dragAmount.y * 0.45f).coerceIn(-85f, 85f)
                            }
                        }
                    } else Modifier
                )
        ) {
            if (useWebGlPbr) {
                WebGl2PbrViewport(
                    kind = selectedKind,
                    customMesh = activeCustomMesh,
                    autoSpin = isAutoSpin,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val baseScale = size.minDimension * 0.26f * userScale

                // Background holographic spatial grid
                val gridSpacing = 28f
                for (x in 0..size.width.toInt() step gridSpacing.toInt()) {
                    drawLine(
                        color = Color(0x0C00FFFF),
                        start = Offset(x.toFloat(), 0f),
                        end = Offset(x.toFloat(), size.height),
                        strokeWidth = 0.7f
                    )
                }
                for (y in 0..size.height.toInt() step gridSpacing.toInt()) {
                    drawLine(
                        color = Color(0x0C00FFFF),
                        start = Offset(0f, y.toFloat()),
                        end = Offset(size.width, y.toFloat()),
                        strokeWidth = 0.7f
                    )
                }

                // Central pedestal halo
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(NeonCyan.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(cx, cy + baseScale * 0.8f),
                        radius = baseScale * 1.4f
                    ),
                    radius = baseScale * 1.4f,
                    center = Offset(cx, cy + baseScale * 0.8f)
                )

                val radX = Math.toRadians(rotX.toDouble())
                val radY = Math.toRadians(currentYaw.toDouble())

                // 3D Matrix Rotation & Perspective Projection
                val projected = vertices.map { (vx, vy, vz) ->
                    // Rotate around Y
                    val x1 = vx * cos(radY) + vz * sin(radY)
                    val z1 = -vx * sin(radY) + vz * cos(radY)
                    // Rotate around X
                    val y2 = vy * cos(radX) - z1 * sin(radX)
                    val z2 = vy * sin(radX) + z1 * cos(radX)

                    val perspectiveFactor = 3.2f / (3.2f + z2.toFloat())
                    val sx = cx + (x1 * baseScale * perspectiveFactor).toFloat()
                    val sy = cy + (y2 * baseScale * perspectiveFactor).toFloat()
                    Triple(sx, sy, z2.toFloat())
                }

                // 1. Render Solid Shaded 3D Polygonal Faces (Depth-Sorted Painter's Algorithm)
                val sortedFaces = faces.mapNotNull { face ->
                    if (face.indices.all { it < projected.size }) {
                        val avgZ = face.indices.map { projected[it].third }.average().toFloat()
                        Pair(face, avgZ)
                    } else null
                }.sortedBy { it.second }

                sortedFaces.forEach { (face, avgZ) ->
                    val path = Path()
                    var faceCenterX = 0f
                    var faceCenterY = 0f
                    face.indices.forEachIndexed { idx, vIdx ->
                        val pt = projected[vIdx]
                        faceCenterX += pt.first
                        faceCenterY += pt.second
                        if (idx == 0) path.moveTo(pt.first, pt.second) else path.lineTo(pt.first, pt.second)
                    }
                    path.close()
                    faceCenterX /= face.indices.size
                    faceCenterY /= face.indices.size

                    val depthShade = ((avgZ + 2.5f) / 5f).coerceIn(0.25f, 1f)
                    drawPath(
                        path = path,
                        brush = Brush.radialGradient(
                            colors = listOf(
                                face.tint.copy(alpha = 0.48f * depthShade),
                                Color(0xFF070B19).copy(alpha = 0.70f * depthShade)
                            ),
                            center = Offset(faceCenterX, faceCenterY),
                            radius = (baseScale * 0.95f).coerceAtLeast(10f)
                        )
                    )
                }

                // 2. Render 3D Edges with Depth Glow
                edges.forEach { (i1, i2) ->
                    if (i1 < projected.size && i2 < projected.size) {
                        val p1 = projected[i1]
                        val p2 = projected[i2]
                        val avgZ = (p1.third + p2.third) / 2f
                        val depthAlpha = ((avgZ + 2f) / 4f).coerceIn(0.25f, 1f)

                        drawLine(
                            brush = Brush.linearGradient(
                                colors = listOf(NeonCyan.copy(alpha = depthAlpha), NeonPurple.copy(alpha = depthAlpha))
                            ),
                            start = Offset(p1.first, p1.second),
                            end = Offset(p2.first, p2.second),
                            strokeWidth = 1.8f * depthAlpha,
                            cap = StrokeCap.Round
                        )
                    }
                }

                // Render 3D Vertices (Glowing Nodes)
                projected.forEach { (sx, sy, z) ->
                    val nodeAlpha = ((z + 2f) / 4f).coerceIn(0.35f, 1f)
                    drawCircle(
                        color = NeonRose.copy(alpha = nodeAlpha),
                        radius = 3.6f * nodeAlpha,
                        center = Offset(sx, sy)
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = nodeAlpha * 0.8f),
                        radius = 1.6f,
                        center = Offset(sx, sy)
                    )
                }
            }

            // HUD Overlays for Canvas mode
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Yaw: ${(currentYaw.toInt() % 360)}° • Pitch: ${rotX.toInt()}°",
                        color = NeonCyan,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = { userScale = (userScale - 0.15f).coerceIn(0.6f, 2.0f) },
                    modifier = Modifier
                        .size(48.dp)
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .testTag("3d_zoom_out_button")
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Zoom Out", tint = NeonCyan, modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = { userScale = (userScale + 0.15f).coerceIn(0.6f, 2.0f) },
                    modifier = Modifier
                        .size(48.dp)
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .testTag("3d_zoom_in_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Zoom In", tint = NeonCyan, modifier = Modifier.size(18.dp))
                }
            }
            } // end else (Canvas fallback)
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Quick Model Geometry Switcher Tabs
        Text(
            text = "Switch 3D Geometry Object (মডেল রূপান্তর):",
            color = TextSecondary,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Model3dKind.entries.forEach { kind ->
                val isSelected = selectedKind == kind
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) NeonCyan.copy(alpha = 0.25f) else Color(0xFF131B2E))
                        .border(1.dp, if (isSelected) NeonCyan else Color(0x2200FFFF), RoundedCornerShape(8.dp))
                        .clickable {
                            useCustomMesh = false
                            selectedKind = kind
                            parsedTitle = "3D ${kind.label} Hologram"
                        }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = kind.label,
                        color = if (!useCustomMesh && isSelected) NeonCyan else TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = if (!useCustomMesh && isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

internal fun serializeCustomMeshForWebGl(mesh: Model3dMeshCatalog.MeshData?): String {
    if (mesh == null || mesh.vertices.isEmpty() || mesh.faces.isEmpty()) return "null"
    val verts = JSONArray()
    mesh.vertices.forEach { (x, y, z) ->
        verts.put(if (x.isFinite()) x.toDouble() else 0.0)
        verts.put(if (y.isFinite()) y.toDouble() else 0.0)
        verts.put(if (z.isFinite()) z.toDouble() else 0.0)
    }
    val tris = JSONArray()
    mesh.faces.forEach { face ->
        if (face.size >= 3) {
            for (i in 1 until face.size - 1) {
                val i0 = face[0]
                val i1 = face[i]
                val i2 = face[i + 1]
                if (i0 in mesh.vertices.indices && i1 in mesh.vertices.indices && i2 in mesh.vertices.indices) {
                    tris.put(i0)
                    tris.put(i1)
                    tris.put(i2)
                }
            }
        }
    }
    return JSONObject().apply {
        put("v", verts)
        put("t", tris)
    }.toString()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebGl2PbrViewport(
    kind: Model3dKind,
    customMesh: Model3dMeshCatalog.MeshData?,
    autoSpin: Boolean,
    modifier: Modifier = Modifier
) {
    val customJson = remember(customMesh) { serializeCustomMeshForWebGl(customMesh) }
    val html = remember(kind, customJson) { buildSelfContainedWebGlHtml(kind, customJson) }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    blockNetworkLoads = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = false
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
                }
                setBackgroundColor(0xFF050811.toInt())
                loadDataWithBaseURL("https://local.ayra.render", html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.evaluateJavascript(
                "window.setAyraScene && window.setAyraScene('${kind.name}', $customJson, $autoSpin);",
                null
            )
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        },
        modifier = modifier
    )
}

internal fun buildSelfContainedWebGlHtml(initialKind: Model3dKind, initialCustomJson: String): String = """
<!doctype html>
<html>
<head>
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline';">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
html,body,canvas{margin:0;padding:0;width:100%;height:100%;background:#050811;overflow:hidden;touch-action:none;}
#hud{position:fixed;left:8px;bottom:6px;color:#00f0ff;font:600 10px monospace;background:rgba(0,0,0,.6);padding:2px 6px;border-radius:4px;pointer-events:none;}
</style>
</head>
<body>
<canvas id="c"></canvas>
<div id="hud">WebGL PBR • Drag to Orbit</div>
<script>
const c = document.getElementById('c');
const gl = c.getContext('webgl') || c.getContext('experimental-webgl');
let kind = '${initialKind.name}', customData = $initialCustomJson, autoSpin = true, rotY = 0.5, rotX = 0.35, dragging = false, lx = 0, ly = 0;
window.setAyraScene = (k, cust, spin) => { kind = k; customData = cust || null; autoSpin = !!spin; buildMesh(k, customData); };
window.setAyraModel = (k, spin) => { kind = k; customData = null; autoSpin = !!spin; buildMesh(k, null); };
c.addEventListener('pointerdown', e => { dragging = true; lx = e.clientX; ly = e.clientY; });
window.addEventListener('pointerup', () => dragging = false);
c.addEventListener('pointermove', e => {
  if (!dragging) return;
  rotY += (e.clientX - lx) * 0.012;
  rotX = Math.max(-1.3, Math.min(1.3, rotX + (e.clientY - ly) * 0.012));
  lx = e.clientX; ly = e.clientY;
});
if (!gl) { document.getElementById('hud').textContent = 'WebGL unavailable (use Wireframe)'; }
const vsSrc = 'attribute vec3 p;attribute vec3 n;uniform mat4 mvp;uniform mat3 nm;varying vec3 vn;varying vec3 vp;void main(){vn=normalize(nm*n);vp=p;gl_Position=mvp*vec4(p,1.0);}';
const fsSrc = 'precision mediump float;varying vec3 vn;varying vec3 vp;uniform vec3 col;void main(){vec3 L=normalize(vec3(0.6,0.9,0.7));vec3 V=normalize(vec3(0.0,0.2,2.5)-vp);vec3 H=normalize(L+V);float diff=max(dot(vn,L),0.12);float spec=pow(max(dot(vn,H),0.0),32.0);float rim=pow(1.0-max(dot(vn,V),0.0),2.5);vec3 c=col*diff+vec3(0.75,0.95,1.0)*spec*0.55+vec3(0.55,0.25,1.0)*rim*0.45;gl_FragColor=vec4(c,1.0);}';
function sh(t,s){const o=gl.createShader(t);gl.shaderSource(o,s);gl.compileShader(o);return o;}
const prog=gl.createProgram();gl.attachShader(prog,sh(gl.VERTEX_SHADER,vsSrc));gl.attachShader(prog,sh(gl.FRAGMENT_SHADER,fsSrc));gl.linkProgram(prog);gl.useProgram(prog);
const pLoc=gl.getAttribLocation(prog,'p'), nLoc=gl.getAttribLocation(prog,'n'), mvpLoc=gl.getUniformLocation(prog,'mvp'), nmLoc=gl.getUniformLocation(prog,'nm'), colLoc=gl.getUniformLocation(prog,'col');
let vbo=gl.createBuffer(), nbo=gl.createBuffer(), vCount=0, curColor=[0.1,0.85,0.95];
function addTri(arr,narr,a,b,c){
  const u=[b[0]-a[0],b[1]-a[1],b[2]-a[2]], v=[c[0]-a[0],c[1]-a[1],c[2]-a[2]];
  let nx=u[1]*v[2]-u[2]*v[1], ny=u[2]*v[0]-u[0]*v[2], nz=u[0]*v[1]-u[1]*v[0];
  const l=Math.hypot(nx,ny,nz)||1; nx/=l; ny/=l; nz/=l;
  arr.push(...a,...b,...c); narr.push(nx,ny,nz,nx,ny,nz,nx,ny,nz);
}
function addBox(pos,nor,cx,cy,cz,sx,sy,sz){
  const x0=cx-sx,x1=cx+sx,y0=cy-sy,y1=cy+sy,z0=cz-sz,z1=cz+sz;
  const q=(a,b,c,d)=>{addTri(pos,nor,a,b,c);addTri(pos,nor,a,c,d);};
  q([x0,y0,z1],[x1,y0,z1],[x1,y1,z1],[x0,y1,z1]);
  q([x1,y0,z0],[x0,y0,z0],[x0,y1,z0],[x1,y1,z0]);
  q([x0,y1,z1],[x1,y1,z1],[x1,y1,z0],[x0,y1,z0]);
  q([x0,y0,z0],[x1,y0,z0],[x1,y0,z1],[x0,y0,z1]);
  q([x1,y0,z1],[x1,y0,z0],[x1,y1,z0],[x1,y1,z1]);
  q([x0,y0,z0],[x0,y0,z1],[x0,y1,z1],[x0,y1,z0]);
}
function buildMesh(k,cust){
  const pos=[], nor=[];
  if(cust && Array.isArray(cust.v) && Array.isArray(cust.t) && cust.t.length >= 3){
    curColor=[0.15,0.9,0.95];
    const v=cust.v, t=cust.t;
    for(let i=0;i+2<t.length;i+=3){
      const a=t[i]*3, b=t[i+1]*3, cc=t[i+2]*3;
      addTri(pos,nor,[v[a],v[a+1],v[a+2]],[v[b],v[b+1],v[b+2]],[v[cc],v[cc+1],v[cc+2]]);
    }
  } else if(k==='SPHERE'){
    curColor=[0.1,0.85,0.95];
    const st=16,sl=24,r=0.95;
    for(let i=0;i<st;i++){
      const p1=Math.PI*i/st,p2=Math.PI*(i+1)/st;
      for(let j=0;j<sl;j++){
        const t1=2*Math.PI*j/sl,t2=2*Math.PI*(j+1)/sl;
        const pt=(p,t)=>[r*Math.sin(p)*Math.cos(t),r*Math.cos(p),r*Math.sin(p)*Math.sin(t)];
        const a=pt(p1,t1),b=pt(p2,t1),c=pt(p2,t2),d=pt(p1,t2);
        addTri(pos,nor,a,b,c); addTri(pos,nor,a,c,d);
      }
    }
  } else if(k==='DIAMOND'){
    curColor=[0.6,0.4,1.0];
    const top=[0,1.05,0], bot=[0,-1.05,0], ring=[];
    for(let i=0;i<8;i++){const a=i*Math.PI/4; ring.push([0.85*Math.cos(a),0.18,0.85*Math.sin(a)]);}
    for(let i=0;i<8;i++){const n=(i+1)%8; addTri(pos,nor,top,ring[n],ring[i]); addTri(pos,nor,bot,ring[i],ring[n]);}
  } else if(k==='CAR'){
    curColor=[0.0,0.85,0.95];
    addBox(pos,nor,0,-0.15,0,1.05,0.22,0.52);
    addBox(pos,nor,-0.08,0.2,0,0.52,0.18,0.42);
    addBox(pos,nor,-0.92,0.15,0,0.08,0.12,0.48);
  } else if(k==='SPACESHIP'){
    curColor=[0.2,0.75,1.0];
    const nose=[0,0,1.25],l=[-1.1,-0.12,-0.8],r=[1.1,-0.12,-0.8],t=[0,0.45,-0.6],b=[0,-0.25,-0.75];
    addTri(pos,nor,nose,t,l); addTri(pos,nor,nose,r,t); addTri(pos,nor,nose,l,b); addTri(pos,nor,nose,b,r); addTri(pos,nor,l,t,r); addTri(pos,nor,l,r,b);
  } else if(k==='ROBOT'){
    curColor=[0.95,0.35,0.65];
    addBox(pos,nor,0,0.62,0,0.28,0.24,0.26);
    addBox(pos,nor,0,0.0,0,0.48,0.42,0.28);
    addBox(pos,nor,-0.65,0.05,0,0.12,0.32,0.12);
    addBox(pos,nor,0.65,0.05,0,0.12,0.32,0.12);
  } else {
    curColor=[0.1,0.9,0.85];
    addBox(pos,nor,0,0,0,0.65,0.65,0.65);
    addBox(pos,nor,0,0,0,0.88,0.24,0.24);
    addBox(pos,nor,0,0,0,0.24,0.88,0.24);
  }
  vCount=pos.length/3;
  gl.bindBuffer(gl.ARRAY_BUFFER,vbo); gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(pos),gl.STATIC_DRAW);
  gl.bindBuffer(gl.ARRAY_BUFFER,nbo); gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(nor),gl.STATIC_DRAW);
}
buildMesh(kind, customData);
function loop(){
  const dpr=window.devicePixelRatio||1, w=c.clientWidth*dpr, h=c.clientHeight*dpr;
  if(c.width!==w||c.height!==h){c.width=w;c.height=h;}
  gl.viewport(0,0,c.width,c.height);
  gl.enable(gl.DEPTH_TEST);
  gl.clearColor(0.02,0.03,0.07,1);
  gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);
  if(autoSpin && !dragging) rotY+=0.012;
  const asp=c.width/Math.max(1,c.height), f=1.8, n=0.1, fa=20;
  const cx=Math.cos(rotX), sx=Math.sin(rotX), cy=Math.cos(rotY), sy=Math.sin(rotY);
  const mv=[cy, sy*sx, sy*cx, 0,  0, cx, -sx, 0,  -sy, cy*sx, cy*cx, 0,  0, 0, -3.4, 1];
  const p=[f/asp,0,0,0, 0,f,0,0, 0,0,(fa+n)/(n-fa),-1, 0,0,(2*fa*n)/(n-fa),0];
  const mvp=new Float32Array(16);
  for(let col=0;col<4;col++)for(let row=0;row<4;row++){
    mvp[col*4+row]=p[row]*mv[col*4]+p[4+row]*mv[col*4+1]+p[8+row]*mv[col*4+2]+p[12+row]*mv[col*4+3];
  }
  const nm=new Float32Array([mv[0],mv[1],mv[2], mv[4],mv[5],mv[6], mv[8],mv[9],mv[10]]);
  gl.uniformMatrix4fv(mvpLoc,false,mvp);
  gl.uniformMatrix3fv(nmLoc,false,nm);
  gl.uniform3fv(colLoc,curColor);
  gl.bindBuffer(gl.ARRAY_BUFFER,vbo); gl.enableVertexAttribArray(pLoc); gl.vertexAttribPointer(pLoc,3,gl.FLOAT,false,0,0);
  gl.bindBuffer(gl.ARRAY_BUFFER,nbo); gl.enableVertexAttribArray(nLoc); gl.vertexAttribPointer(nLoc,3,gl.FLOAT,false,0,0);
  gl.drawArrays(gl.TRIANGLES,0,vCount);
  requestAnimationFrame(loop);
}
if(gl) requestAnimationFrame(loop);
</script>
</body>
</html>
""".trimIndent()
