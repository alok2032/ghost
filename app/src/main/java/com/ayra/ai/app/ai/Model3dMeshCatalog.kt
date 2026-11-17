package com.ayra.ai.app.ai

import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared 3D geometry catalog providing accurate vertex/edge/face counts
 * and genuine Wavefront .OBJ export for every Ayra 3D Holographic model kind.
 */
object Model3dMeshCatalog {

    data class MeshData(
        val kind: String,
        val vertices: List<Triple<Float, Float, Float>>,
        val edges: List<Pair<Int, Int>>,
        val faces: List<List<Int>>
    ) {
        val vertexCount: Int get() = vertices.size
        val edgeCount: Int get() = edges.size
        val faceCount: Int get() = faces.size
    }

    fun getMesh(kind: String): MeshData {
        return when (kind.uppercase(Locale.ROOT)) {
            "CAR" -> {
                val v = listOf(
                    Triple(-1.4f, 0.4f, -0.7f), Triple(1.4f, 0.4f, -0.7f),
                    Triple(1.4f, 0.4f, 0.7f), Triple(-1.4f, 0.4f, 0.7f),
                    Triple(-1.2f, -0.1f, -0.65f), Triple(1.2f, -0.1f, -0.65f),
                    Triple(1.2f, -0.1f, 0.65f), Triple(-1.2f, -0.1f, 0.65f),
                    Triple(-0.4f, -0.7f, -0.5f), Triple(0.5f, -0.7f, -0.5f),
                    Triple(0.5f, -0.7f, 0.5f), Triple(-0.4f, -0.7f, 0.5f),
                    Triple(-1.3f, -0.4f, -0.6f), Triple(-1.3f, -0.4f, 0.6f)
                )
                val e = listOf(
                    Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 0),
                    Pair(4, 5), Pair(5, 6), Pair(6, 7), Pair(7, 4),
                    Pair(0, 4), Pair(1, 5), Pair(2, 6), Pair(3, 7),
                    Pair(8, 9), Pair(9, 10), Pair(10, 11), Pair(11, 8),
                    Pair(4, 8), Pair(5, 9), Pair(6, 10), Pair(7, 11),
                    Pair(12, 13), Pair(4, 12), Pair(7, 13)
                )
                val f = listOf(
                    listOf(8, 9, 10, 11),
                    listOf(4, 5, 9, 8),
                    listOf(11, 10, 6, 7),
                    listOf(0, 1, 5, 4),
                    listOf(3, 2, 6, 7),
                    listOf(0, 4, 8, 11, 7, 3),
                    listOf(1, 5, 9, 10, 6, 2)
                )
                MeshData("CAR", v, e, f)
            }

            "SPACESHIP" -> {
                val v = listOf(
                    Triple(0f, 0f, 1.8f),
                    Triple(-1.4f, 0.2f, -1.2f),
                    Triple(1.4f, 0.2f, -1.2f),
                    Triple(0f, -0.6f, -1.0f),
                    Triple(0f, 0.3f, -1.1f),
                    Triple(-0.5f, -0.2f, 0.3f),
                    Triple(0.5f, -0.2f, 0.3f),
                    Triple(0f, -0.4f, 0.8f)
                )
                val e = listOf(
                    Pair(0, 1), Pair(0, 2), Pair(1, 2),
                    Pair(0, 3), Pair(1, 3), Pair(2, 3),
                    Pair(0, 4), Pair(1, 4), Pair(2, 4), Pair(3, 4),
                    Pair(5, 6), Pair(6, 7), Pair(7, 5),
                    Pair(0, 7), Pair(1, 5), Pair(2, 6)
                )
                val f = listOf(
                    listOf(0, 1, 3),
                    listOf(0, 2, 3),
                    listOf(0, 1, 2),
                    listOf(0, 5, 7),
                    listOf(0, 6, 7),
                    listOf(1, 4, 2)
                )
                MeshData("SPACESHIP", v, e, f)
            }

            "ROBOT" -> {
                val v = listOf(
                    Triple(-0.35f, -1.2f, -0.35f), Triple(0.35f, -1.2f, -0.35f),
                    Triple(0.35f, -0.7f, -0.35f), Triple(-0.35f, -0.7f, -0.35f),
                    Triple(-0.35f, -1.2f, 0.35f), Triple(0.35f, -1.2f, 0.35f),
                    Triple(0.35f, -0.7f, 0.35f), Triple(-0.35f, -0.7f, 0.35f),
                    Triple(-0.7f, -0.6f, -0.4f), Triple(0.7f, -0.6f, -0.4f),
                    Triple(0.6f, 0.4f, -0.35f), Triple(-0.6f, 0.4f, -0.35f),
                    Triple(-0.7f, -0.6f, 0.4f), Triple(0.7f, -0.6f, 0.4f),
                    Triple(0.6f, 0.4f, 0.35f), Triple(-0.6f, 0.4f, 0.35f),
                    Triple(-1.1f, 0.2f, 0f), Triple(1.1f, 0.2f, 0f)
                )
                val e = listOf(
                    Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 0),
                    Pair(4, 5), Pair(5, 6), Pair(6, 7), Pair(7, 4),
                    Pair(0, 4), Pair(1, 5), Pair(2, 6), Pair(3, 7),
                    Pair(8, 9), Pair(9, 10), Pair(10, 11), Pair(11, 8),
                    Pair(12, 13), Pair(13, 14), Pair(14, 15), Pair(15, 12),
                    Pair(8, 12), Pair(9, 13), Pair(10, 14), Pair(11, 15),
                    Pair(8, 16), Pair(9, 17)
                )
                val f = listOf(
                    listOf(4, 5, 6, 7),
                    listOf(0, 1, 2, 3),
                    listOf(12, 13, 14, 15),
                    listOf(8, 9, 10, 11),
                    listOf(8, 9, 13, 12),
                    listOf(11, 10, 14, 15)
                )
                MeshData("ROBOT", v, e, f)
            }

