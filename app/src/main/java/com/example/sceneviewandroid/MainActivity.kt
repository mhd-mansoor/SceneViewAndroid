package com.example.sceneviewandroid

import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.sceneviewandroid.databinding.ActivityMainBinding
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
import io.github.sceneview.math.Color
import io.github.sceneview.math.Rotation
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MANSOOR"

        // Tuning values (change to fine-tune visual match)
        private const val SCALE = 0.12f          // overall scaling of BallTrack coordinates into scene
        private const val HEIGHT_BOOST = 1.4f   // add to Y so track starts high above bowler
        private val STADIUM_OFFSET = Float3(0f, -0.4f, -3.7f) // align with stadium node position
    }

    private lateinit var sceneView: SceneView
    private var modelNode: ModelNode? = null

    private lateinit var binding: ActivityMainBinding

    // touch / orbit state
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var rotationY = 0f   // horizontal orbit (degrees)
    private var rotationX = 0f   // vertical tilt (degrees)
    private var lastDistance = 0f
    private var scaleFactor = 1.0f

    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sceneView = binding.sceneView
        val btnBallTrack= binding.btnBallTrack

        gestureDetector = GestureDetector(this, GestureListener())

        loadStadiumModel()

        btnBallTrack.setOnClickListener {
            Toast.makeText(this, "Button Clicked", Toast.LENGTH_SHORT).show()
            drawBallTrackFromParameters()
        }

        setupTouchControls()
    }

    // ---------- Model loading ----------
    private fun loadStadiumModel() {
        val modelLoader = sceneView.modelLoader
        val modelInstance: ModelInstance? = try {
            modelLoader.createModelInstance(assetFileLocation = "models/stadium.glb")
        } catch (e: Exception) {
            Log.e(TAG, "Model load failed: ${e.message}", e)
            null
        }

        modelNode = modelInstance?.let {
            ModelNode(modelInstance = it).apply {
                // model position and scale
                position = Float3(0f, -0.5f, -3.7f)
                scale = Float3(0.3f, 0.3f, 0.3f)
                rotation = Rotation(0f, 0f, 0f)
            }
        }

        modelNode?.let { sceneView.addChildNode(it) }
    }

    // ---------- Ball track: 2 arcs (auto bounce with ground detection) ----------
    private fun drawBallTrackFromParameters() {
        val engine = sceneView.engine
        val material = sceneView.materialLoader.createColorInstance(Color(1f, 0f, 0f, 1f))

        val parameterX = floatArrayOf(0.000054f, 0.000054f, -0.95f)
        val parameterY = floatArrayOf(-0.0014491995f, 0.14178087f, -0.964562f)
        val parameterZ = floatArrayOf(2.5E-4f, -0.54509115f, 37.990707f)

        val minFrameIdx = -100.0f
        val maxFrameIdx = 500.0f
        val step = 1.0f

        // Visual ground in WORLD coordinates (matches how we render with SCALE + HEIGHT_BOOST + STADIUM_OFFSET)
        val yGroundWorld = -20.0f

        // find bounceFrame using same transforms used for drawing (so bounce corresponds to visible ground)
        var bounceFrame: Float? = null
        var t = minFrameIdx
        fun worldYAt(tVal: Float): Float {
            val rawY = parameterY[0] * tVal * tVal + parameterY[1] * tVal + parameterY[2]
            return rawY * SCALE + HEIGHT_BOOST + STADIUM_OFFSET.y
        }

        var prevWorldY = worldYAt(t)
        while (t <= maxFrameIdx) {
            val worldY = worldYAt(t)
            if (prevWorldY > yGroundWorld && worldY <= yGroundWorld) {
                bounceFrame = t
                break
            }
            prevWorldY = worldY
            t += step
        }

        if (bounceFrame == null) {
            Log.e(TAG, "⚠️ No ground crossing found in world coords. Cannot draw a two-part arc. prevWorldY=$prevWorldY")
            Toast.makeText(this, "No ground crossing found.", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "✅ Bounce detected near t=$bounceFrame (worldY near ${worldYAt(bounceFrame)})")

        val preBouncePath = buildBallPath(
            parameterX, parameterY, parameterZ,
            minFrameIdx, bounceFrame, step, postBounce = false
        )

        val postBouncePath = buildBallPath(
            parameterX, parameterY, parameterZ,
            bounceFrame, maxFrameIdx, step, postBounce = true
        )

        Log.d(TAG, "Pre path size=${preBouncePath.size}, Post path size=${postBouncePath.size}")
        if (preBouncePath.isNotEmpty()) drawTube(sceneView, engine, material, preBouncePath)
        if (postBouncePath.isNotEmpty()) drawTube(sceneView, engine, material, postBouncePath)
    }

    private fun buildBallPath(
        parameterX: FloatArray,
        parameterY: FloatArray,
        parameterZ: FloatArray,
        startT: Float,
        endT: Float,
        step: Float,
        postBounce: Boolean
    ): List<Float3> {
        val path = mutableListOf<Float3>()
        var t = startT

        // keep consistent base scale; tweak post-bounce visually
        val heightBoost = if (postBounce) HEIGHT_BOOST * 0.25f else HEIGHT_BOOST
        val scaleZ = if (postBounce) SCALE * 0.7f else SCALE * 0.8f

        if (postBounce) {
            // Use the *startT* (which is the bounce frame) to compute the first point,
            // not endT (that was the bug).
            val bouncePointT = startT
            val bouncePoint = Float3(
                parameterX[0] * bouncePointT * bouncePointT + parameterX[1] * bouncePointT + parameterX[2],
                parameterY[0] * bouncePointT * bouncePointT + parameterY[1] * bouncePointT + parameterY[2],
                parameterZ[0] * bouncePointT * bouncePointT + parameterZ[1] * bouncePointT + parameterZ[2]
            )
            path.add(
                Float3(
                    bouncePoint.x * SCALE + STADIUM_OFFSET.x,
                    bouncePoint.y * SCALE + heightBoost + STADIUM_OFFSET.y,
                    -bouncePoint.z * scaleZ + STADIUM_OFFSET.z
                )
            )
            // start slightly after bounce to avoid repeating the same sample
            t += step
        }

        while (t <= endT) {
            val x = parameterX[0] * t * t + parameterX[1] * t + parameterX[2]
            val y = parameterY[0] * t * t + parameterY[1] * t + parameterY[2]
            val z = parameterZ[0] * t * t + parameterZ[1] * t + parameterZ[2]

            val p = Float3(
                x * SCALE + STADIUM_OFFSET.x,
                y * SCALE + heightBoost + STADIUM_OFFSET.y,
                -z * scaleZ + STADIUM_OFFSET.z
            )
            path.add(p)
            t += step
        }

        return path
    }



    // ---------- helper: safely draw tube geometry ----------
    private fun drawTube(sceneView: SceneView, engine: Engine, material: MaterialInstance, path: List<Float3>) {
        if (path.size < 2) return

        try {
            val (vb, ib, ic) = buildTubeMesh(engine, path, 0.05f, 24)
            val entity = EntityManager.get().create()

            RenderableManager.Builder(1)
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, ic)
                .material(0, material)
                .culling(false)
                .castShadows(false)
                .receiveShadows(false)
                .build(engine, entity)

            val node = Node(engine, entity)
            modelNode?.addChildNode(node) ?: sceneView.addChildNode(node)
        } catch (e: Exception) {
            Log.e(TAG, "Tube build failed, using fallback", e)
            fallbackDrawSegments(sceneView, path, material, engine, 0.04f)
        }
    }


    // ---------- Mesh builder (returns VertexBuffer, IndexBuffer, indexCount) ----------
    // Creates direct buffers and returns Filament buffers; throws on error
    private fun buildTubeMesh(engine: Engine, path: List<Float3>, radius: Float, radialSegments: Int):
            Triple<VertexBuffer, IndexBuffer, Int> {

        if (path.size < 2) throw IllegalArgumentException("path too short")

        val verts = ArrayList<Float>(path.size * radialSegments * 3)
        val indices = ArrayList<Short>((path.size - 1) * radialSegments * 6)

        // circle points in local XY plane
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

        for (i in 0 until path.size - 1) {
            val row = i * radialSegments
            val next = (i + 1) * radialSegments
            for (j in 0 until radialSegments) {
                val a = (row + j).toShort()
                val b = (row + (j + 1) % radialSegments).toShort()
                val c = (next + j).toShort()
                val d = (next + (j + 1) % radialSegments).toShort()
                // two triangles (a,c,b) and (b,c,d)
                indices.add(a); indices.add(c); indices.add(b)
                indices.add(b); indices.add(c); indices.add(d)
            }
        }

        val vArr = FloatArray(verts.size)
        for (i in verts.indices) vArr[i] = verts[i]
        val iArr = ShortArray(indices.size)
        for (i in indices.indices) iArr[i] = indices[i]

        val vertexCount = vArr.size / 3
        if (vertexCount <= 0) throw IllegalStateException("vertexCount == 0")

        val vb = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(vertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .build(engine)

        // fill direct ByteBuffer for vertex data
        val vertexByteBuffer = ByteBuffer.allocateDirect(vArr.size * 4).order(ByteOrder.nativeOrder())
        val vertexFloatBuffer = vertexByteBuffer.asFloatBuffer()
        vertexFloatBuffer.put(vArr)
        vertexFloatBuffer.rewind()
        vb.setBufferAt(engine, 0, vertexFloatBuffer)

        val ib = IndexBuffer.Builder()
            .indexCount(iArr.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)

        val indexByteBuffer = ByteBuffer.allocateDirect(iArr.size * 2).order(ByteOrder.nativeOrder())
        val indexShortBuffer = indexByteBuffer.asShortBuffer()
        indexShortBuffer.put(iArr)
        indexShortBuffer.rewind()
        ib.setBuffer(engine, indexShortBuffer)

        return Triple(vb, ib, iArr.size)
    }

    // ---------- fallback drawing (overlapping cylinders) ----------
    private fun fallbackDrawSegments(sceneView: SceneView, path: List<Float3>, material: MaterialInstance, engine: Engine, radius: Float) {
        var last = path.first()
        for (i in 1 until path.size) {
            val current = path[i]
            drawCylinderSegment(sceneView, last, current, material, engine, radius, overlap = 0.92f)
            last = current
        }
        // parent fallback segments to stadium so they move together
    }

    private fun drawCylinderSegment(sceneView: SceneView, start: Float3, end: Float3, material: MaterialInstance, engine: Engine, radius: Float, overlap: Float) {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val dz = end.z - start.z
        val length = sqrt(dx * dx + dy * dy + dz * dz) * overlap
        val mid = Float3((start.x + end.x) / 2f, (start.y + end.y) / 2f, (start.z + end.z) / 2f)
        val cyl = CylinderNode(engine = engine, radius = radius, height = length, materialInstance = material)
        cyl.position = mid
        cyl.rotation = Rotation(x = 90f, y = 0f, z = 0f)
        // parent to stadium if available
        modelNode?.addChildNode(cyl) ?: sceneView.addChildNode(cyl)
    }

    // ---------- touch: orbit (drag) + pinch (zoom) ----------
    private fun setupTouchControls() {
        sceneView.setOnTouchListener { v, event ->
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
            scaleFactor = (scaleFactor * scaleChange).coerceIn(0.25f, 2.5f)
            // scale stadium and everything under it (including track)
            modelNode?.scale = Float3(scaleFactor, scaleFactor, scaleFactor)
        }
        lastDistance = distance
    }

    // double-tap resets
    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetView()
            return true
        }
    }

    private fun resetView() {
        rotationX = 0f
        rotationY = 0f
        scaleFactor = 1.0f
        modelNode?.apply {
            rotation = Rotation(0f, 0f, 0f)
            scale = Float3(0.3f, 0.3f, 0.3f) // reset to original stadium scale
        }
    }

    // ---------- lifecycle ----------
    override fun onResume() {
        super.onResume()
//        arSceneView.resume()
    }

    override fun onPause() {
//        arSceneView.pause()
        super.onPause()
    }

    override fun onDestroy() {
        sceneView.destroy()
        super.onDestroy()
    }
}
