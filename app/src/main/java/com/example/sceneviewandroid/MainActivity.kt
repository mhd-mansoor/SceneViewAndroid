package com.example.sceneviewandroid

import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Color
import io.github.sceneview.math.Rotation
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var arSceneView: SceneView
    private var modelNode: ModelNode? = null

    // drag + zoom state
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var rotationY = 0f
    private var lastDistance = 0f
    private var scaleFactor = 1.0f

    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        arSceneView = findViewById(R.id.sceneView)
        val button: Button = findViewById(R.id.btnBallTrack)

        gestureDetector = GestureDetector(this, GestureListener())
        loadModel()

        button.setOnClickListener {
            Toast.makeText(this, "Button Clicked", Toast.LENGTH_SHORT).show()
            // draw the single-tube curved track (safe, with fallback)
            drawBallTrackFromParameterss(arSceneView)
        }

        setupTouchControls()
    }

    private fun loadModel() {
        val modelLoader = arSceneView.modelLoader
        val modelInstance: ModelInstance? = try {
            modelLoader.createModelInstance(assetFileLocation = "models/stadium.glb")
        } catch (e: Exception) {
            Log.e("MainActivity", "Model load failed: ${e.message}", e)
            null
        }

        modelNode = if (modelInstance != null) {
            ModelNode(modelInstance = modelInstance).apply {
                // prefer Float3 position/scale rather than Position/Scale types
                position = Float3(0f, -0.5f, -3.7f)
                scale = Float3(0.3f, 0.3f, 0.3f)
                rotation = Rotation(0f, 0f, 0f)
            }
        } else {
            null
        }

        modelNode?.let { arSceneView.addChildNode(it) }
    }

    /**
     * Build and add a single continuous tubular mesh following a bowling-like path.
     * This function is defensive: if mesh build fails for any reason it falls back to a
     * simple CylinderNode to avoid Filament AABB crashes.
     */
    private fun drawBallTrackFromParameterss(sceneView: SceneView) {
        val engine = sceneView.engine
        val material = sceneView.materialLoader.createColorInstance(Color(1f, 0f, 0f, 1f))

        // BallTrack parameters (from your data)
        val pX = floatArrayOf(6.8128065E-5f, 5.674988E-4f, -0.87317497f)
        val pY = floatArrayOf(-0.0014491995f, 0.14178087f, -0.964562f)
        val pZ = floatArrayOf(2.5E-4f, -0.54509115f, 37.990707f)
        val minT = 0.68f
        val maxT = 92.24f

        val path = mutableListOf<Float3>()
        val step = 0.4f

        var t = minT
        while (t <= maxT) {
            val x = pX[0] * t * t + pX[1] * t + pX[2]
            val y = pY[0] * t * t + pY[1] * t + pY[2]
            val z = pZ[0] * t * t + pZ[1] * t + pZ[2]

            // Adjust to SceneView coordinate space
            // These numbers are tuned for your stadium.glb position and scale
            val scale = 0.32f      // overall scene scaling
            val heightBoost = 0.50f // start high
            val offset = Float3(0.1f, -0.5f, -4.7f) // shift to align with stumps

            // Flip Z to face forward; amplify curve with scale factors
            val pos = Float3(
                x * scale + offset.x,
                y * scale + heightBoost + offset.y,
                -z * (scale * 0.3f) + offset.z
            )
            path += pos
            t += step
        }

        if (path.size < 2) {
            Log.w("BallTrack", "No path points generated")
            return
        }

        val (vertexBuffer, indexBuffer, indexCount) = buildTubeMesh(engine, path, 0.03f, 24)

        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .geometry(
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer,
                indexBuffer,
                0,
                indexCount
            )
            .material(0, material)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, entity)

        val node = io.github.sceneview.node.Node(engine, entity)
        sceneView.addChildNode(node)

        Log.d("BallTrack", "✅ Drawn curved ball track (${path.size} pts)")
    }


    // ---------------------- helpers ----------------------

    private fun generateBowlingPath(): List<Float3> {
        val pts = mutableListOf<Float3>()
        val segments = 120
        for (i in 0..segments) {
            val t = i / segments.toFloat()
            val z = 2f - 6f * t            // forward (bowler→batsman)
            val x = 0.12f * sin(t * PI).toFloat() // small swing
            val y = if (t < 0.72f)
                1.6f - 1.9f * t.pow(2.2f)     // descending arc
            else
                0.05f + 0.12f * (1f - t).pow(2f) // after bounce
            pts += Float3(x, y, z)
        }
        return pts
    }


    private fun drawBallTrackFromParameters(sceneView: SceneView) {
        val engine = sceneView.engine
        val materialLoader = sceneView.materialLoader
        val material = materialLoader.createColorInstance(Color(1f, 0f, 0f, 1f))

        // Parameters from your example
        val parameterX = floatArrayOf(8.8128065E-5f, 5.674988E-4f, -0.87317497f)
        val parameterY = floatArrayOf(-0.0014491995f, 0.14178087f, -0.964562f)
        val parameterZ = floatArrayOf(2.5E-4f, -0.54509115f, 37.990707f)
        val minFrameIdx = 54.68f
        val maxFrameIdx = 92.24f

        val pathPoints = mutableListOf<Float3>()
        val step = 0.5f
        var t = minFrameIdx

        while (t <= maxFrameIdx) {
            val x = parameterX[0] * t * t + parameterX[1] * t + parameterX[2]
            val y = parameterY[0] * t * t + parameterY[1] * t + parameterY[2]
            val z = parameterZ[0] * t * t + parameterZ[1] * t + parameterZ[2]

            // Convert to your stadium’s scale (around Z = -3.7)
            // 🔥 Normalize & shift: reduces huge Z range into small 5-unit playable range
            val scale = 0.15f
            val offset = Float3(0f, -0.3f, -3.7f)

            val pos = Float3(
                x * scale + offset.x,
                y * scale + offset.y,
                -z * scale + offset.z
            )

            pathPoints += pos
            t += step
        }

        if (pathPoints.size < 2) {
            Log.w("BallTrack", "No path points generated!")
            return
        }

        val (vertexBuffer, indexBuffer, indexCount) = buildTubeMesh(engine, pathPoints, 0.05f, 24)

        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .geometry(
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer,
                indexBuffer,
                0,
                indexCount
            )
            .material(0, material)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, entity)

        val node = io.github.sceneview.node.Node(engine, entity)
        sceneView.addChildNode(node)

        Log.d("BallTrack", "✅ Ball track drawn: ${pathPoints.size} points")
    }


    private fun buildTubeMesh(
        engine: Engine,
        path: List<Float3>,
        radius: Float,
        radialSegments: Int
    ): Triple<VertexBuffer, IndexBuffer, Int> {

        val verts = mutableListOf<Float3>()
        val indices = mutableListOf<Short>()

        val circle = List(radialSegments) { i ->
            val angle = 2 * Math.PI * i / radialSegments
            Float3(cos(angle).toFloat() * radius, sin(angle).toFloat() * radius, 0f)
        }

        for (p in path) for (c in circle)
            verts += Float3(p.x + c.x, p.y + c.y, p.z + c.z)

        for (i in 0 until path.size - 1) {
            val row = i * radialSegments
            val next = (i + 1) * radialSegments
            for (j in 0 until radialSegments) {
                val a = (row + j).toShort()
                val b = (row + (j + 1) % radialSegments).toShort()
                val c = (next + j).toShort()
                val d = (next + (j + 1) % radialSegments).toShort()
                indices += listOf(a, c, b, b, c, d)
            }
        }

        val vertexCount = verts.size
        val vertexBuffer = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(vertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .build(engine)

        val fb = FloatArray(vertexCount * 3)
        for (i in verts.indices) {
            fb[i * 3] = verts[i].x
            fb[i * 3 + 1] = verts[i].y
            fb[i * 3 + 2] = verts[i].z
        }
        vertexBuffer.setBufferAt(engine, 0, FloatBuffer.wrap(fb))

        val indexBuffer = IndexBuffer.Builder()
            .indexCount(indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        indexBuffer.setBuffer(engine, ShortBuffer.wrap(indices.toShortArray()))

        return Triple(vertexBuffer, indexBuffer, indices.size)
    }



    /**
     * Build raw vertex and index arrays for a tube sweeping a circle along `path`.
     * Returns Pair(verticesFloatArray, indicesShortArray)
     */
    private fun buildTubeVertexIndexLists(
        path: List<Float3>,
        radius: Float,
        radialSegments: Int,
    ): Pair<FloatArray, ShortArray> {
        if (path.size < 2) return Pair(FloatArray(0), ShortArray(0))

        val verts = ArrayList<Float>(path.size * radialSegments * 3)
        val indices = ArrayList<Short>((path.size - 1) * radialSegments * 6)

        // create circle basis in XY plane and then translate to each path point (simple approach).
        // For a visually ok result we ignore twisting; for production you should compute Frenet frames.
        for (p in path) {
            for (j in 0 until radialSegments) {
                val angle = 2.0 * PI * j / radialSegments
                val cx = (cos(angle) * radius).toFloat()
                val cy = (sin(angle) * radius).toFloat()
                verts.add(p.x + cx)
                verts.add(p.y + cy)
                verts.add(p.z)
            }
        }

        // indices (two triangles per quad)
        for (i in 0 until path.size - 1) {
            val row = i * radialSegments
            val next = (i + 1) * radialSegments
            for (j in 0 until radialSegments) {
                val a = (row + j).toShort()
                val b = (row + (j + 1) % radialSegments).toShort()
                val c = (next + j).toShort()
                val d = (next + (j + 1) % radialSegments).toShort()
                // triangle a,c,b and b,c,d
                indices.add(a); indices.add(c); indices.add(b)
                indices.add(b); indices.add(c); indices.add(d)
            }
        }

        // convert to primitive arrays
        val vArr = FloatArray(verts.size)
        for (i in verts.indices) vArr[i] = verts[i]
        val iArr = ShortArray(indices.size)
        for (i in indices.indices) iArr[i] = indices[i]

        return Pair(vArr, iArr)
    }

    /**
     * Fallback — draw many overlapping cylinders (never crashes) to approximate tube.
     */
    private fun fallbackDrawSegments(
        sceneView: SceneView,
        path: List<Float3>,
        material: MaterialInstance,
        engine: Engine,
        radius: Float,
    ) {
        var last = path.first()
        for (i in 1 until path.size) {
            val current = path[i]
            drawCylinderSegment(sceneView, last, current, material, engine, radius, overlap = 0.9f)
            last = current
        }
    }

    private fun drawCylinderSegment(
        sceneView: SceneView,
        start: Float3,
        end: Float3,
        material: MaterialInstance,
        engine: Engine,
        radius: Float,
        overlap: Float,
    ) {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val dz = end.z - start.z
        val length = sqrt(dx * dx + dy * dy + dz * dz) * overlap
        val mid = Float3((start.x + end.x) / 2f, (start.y + end.y) / 2f, (start.z + end.z) / 2f)
        val cyl = CylinderNode(
            engine = engine,
            radius = radius,
            height = length,
            materialInstance = material
        )
        cyl.position = mid
        cyl.rotation = Rotation(x = 90f, y = 0f, z = 0f)
        sceneView.addChildNode(cyl)
    }

    // ---------------- touch controls (drag = translate) ----------------

    private fun setupTouchControls() {
        arSceneView.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            when (event.pointerCount) {
                1 -> handleRotate(event)
                2 -> handlePinchZoom(event)
            }
            if (event.action == MotionEvent.ACTION_UP) {
                v.performClick()
                lastDistance = 0f
            }
            true
        }
    }


    private fun handleRotate(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                lastTouchX = event.x
                rotationY += dx * 0.5f

                // ✅ SceneView uses 'rotation' instead of 'localRotation'
                // Rotation(y = angleInDegrees) rotates around Y-axis
                modelNode?.rotation = Rotation(y = rotationY)
            }
        }
    }

    private fun handlePinchZoom(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        val distance = sqrt(dx * dx + dy * dy)
        if (lastDistance != 0f) {
            val scaleChange = distance / lastDistance
            scaleFactor = (scaleFactor * scaleChange).coerceIn(0.25f, 3.0f)
            modelNode?.scale = Float3(scaleFactor, scaleFactor, scaleFactor)
        }
        lastDistance = distance
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetModelView()
            return true
        }
    }

    private fun resetModelView() {
        scaleFactor = 1.0f
        modelNode?.apply {
            rotation = Rotation(0f, 0f, 0f)
            scale = Float3(scaleFactor, scaleFactor, scaleFactor)
        }
    }

    override fun onResume() {
        super.onResume()
//        arSceneView.resume()
    }

    override fun onPause() {
//        arSceneView.pause()
        super.onPause()
    }

    override fun onDestroy() {
        arSceneView.destroy()
        super.onDestroy()
    }
}