            "DIAMOND" -> {
                val v = listOf(
                    Triple(0f, -1.3f, 0f),
                    Triple(-0.7f, -0.5f, -0.7f), Triple(0.7f, -0.5f, -0.7f),
                    Triple(0.7f, -0.5f, 0.7f), Triple(-0.7f, -0.5f, 0.7f),
                    Triple(-1.1f, -0.2f, 0f), Triple(1.1f, -0.2f, 0f),
                    Triple(0f, -0.2f, -1.1f), Triple(0f, -0.2f, 1.1f),
                    Triple(0f, 1.4f, 0f)
                )
                val e = listOf(
                    Pair(0, 1), Pair(0, 2), Pair(0, 3), Pair(0, 4),
                    Pair(1, 2), Pair(2, 3), Pair(3, 4), Pair(4, 1),
                    Pair(1, 5), Pair(1, 7), Pair(2, 6), Pair(2, 7),
                    Pair(3, 6), Pair(3, 8), Pair(4, 5), Pair(4, 8),
                    Pair(5, 7), Pair(7, 6), Pair(6, 8), Pair(8, 5),
                    Pair(9, 5), Pair(9, 6), Pair(9, 7), Pair(9, 8),
                    Pair(9, 1), Pair(9, 2), Pair(9, 3), Pair(9, 4)
                )
                val f = listOf(
                    listOf(0, 1, 7),
                    listOf(0, 7, 2),
                    listOf(0, 2, 6),
                    listOf(0, 6, 3),
                    listOf(0, 3, 8),
                    listOf(0, 8, 4),
                    listOf(9, 5, 7),
                    listOf(9, 7, 6),
                    listOf(9, 6, 8),
                    listOf(9, 8, 5)
                )
                MeshData("DIAMOND", v, e, f)
            }

            "SPHERE" -> {
                val v = mutableListOf<Triple<Float, Float, Float>>()
                val e = mutableListOf<Pair<Int, Int>>()
                val f = mutableListOf<List<Int>>()
                val rings = 6
                val sectors = 8
                for (r in 0..rings) {
                    val phi = Math.PI * r / rings
                    for (s in 0 until sectors) {
                        val theta = 2 * Math.PI * s / sectors
                        val x = (sin(phi) * cos(theta)).toFloat() * 1.15f
                        val y = (cos(phi)).toFloat() * 1.15f
                        val z = (sin(phi) * sin(theta)).toFloat() * 1.15f
                        v.add(Triple(x, y, z))
                    }
                }
                for (r in 0 until rings) {
                    for (s in 0 until sectors) {
                        val current = r * sectors + s
                        val next = r * sectors + ((s + 1) % sectors)
                        val down = (r + 1) * sectors + s
                        val downNext = (r + 1) * sectors + ((s + 1) % sectors)
                        e.add(Pair(current, next))
                        e.add(Pair(current, down))
                        f.add(listOf(current, next, downNext, down))
                    }
                }
                MeshData("SPHERE", v, e, f)
            }

