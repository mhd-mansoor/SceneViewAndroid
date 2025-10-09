package com.example.sceneviewandroid

import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.SceneView
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var sceneView: SceneView
    private var modelNode: Node? = null

    // rotation and zoom state
    private var lastTouchX = 0f
    private var rotationY = 0f
    private var lastDistance = 0f
    private var scaleFactor = 1.0f

    // Gesture detector for double tap
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sceneView = findViewById(R.id.sceneView)

        gestureDetector = GestureDetector(this, GestureListener())
        loadModel()
    }

    private fun loadModel() {
        ModelRenderable.builder()
            .setSource(this, Uri.parse("file:///android_asset/models/stadium.glb"))
            .setIsFilamentGltf(true)
            .setAsyncLoadEnabled(true)
            .build()
            .thenAccept { renderable ->
                modelNode = Node().apply {
                    this.renderable = renderable
                    localScale = Vector3(0.5f, 0.5f, 0.5f)
                    localPosition = Vector3(0f, -0.2f, -1.5f)
                }
                sceneView.scene.addChild(modelNode)
                setupTouchControls()
            }
            .exceptionally {
                it.printStackTrace()
                null
            }
    }

    private fun setupTouchControls() {
        sceneView.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event) // 👈 Handle double tap

            when (event.pointerCount) {
                1 -> handleRotate(event)
                2 -> handlePinchZoom(event)
            }

            if (event.action == MotionEvent.ACTION_UP) {
                v.performClick() // Accessibility compliance
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
                modelNode?.localRotation = Quaternion.axisAngle(Vector3(0f, 1f, 0f), rotationY)
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
            scaleFactor = (scaleFactor * scaleChange).coerceIn(0.3f, 2.0f)
            modelNode?.localScale = Vector3(scaleFactor, scaleFactor, scaleFactor)
        }
        lastDistance = distance
    }

    // 👇 Inner class to handle double-tap gesture
    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetModelView()
            return true
        }
    }

    private fun resetModelView() {
        rotationY = 0f
        scaleFactor = 1.0f
        modelNode?.apply {
            localRotation = Quaternion.identity()
            localScale = Vector3(scaleFactor, scaleFactor, scaleFactor)
        }
    }

    override fun onResume() {
        super.onResume()
        sceneView.resume()
    }

    override fun onPause() {
        sceneView.pause()
        super.onPause()
    }

    override fun onDestroy() {
        sceneView.destroy()
        super.onDestroy()
    }
}
