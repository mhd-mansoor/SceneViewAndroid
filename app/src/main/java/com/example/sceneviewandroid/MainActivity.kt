    package com.example.sceneviewandroid

    import android.graphics.PixelFormat
    import android.os.Bundle
    import android.util.Log
    import android.view.GestureDetector
    import android.view.MotionEvent
    import android.view.SurfaceHolder
    import android.view.SurfaceView
    import android.widget.Toast
    import androidx.appcompat.app.AppCompatActivity
    import androidx.core.net.toUri
    import androidx.lifecycle.lifecycleScope
    import androidx.media3.common.MediaItem
    import androidx.media3.exoplayer.ExoPlayer
    import com.example.sceneviewandroid.databinding.ActivityMainBinding
    import com.google.android.filament.Engine
    import com.google.android.filament.EntityManager
    import com.google.android.filament.IndexBuffer
    import com.google.android.filament.MaterialInstance
    import com.google.android.filament.RenderableManager
    import com.google.android.filament.VertexBuffer
    import com.google.android.filament.View
    import dev.romainguy.kotlin.math.Float3
    import io.github.sceneview.SceneView
    import io.github.sceneview.math.Color
    import io.github.sceneview.math.Rotation
    import io.github.sceneview.node.CylinderNode
    import io.github.sceneview.node.ModelNode
    import io.github.sceneview.node.Node
    import io.github.sceneview.node.SphereNode
    import kotlinx.coroutines.delay
    import kotlinx.coroutines.launch
    import java.nio.ByteBuffer
    import java.nio.ByteOrder
    import kotlin.math.PI
    import kotlin.math.cos
    import kotlin.math.sin
    import kotlin.math.sqrt
    import kotlin.random.Random
    import android.graphics.Color as mColor

    /**
     * Main activity that:
     * - Loads a 3D stadium model using SceneView
     * - Generates and draws a two-part "ball trajectory" (before and after bounce)
     * - Animates a ball along this trajectory
     * - Supports user touch gestures for rotating and zooming the stadium
     *
     * Uses Filament under the hood (via SceneView) to render custom geometry like tubes.
     */
    class MainActivity : AppCompatActivity() {

        companion object {
            private const val TAG = "MANSOOR"

            /** Scale factor for converting real-world coordinates into SceneView units */
            private const val SCALE = 0.08f

            /** Lifts the track higher so it starts above the bowler */
            private const val HEIGHT_BOOST = 1.5f

            /** Offset for stadium alignment and ground reference */
            private val STADIUM_OFFSET = Float3(0f, -0.4f, -2.5f)
        }

        private lateinit var sceneView: SceneView
        private var modelNode: ModelNode? = null

        private var exoPlayer: ExoPlayer? = null
        private var isVideoPlaying = false

        private lateinit var player: ExoPlayer

        private lateinit var surfaceView: SurfaceView
        private lateinit var binding: ActivityMainBinding

        private var pitchMapOverlayNode: Node? = null

        // Touch controls state variables
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var rotationY = 0f   // horizontal orbit (degrees)
        private var rotationX = 0f   // vertical tilt (degrees)
        private var lastDistance = 0f
        private var scaleFactor = 1.0f

        // store original transform so interactions are relative
        private var initialModelPosition: Float3? = null
        private var initialModelScale: Float3? = null
        private var initialModelRotation: Rotation? = null

        // New: Pitch overlay and impact dots
        private var pitchRegionNode: Node? = null
        private val impactDots = mutableListOf<SphereNode>()
        private var ballNode: SphereNode? = null
        private var pivotNode: Node? = null
        private lateinit var gestureDetector: GestureDetector


        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            sceneView = binding.sceneView
            val btnAnimateBall = binding.btnAnimateBall

            // Gesture detector for double-tap reset
            gestureDetector = GestureDetector(this, GestureListener())
            sceneView.setZOrderMediaOverlay(true)
            sceneView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            sceneView.holder.setFormat(PixelFormat.TRANSLUCENT)
            sceneView.uiHelper.isOpaque = false
            sceneView.view.blendMode = View.BlendMode.TRANSLUCENT
            sceneView.scene.skybox = null

            val options = sceneView.renderer.clearOptions
            options.clear = true
            sceneView.renderer.clearOptions = options

            sceneView.view.dynamicResolutionOptions =
                View.DynamicResolutionOptions().apply {
                    enabled = true
                    quality = View.QualityLevel.LOW
                }

            initializePlayer()
            // Load 3D model of stadium
            loadStadiumModel()

            binding.btnPlatVideo.setOnClickListener {
                if (player.isPlaying) {
                    stopVideo()
                } else {
                    player.play()
                }

            }
    
            binding.btnTogglePitch.setOnClickListener {
                modelNode?.isVisible = modelNode?.isVisible != true
            }
    
            var isPitchVisible = true // default state (you can set to false if needed)
    
            binding.btnTogglePitch.setOnClickListener {
                isPitchVisible = !isPitchVisible
                togglePitchVisibility(isPitchVisible)
            }
    
            // Button triggers ball track drawing
            btnAnimateBall.setOnClickListener {
                drawBallTrackFromParameters()
            }
            // Initialize orbit + zoom gestures
            setupTouchControls()

            // New: Button to add impact dots
            binding.btnAddImpactDots.setOnClickListener {
                addSampleImpactDots()
            }

            // New: Button to toggle pitch region
            binding.btnTogglePitchRegion.setOnClickListener {
            }

            // Add this to your onCreate method after other buttons
            binding.btnDebugPitch.setOnClickListener {
//                drawSimpleRectangle()
            }
        }


        // Pitch Data
        /*val pitchLengthMarkers = arrayOf(
            PitchLengthMarker(
                text = "STUMPS",
                position = Float3(1.855f, 0f, -10.06f + 1.5f / 2),
                deliveryLengthRegion = DeliveryLengthRegion(
                    height = 1.5f,
                    color = mColor.parseColor("#B0AB60"),
                    name = "Yorker"
                )
            ),
            PitchLengthMarker(text = "2M", position = Float3(1.855f, 0f, -8.06f)),
            PitchLengthMarker(
                text = "",
                position = Float3(1.855f, 0f, -10.06f + 1.5f + 3.0f / 2),
                deliveryLengthRegion = DeliveryLengthRegion(
                    height = 3.0f,
                    color = mColor.parseColor("#9DB978"),
                    name = "Full"
                )
            ),
            PitchLengthMarker(text = "4M", position = Float3(1.855f, 0f, -6.06f)),
            PitchLengthMarker(
                text = "",
                position = Float3(1.855f, 0f, -10.06f + 1.5f + 3.0f + 3.0f / 2),
                deliveryLengthRegion = DeliveryLengthRegion(
                    height = 3.0f,
                    color = mColor.parseColor("#D99A96"),
                    name = "Good"
                )
            ),
            PitchLengthMarker(text = "6M", position = Float3(1.855f, 0f, -4.06f)),
            PitchLengthMarker(text = "8M", position = Float3(1.855f, 0f, -2.06f)),
            PitchLengthMarker(
                text = "",
                position = Float3(1.855f, 0f, -10.06f + 1.5f + 3.0f + 3.0f + 12.06f / 2),
                deliveryLengthRegion = DeliveryLengthRegion(
                    height = 12.06f,
                    color = mColor.parseColor("#9BC4E2"), // pick your short color
                    name = "Short"
                )
            ),
            PitchLengthMarker(text = "HALFWAY", position = Float3(1.855f, 0f, 0f))
        )*/


        fun generateSpacedPositions(): List<Float3> {
            val random = Random(System.currentTimeMillis())
            val positions = mutableListOf<Float3>()

            repeat(1000) {
                val x = random.nextFloat() * 4f - 2f       // X between -2 and +2
                val y = 0.1f + random.nextFloat() * 0.03f - 0.01f // Y around 0.1 ± 0.01
                val z = random.nextFloat() * 12f - 6f       // Z between -6 and +1
                positions += Float3(x, y, z)
            }

            return positions
        }

        /**
         * Adds sample impact dots at strategic positions on the pitch
         * You can modify these coordinates based on your actual impact data
         */
        private fun addSampleImpactDots() {
            // Clear existing dots
            clearImpactDots()

            Log.d("MANSOOR", "addSampleImpactDots: ${generateSpacedPositions().size}")
            generateSpacedPositions().forEach { position ->
                addImpactDot(position)
            }

        }

        /**
         * Adds a single impact dot at the specified position
         */
        private fun addImpactDot(position: Float3) {
            val engine = sceneView.engine

            // Create red material for impact dots
            val dotMaterial = sceneView.materialLoader.createColorInstance(Color(1f, 0f, 0f, 1f))

            val impactDot = SphereNode(
                engine = engine,
                radius = 0.05f, // Small sphere for impact dot
                materialInstance = dotMaterial
            ).apply {
                this.position = position
                // Position slightly above pitch to avoid z-fighting
            }

            // Add to model node so it moves with the stadium
            modelNode?.addChildNode(impactDot) ?: sceneView.addChildNode(impactDot)
            impactDots.add(impactDot)
        }



        /**
         * Clears all impact dots
         */
        private fun clearImpactDots() {
            impactDots.forEach { dot ->
                modelNode?.removeChildNode(dot)
                dot.destroy()
            }
            impactDots.clear()
        }

        /**
         * Updates impact dots based on new tracking data
         * @param positions List of impact positions in pitch coordinates
         */
        fun updateImpactDots(positions: List<Float3>) {
            clearImpactDots()
            positions.forEach { position ->
                addImpactDot(position)
            }
        }


        private fun initializePlayer() {
            player = ExoPlayer.Builder(this).build()
            player.repeatMode = ExoPlayer.REPEAT_MODE_ONE

            // Attach the SurfaceView to the player when it's ready
            binding.surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    player.setVideoSurfaceHolder(holder)

                    // Load your video
                    val mediaItem = MediaItem.fromUri(
                        "asset:///sample_video.mp4".toUri()
                    )
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.playWhenReady = true
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) = Unit

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    player.setVideoSurfaceHolder(null)
                }
            })
        }

        override fun onStop() {
            super.onStop()
            exoPlayer?.pause()
        }

        private fun stopVideo() {
            player.pause()
        }

        private fun togglePitchVisibility(showPitch: Boolean) {
            // These (commented in your code) should be SHOWN when showPitch = true
            val toShow = listOf(
                "Crease", "creaseline_1", "bowl_crease_1", "pop_crease_1",
                "prot_area_11", "prot_area_12", "prot_area_13", "prot_area_14",
                "stump_1", "Stump01", "Stump02", "Stump03",
                "stumpstrike1", "stumpstrike2", "wide_line_11", "wide_line_12",
                "creaseline_2", "bowl_crease_2", "pop_crease_2",
                "prot_area_21", "prot_area_22", "prot_area_23", "prot_area_24",
                "ret_crease_21", "ret_crease_22", "stump_2",
                "Stump04", "Stump05", "Stump06",
                "stumpstrike004", "stumpstrike005",
                "wide_line_21", "wide_line_22",
                "boundary_crease", "outer_ground", "Light", "ret_crease_11", "ret_crease_12",
            )

            // These (uncommented in your code) should be HIDDEN when showPitch = true
            val toHide = listOf(
                "stadium",
                "boundary board", "boundary board.002", "boundary board.003",
                "boundary board.004", "boundary board.005", "boundary board.006",
                "boundary board.007", "boundary board.008", "boundary board.009",
                "boundary board.010", "boundary_crease", "outer_ground",
                "pitch"
            )

            if (showPitch) {
                toShow.forEach { setModelPartVisibility(it, true) }
                toHide.forEach { setModelPartVisibility(it, false) }
            } else {
                toShow.forEach { setModelPartVisibility(it, true) }
                toHide.forEach { setModelPartVisibility(it, true) }
            }

        }


        // ---------------------------------------------------------------------------------------------
        // 1. MODEL LOADING
        // ---------------------------------------------------------------------------------------------

        /**
         * Loads the 3D stadium model (GLB file) and adds it to the SceneView.
         */
        private fun loadStadiumModel() {
            val modelLoader = sceneView.modelLoader
            val modelInstance = try {
                modelLoader.createModelInstance("models/stadium_new.glb")
            } catch (e: Exception) {
                Log.e(TAG, "Model load failed: ${e.message}", e)
                return
            }

            modelNode = ModelNode(modelInstance = modelInstance).apply {
                position = Float3(0f, -0.3f, -4.9f)
                scale = Float3(0.45f, 0.45f, 0.35f)
                rotation = Rotation(3.5f, 0f, 0f)
            }

            // Create pivot and add model as its child
            pivotNode = Node(sceneView.engine).apply {
                position = Float3(0f, 0f, 0f) // pivot stays fixed
                addChildNode(modelNode!!)
            }

            sceneView.addChildNode(pivotNode!!)

            // Save originals for reset
            initialModelPosition = modelNode!!.position
            initialModelScale = modelNode!!.scale
            initialModelRotation = modelNode!!.rotation
        }


        private fun setModelPartVisibility(partName: String, visible: Boolean) {
            val asset = modelNode?.modelInstance?.asset ?: return
            val rm = sceneView.engine.renderableManager

            asset.getEntitiesByName(partName).forEach { entity ->
                val ri = rm.getInstance(entity)
                if (ri != 0) {
                    // Toggle layer visibility
                    rm.setLayerMask(ri, 0xFF, if (visible) 0xFF else 0x00)
                }
            }
        }

        private fun setModelPartVisibilityRecursive(partName: String, visible: Boolean) {
            val asset = modelNode?.modelInstance?.asset ?: return
            val rm = sceneView.engine.renderableManager
            val tm = sceneView.engine.transformManager

            // Get all entities that match the name
            val entities = asset.getEntitiesByName(partName)
            if (entities.isEmpty()) {
                Log.w("ModelVisibility", "No entities found for: $partName")
                return
            }

            // Build a recursive function to hide all child entities
            fun hideEntityTree(entity: Int) {
                val ri = rm.getInstance(entity)
                if (ri != 0) {
                    // hide/show renderable entity
                    rm.setLayerMask(ri, 0xFF, if (visible) 0xFF else 0x00)
                }

                // Traverse children
                val ti = tm.getInstance(entity)
                if (ti != 0) {
                    val childCount = tm.getChildCount(ti)
                    val children = IntArray(childCount)
                    tm.getChildren(ti, entities)
                    for (child in children) hideEntityTree(child)
                }
            }

            // Start from each matching root
            for (entity in entities) hideEntityTree(entity)

            Log.d("ModelVisibility", "Stadium '$partName' visibility set to $visible (recursive)")
        }


        // ---------------------------------------------------------------------------------------------
        // 2. BALL TRACK GENERATION
        // ---------------------------------------------------------------------------------------------

        /**
         * Draws a two-part ball path (pre-bounce and post-bounce).
         * It detects where the ball hits the ground using Y-values, and then draws two arcs:
         * one before bounce, one after (reflected trajectory).
         */
        private fun drawBallTrackFromParameters() {
            val engine = sceneView.engine

            // Materials for arcs (red)
            val material = sceneView.materialLoader.createColorInstance(Color(1f, 0f, 0f, 1f))

            // Polynomial coefficients for X, Y, Z over time (t)
            val parameterX = floatArrayOf(0.00003f, 0.00003f, -0.95f)
            val parameterY = floatArrayOf(-0.0014491995f, 0.14178087f, -0.964562f)
            val parameterZ = floatArrayOf(2.5E-4f, -0.54509115f, 37.990707f)

            val minFrameIdx = 0.0f
            val maxFrameIdx = 300.0f
            val step = 1.0f

            // Visual ground in WORLD coordinates (matches how we render with SCALE + HEIGHT_BOOST + STADIUM_OFFSET)
            val yGroundWorld = STADIUM_OFFSET.y + 0.5f // ground level = stadium base

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

            // If bounce not found, abort
            if (bounceFrame == null) {
                Log.e(
                    TAG,
                    "No ground crossing found in world coords. Cannot draw a two-part arc. prevWorldY=$prevWorldY"
                )
                Toast.makeText(this, "No ground crossing found.", Toast.LENGTH_SHORT).show()
                return
            }


            // Build both paths (before and after bounce)
            val preBouncePath = buildBallPath(
                parameterX, parameterY, parameterZ,
                minFrameIdx, bounceFrame, step, isPostBounce = false
            )

            val postBouncePath = buildBallPath(
                parameterX, parameterY, parameterZ,
                bounceFrame, maxFrameIdx, step, isPostBounce = true
            )

            // Draw both parts of the trajectory
            if (preBouncePath.isNotEmpty()) drawTube(sceneView, engine, material, preBouncePath)
            if (postBouncePath.isNotEmpty()) drawTube(sceneView, engine, material, postBouncePath)

            // Combine both paths for animation
            val combinedPath = mutableListOf<Float3>().apply {
                addAll(preBouncePath)
                addAll(postBouncePath)
            }

            // Animate ball along the path
            animateBallAlongPath(combinedPath)

        }

        private fun clearOldPaths() {
            modelNode?.childNodes?.toList()?.forEach {
                if (it !is ModelNode) modelNode?.removeChildNode(it)
            }
        }

        /**
         * Builds either the pre-bounce or post-bounce trajectory.
         * For post-bounce, it reflects Y-velocity to simulate a bounce.
         */
        private fun buildBallPath(
            parameterX: FloatArray,
            parameterY: FloatArray,
            parameterZ: FloatArray,
            startT: Float,
            endT: Float,
            step: Float,
            isPostBounce: Boolean,
        ): List<Float3> {
            val path = mutableListOf<Float3>()
            var t = startT

            if (!isPostBounce) {
                // Pre-bounce: use original trajectory
                while (t <= endT) {
                    val x = parameterX[0] * t * t + parameterX[1] * t + parameterX[2]
                    val y = parameterY[0] * t * t + parameterY[1] * t + parameterY[2]
                    val z = parameterZ[0] * t * t + parameterZ[1] * t + parameterZ[2]

                    val worldX = x * SCALE + STADIUM_OFFSET.x + 0.01f
                    val worldY = y * SCALE + HEIGHT_BOOST + STADIUM_OFFSET.y
                    val worldZ = z * SCALE + STADIUM_OFFSET.z + 4f

                    path.add(Float3(worldX, worldY, worldZ))
                    t += step
                }
            } else {
                // Post-bounce: create reflected trajectory
                val bounceT = startT

                // Calculate position and velocity at bounce point
                val bounceX =
                    parameterX[0] * bounceT * bounceT + parameterX[1] * bounceT + parameterX[2]
                val bounceY =
                    parameterY[0] * bounceT * bounceT + parameterY[1] * bounceT + parameterY[2]
                val bounceZ =
                    parameterZ[0] * bounceT * bounceT + parameterZ[1] * bounceT + parameterZ[2]

                // Calculate velocity components at bounce (derivative of position)
                val velX = 2 * parameterX[0] * bounceT + parameterX[1]
                val velY = 2 * parameterY[0] * bounceT + parameterY[1]
                val velZ = 2 * parameterZ[0] * bounceT + parameterZ[1]

                // Reflect Y velocity (bounce) with energy loss
                val reflectedVelY = -velY * 0.9f  // 0.7 = bounce coefficient

                while (t <= endT) {
                    val deltaT = t - bounceT

                    // New trajectory starting from bounce point with reflected velocity
                    val x = bounceX + velX * deltaT + parameterX[0] * deltaT * deltaT
                    val y = bounceY + reflectedVelY * deltaT + parameterY[0] * deltaT * deltaT
                    val z = bounceZ + velZ * deltaT + parameterZ[0] * deltaT * deltaT

                    val worldX = x * SCALE + STADIUM_OFFSET.x + 0.01f
                    val worldY = y * SCALE + HEIGHT_BOOST + STADIUM_OFFSET.y
                    val worldZ = z * SCALE + STADIUM_OFFSET.z + 4f

                    path.add(Float3(worldX, worldY, worldZ))
                    t += step
                }
            }

            return path
        }

        // ---------------------------------------------------------------------------------------------
        // 3. BALL ANIMATION
        // ---------------------------------------------------------------------------------------------

        /**
         * Moves a small white sphere along the given trajectory path.
         */
        private fun animateBallAlongPath(path: List<Float3>) {
            if (path.isEmpty()) return
            val engine = sceneView.engine

            // Create or reuse the ball node
            if (ballNode == null) {
                val ballMaterial = sceneView.materialLoader.createColorInstance(Color(1f, 1f, 1f, 1f))
                ballNode = SphereNode(engine, radius = 0.1f, materialInstance = ballMaterial)
                ballNode!!.position = path.first()
                modelNode?.addChildNode(ballNode!!)
            }

            // Animate with coroutine for smoothness
            lifecycleScope.launch {
                val totalSteps = path.size
                val durationMs = 1000L // total animation duration
                val frameDelay = durationMs / totalSteps

                for (i in path.indices) {
                    ballNode?.position = path[i]
                    delay(frameDelay)
                }
            }
        }


        // ---------------------------------------------------------------------------------------------
        // 4. GEOMETRY BUILDING (Tube Mesh)
        // ---------------------------------------------------------------------------------------------

        /**
         * Builds and renders a tubular mesh along the trajectory path.
         * If Filament mesh creation fails, falls back to drawing short cylinders per segment.
         */
        private fun drawTube(
            sceneView: SceneView,
            engine: Engine,
            material: MaterialInstance,
            path: List<Float3>,
        ) {
            if (path.size < 2) return

            try {
                val (vb, ib, ic) = buildTubeMesh(engine, path, 0.07f, 34)
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


        /**
         * Constructs vertex and index buffers for a tube geometry that follows a path.
         */
        private fun buildTubeMesh(
            engine: Engine,
            path: List<Float3>,
            radius: Float,
            radialSegments: Int,
        ):
                Triple<VertexBuffer, IndexBuffer, Int> {

            if (path.size < 2) throw IllegalArgumentException("path too short")

            val verts = ArrayList<Float>(path.size * radialSegments * 3)
            val indices = ArrayList<Short>((path.size - 1) * radialSegments * 6)

            // Create vertices in circular cross-sections along path
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

            // Connect rings of vertices into triangles
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

            // Create Filament-compatible buffers
            val vArr = FloatArray(verts.size)
            for (i in verts.indices) vArr[i] = verts[i]
            val iArr = ShortArray(indices.size)
            for (i in indices.indices) iArr[i] = indices[i]

            val vertexCount = vArr.size / 3
            if (vertexCount <= 0) throw IllegalStateException("vertexCount == 0")

            val vb = VertexBuffer.Builder()
                .bufferCount(1)
                .vertexCount(vertexCount)
                .attribute(
                    VertexBuffer.VertexAttribute.POSITION,
                    0,
                    VertexBuffer.AttributeType.FLOAT3,
                    0,
                    12
                )
                .build(engine)

            // fill direct ByteBuffer for vertex data
            val vertexByteBuffer =
                ByteBuffer.allocateDirect(vArr.size * 4).order(ByteOrder.nativeOrder())
            val vertexFloatBuffer = vertexByteBuffer.asFloatBuffer()
            vertexFloatBuffer.put(vArr)
            vertexFloatBuffer.rewind()
            vb.setBufferAt(engine, 0, vertexFloatBuffer)

            val ib = IndexBuffer.Builder()
                .indexCount(iArr.size)
                .bufferType(IndexBuffer.Builder.IndexType.USHORT)
                .build(engine)

            val indexByteBuffer =
                ByteBuffer.allocateDirect(iArr.size * 2).order(ByteOrder.nativeOrder())
            val indexShortBuffer = indexByteBuffer.asShortBuffer()
            indexShortBuffer.put(iArr)
            indexShortBuffer.rewind()
            ib.setBuffer(engine, indexShortBuffer)

            return Triple(vb, ib, iArr.size)
        }

        // ---------- fallback drawing (overlapping cylinders) ----------
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
                drawCylinderSegment(sceneView, last, current, material, engine, radius, overlap = 0.92f)
                last = current
            }
            // parent fallback segments to stadium so they move together
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
            // parent to stadium if available
            modelNode?.addChildNode(cyl) ?: sceneView.addChildNode(cyl)
        }


        // ---------------------------------------------------------------------------------------------
        // 5. TOUCH CONTROLS (Orbit + Zoom + Reset)
        // ---------------------------------------------------------------------------------------------

        /**
         * Configures touch listeners for single-finger rotate and two-finger pinch zoom.
         */
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

        /**
         * Handles single-finger rotation around Y-axis.
         */
        private fun handleRotate(event: MotionEvent) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastTouchX
                    lastTouchX = event.x
                    rotationY += dx * 0.5f

                    // Preserve original X and Z rotation, only update Y (yaw)
                    val baseRotation = initialModelRotation ?: Rotation(3.5f, 0f, 0f)
                    modelNode?.rotation = Rotation(baseRotation.x, y = rotationY, baseRotation.z)
                }
            }
        }

        /**
         * Handles two-finger pinch zoom on the model.
         */
        private fun handlePinchZoom(event: MotionEvent) {
            if (event.pointerCount < 2) return
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            val distance = sqrt(dx * dx + dy * dy)
            if (lastDistance != 0f) {
                val scaleChange = distance / lastDistance
                scaleFactor = (scaleFactor * scaleChange).coerceIn(0.25f, 2.5f)

                // Multiply original (non-uniform) scale by scalar factor
                val baseScale = initialModelScale ?: Float3(0.45f, 0.45f, 0.35f)
                modelNode?.scale = Float3(
                    baseScale.x * scaleFactor,
                    baseScale.y * scaleFactor,
                    baseScale.z * scaleFactor
                )
            }
        }

        /**
         * Resets rotation and zoom on double-tap gesture.
         */
        private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetView()
                return true
            }
        }

        /**
         * Restores default camera angle and stadium scale.
         */
        private fun resetView() {
            rotationY = 0f
            scaleFactor = 1.0f

            initialModelPosition?.let { modelNode?.position = it }
            initialModelScale?.let { modelNode?.scale = it }
            initialModelRotation?.let { modelNode?.rotation = it }

            // clear any gesture state
            lastDistance = 0f
            lastTouchX = 0f
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
            clearImpactDots()
            super.onDestroy()

            sceneView.destroy()
            exoPlayer?.release()
            exoPlayer = null
            super.onDestroy()
        }
    }