            else -> {
                val v = listOf(
                    Triple(-1f, -1f, -1f), Triple(1f, -1f, -1f),
                    Triple(1f, 1f, -1f), Triple(-1f, 1f, -1f),
                    Triple(-1f, -1f, 1f), Triple(1f, -1f, 1f),
                    Triple(1f, 1f, 1f), Triple(-1f, 1f, 1f),
                    Triple(-0.5f, -0.5f, -0.5f), Triple(0.5f, -0.5f, -0.5f),
                    Triple(0.5f, 0.5f, -0.5f), Triple(-0.5f, 0.5f, -0.5f),
                    Triple(-0.5f, -0.5f, 0.5f), Triple(0.5f, -0.5f, 0.5f),
                    Triple(0.5f, 0.5f, 0.5f), Triple(-0.5f, 0.5f, 0.5f),
                    Triple(0f, 0f, 1.4f), Triple(0f, 0f, -1.4f)
                )
                val e = listOf(
                    Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 0),
                    Pair(4, 5), Pair(5, 6), Pair(6, 7), Pair(7, 4),
                    Pair(0, 4), Pair(1, 5), Pair(2, 6), Pair(3, 7),
                    Pair(8, 9), Pair(9, 10), Pair(10, 11), Pair(11, 8),
                    Pair(12, 13), Pair(13, 14), Pair(14, 15), Pair(15, 12),
                    Pair(8, 12), Pair(9, 13), Pair(10, 14), Pair(11, 15),
                    Pair(16, 12), Pair(16, 13), Pair(16, 14), Pair(16, 15),
                    Pair(17, 8), Pair(17, 9), Pair(17, 10), Pair(17, 11)
                )
                val f = listOf(
                    listOf(0, 1, 2, 3),
                    listOf(4, 5, 6, 7),
                    listOf(0, 1, 5, 4),
                    listOf(2, 3, 7, 6),
                    listOf(8, 9, 10, 11),
                    listOf(12, 13, 14, 15)
                )
                MeshData("CYBER_CORE", v, e, f)
            }
        }
    }

    fun buildWavefrontObj(kind: String, title: String = kind): String {
        return buildWavefrontObj(getMesh(kind), title)
    }

    fun buildWavefrontObj(mesh: MeshData, title: String = mesh.kind): String {
        val safeObjName = title.replace(Regex("[^A-Za-z0-9_\\-]"), "_").ifBlank { mesh.kind }
        val sb = StringBuilder()
        sb.append("# Ghost AI Wavefront .OBJ Export\n")
        sb.append("# Vertices: ${mesh.vertexCount}, Faces: ${mesh.faceCount}, Edges: ${mesh.edgeCount}\n")
        sb.append("o $safeObjName\n")
        mesh.vertices.forEach { (x, y, z) ->
            sb.append(String.format(Locale.US, "v %.4f %.4f %.4f\n", x, -y, z))
        }
        mesh.faces.forEach { indices ->
            val oneBased = indices.joinToString(" ") { (it + 1).toString() }
            sb.append("f $oneBased\n")
        }
        return sb.toString()
    }

    /**
     * Parses a Wavefront .OBJ text snippet (vertices `v x y z` and faces `f i1 i2 i3 ...`)
     * into normalized MeshData centered at the origin and scaled into [-1.4, 1.4].
     * Returns null if fewer than 4 vertices or 2 faces are present.
     */
    fun parseWavefrontObj(objText: String, kind: String = "CUSTOM_AI"): MeshData? {
        val rawVerts = mutableListOf<Triple<Float, Float, Float>>()
        val faces = mutableListOf<List<Int>>()
        val edgeSet = linkedSetOf<Pair<Int, Int>>()

        objText.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("v ") -> {
                    val parts = trimmed.split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        val x = parts[1].toFloatOrNull()
                        val y = parts[2].toFloatOrNull()
                        val z = parts[3].toFloatOrNull()
                        if (x != null && y != null && z != null) {
                            rawVerts.add(Triple(x, -y, z))
                        }
                    }
                }
                trimmed.startsWith("f ") -> {
                    val parts = trimmed.split(Regex("\\s+")).drop(1)
                    val indices = parts.mapNotNull { token ->
                        val rawIdx = token.substringBefore('/').toIntOrNull() ?: return@mapNotNull null
                        val zeroBased = if (rawIdx < 0) rawVerts.size + rawIdx else rawIdx - 1
                        if (zeroBased in rawVerts.indices) zeroBased else null
                    }
                    if (indices.size >= 3) {
                        faces.add(indices)
                        for (i in indices.indices) {
                            val a = indices[i]
                            val b = indices[(i + 1) % indices.size]
                            val pair = if (a < b) Pair(a, b) else Pair(b, a)
                            edgeSet.add(pair)
                        }
                    }
                }
            }
        }

        if (rawVerts.size < 4 || faces.size < 2) return null

        val avgX = rawVerts.map { it.first }.average().toFloat()
        val avgY = rawVerts.map { it.second }.average().toFloat()
        val avgZ = rawVerts.map { it.third }.average().toFloat()
        val centered = rawVerts.map { (x, y, z) -> Triple(x - avgX, y - avgY, z - avgZ) }
        val maxAbs = centered.maxOfOrNull { (x, y, z) ->
            maxOf(kotlin.math.abs(x), kotlin.math.abs(y), kotlin.math.abs(z))
        }?.takeIf { it > 0.0001f } ?: 1f
        val scale = 1.25f / maxAbs
        val normalized = centered.map { (x, y, z) -> Triple(x * scale, y * scale, z * scale) }

        return MeshData(
            kind = kind,
            vertices = normalized,
            edges = edgeSet.toList(),
            faces = faces
        )
    }
}
