/* 
 * Copyright Erkinjanov Anaskhan, 06/08/22.
 */

package com.ailnor.openglsample

import android.content.Context
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix.*
import com.ailnor.openglsample.Geometry.Ray
import com.ailnor.openglsample.TextureHelper.loadTexture
import com.ailnor.openglsample.objects.Mallet
import com.ailnor.openglsample.objects.Puck
import com.ailnor.openglsample.objects.Table
import com.ailnor.openglsample.programs.ColorShaderProgram
import com.ailnor.openglsample.programs.TextureShaderProgram
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10


class AirHockeyRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private val projectionMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val viewProjectionMatrix = FloatArray(16)
    private val modelViewProjectionMatrix = FloatArray(16)

    private val invertedViewProjectionMatrix = FloatArray(16)

    private val leftBound = -0.5f
    private val rightBound = 0.5f
    private val farBound = -0.8f
    private val nearBound = 0.8f

    private lateinit var table: Table
    private lateinit var mallet: Mallet
    private lateinit var puck: Puck

    private var textureProgram: TextureShaderProgram? = null
    private var colorProgram: ColorShaderProgram? = null

    private var texture = 0

    private var malletPressed = false
    private lateinit var blueMalletPosition: Geometry.Point
    private lateinit var previousBlueMalletPosition: Geometry.Point
    private lateinit var puckPosition: Geometry.Point
    private lateinit var puckVector: Geometry.Vector

    override fun onSurfaceCreated(glUnused: GL10?, config: EGLConfig?) {
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
        table = Table()
        mallet = Mallet(0.08f, 0.15f, 32)
        puck = Puck(0.06f, 0.02f, 32)
        textureProgram = TextureShaderProgram(context)
        colorProgram = ColorShaderProgram(context)
        texture = loadTexture(context, R.drawable.air_hockey_surface)

        blueMalletPosition = Geometry.Point(0f, mallet.height/2, 0.4f)
        puckPosition = Geometry.Point(0f, puck.height/2, 0f)
        puckVector = Geometry.Vector(0f, 0f, 0f)
    }

    override fun onSurfaceChanged(glUnused: GL10?, width: Int, height: Int) {
        // Set the OpenGL viewport to fill the entire surface.
        glViewport(0, 0, width, height)
        perspectiveM(projectionMatrix,  0,60f, width.toFloat() / height.toFloat(), 1f, 10f)
        setLookAtM(viewMatrix, 0, 0f, 1.2f, 2.2f, 0f, 0f, 0f, 0f, 1f, 0f)
    }


    override fun onDrawFrame(glUnused: GL10?) {
        // Clear the rendering surface.
        glClear(GL_COLOR_BUFFER_BIT)

        // Multiply the view and projection matrices together.
        multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        invertM(invertedViewProjectionMatrix, 0, viewProjectionMatrix, 0)

        // Draw the table.
        positionTableInScene()
        textureProgram!!.useProgram()
        textureProgram!!.setUniforms(modelViewProjectionMatrix, texture)
        table.bindData(textureProgram!!)
        table.draw()

        // Draw the mallets.
        positionObjectInScene(0f, mallet.height / 2f, -0.4f)
        colorProgram!!.useProgram()
        colorProgram!!.setUniforms(modelViewProjectionMatrix, 1f, 0f, 0f)
        mallet.bindData(colorProgram!!)
        mallet.draw()
        positionObjectInScene(blueMalletPosition.x, blueMalletPosition.y, blueMalletPosition.z)
        colorProgram!!.setUniforms(modelViewProjectionMatrix, 0f, 0f, 1f)
        // Note that we don't have to define the object data twice -- we just
        // draw the same mallet again but in a different position and with a
        // different color.
        mallet.draw()

        puckPosition = puckPosition.translate(puckVector)
        if (puckPosition.x < leftBound + puck.radius
            || puckPosition.x > rightBound - puck.radius
        ) {
            puckVector = Geometry.Vector(-puckVector.x, puckVector.y, puckVector.z)
            puckVector = puckVector.scale(0.95f)
        }
        if (puckPosition.z < farBound + puck.radius || puckPosition.z > nearBound - puck.radius) {
            puckVector = Geometry.Vector(puckVector.x, puckVector.y, -puckVector.z)
            puckVector = puckVector.scale(0.95f)
        }

        // Clamp the puck position.
        puckPosition = Geometry.Point(
            clamp(puckPosition.x, leftBound + puck.radius, rightBound - puck.radius),
            puckPosition.y,
            clamp(puckPosition.z, farBound + puck.radius, nearBound - puck.radius)
        )

        puckVector = puckVector.scale(0.99f)

        // Draw the puck.
        positionObjectInScene(puckPosition.x, puckPosition.y, puckPosition.z)
        colorProgram!!.setUniforms(modelViewProjectionMatrix, 0.8f, 0.8f, 1f)
        puck.bindData(colorProgram!!)
        puck.draw()
    }

    private fun positionTableInScene() {
        // The table is defined in terms of X & Y coordinates, so we rotate it
        // 90 degrees to lie flat on the XZ plane.
        setIdentityM(modelMatrix, 0)
        rotateM(modelMatrix, 0, -90f, 1f, 0f, 0f)
        multiplyMM(
            modelViewProjectionMatrix, 0, viewProjectionMatrix,
            0, modelMatrix, 0
        )
    }

    // The mallets and the puck are positioned on the same plane as the table.
    private fun positionObjectInScene(x: Float, y: Float, z: Float) {
        setIdentityM(modelMatrix, 0)
        translateM(modelMatrix, 0, x, y, z)
        multiplyMM(
            modelViewProjectionMatrix, 0, viewProjectionMatrix,
            0, modelMatrix, 0
        )
    }

    private fun convertNormalized2DPointToRay(normalizedX: Float, normalizedY: Float): Ray {
        // We'll convert these normalized device coordinates into world-space
        // coordinates. We'll pick a point on the near and far planes, and draw a
        // line between them. To do this transform, we need to first multiply by
        // the inverse matrix, and then we need to undo the perspective divide.
        // We'll convert these normalized device coordinates into world-space
        // coordinates. We'll pick a point on the near and far planes, and draw a
        // line between them. To do this transform, we need to first multiply by
        // the inverse matrix, and then we need to undo the perspective divide.

        val nearPointNdc = floatArrayOf(normalizedX, normalizedY, -1f, 1f)
        val farPointNdc = floatArrayOf(normalizedX, normalizedY, 1f, 1f)
        val nearPointWorld = FloatArray(4)
        val farPointWorld = FloatArray(4)

        multiplyMV(nearPointWorld, 0, invertedViewProjectionMatrix, 0, nearPointNdc, 0)
        multiplyMV(farPointWorld, 0, invertedViewProjectionMatrix, 0, farPointNdc, 0)

        val nearPointRay = Geometry.Point(nearPointWorld[0], nearPointWorld[1], nearPointWorld[2])
        val farPointRay = Geometry.Point(farPointWorld[0], farPointWorld[1], farPointWorld[2])
        return Ray(nearPointRay, Geometry.vectorBetween(nearPointRay, farPointRay))
    }

    private fun divideByW(vector: FloatArray){
        vector[0] /= vector[3]
        vector[1] /= vector[3]
        vector[2] /= vector[3]
    }

    fun handleTouchPress(normalizedX: Float, normalizedY: Float){
        val ray: Ray = convertNormalized2DPointToRay(normalizedX, normalizedY)

        // Now test if this ray intersects with the mallet by creating a
        // bounding sphere that wraps the mallet.
        // Now test if this ray intersects with the mallet by creating a
        // bounding sphere that wraps the mallet.

        val malletBoundingSphere = Geometry.Sphere(
            Geometry.Point(
                blueMalletPosition.x,
                blueMalletPosition.y,
                blueMalletPosition.z
            ),
            mallet.height / 2f
        )

        // If the ray intersects (if the user touched a part of the screen that
        // intersects the mallet's bounding sphere), then set malletPressed =
        // true.
        // If the ray intersects (if the user touched a part of the screen that
        // intersects the mallet's bounding sphere), then set malletPressed =
        // true.

        malletPressed = Geometry.intersects(malletBoundingSphere, ray)
    }

    fun handleTouchDrag(normalizedX: Float, normalizedY: Float){
        if (malletPressed) {
            val ray = convertNormalized2DPointToRay(normalizedX, normalizedY)
            // Define a plane representing our air hockey table.
            val plane = Geometry.Plane(Geometry.Point(0f, 0f, 0f), Geometry.Vector(0f, 1f, 0f))
            // Find out where the touched point intersects the plane
            // representing our table. We'll move the mallet along this plane.
            val touchedPoint: Geometry.Point = Geometry.intersectionPoint(ray, plane)
            previousBlueMalletPosition = blueMalletPosition
            blueMalletPosition =
                Geometry.Point(
                    clamp(touchedPoint.x, leftBound + mallet.radius, rightBound - mallet.radius),
                    mallet.radius / 2f,
                    clamp(touchedPoint.z, mallet.radius, nearBound - mallet.radius))

            val distance = Geometry.vectorBetween(blueMalletPosition, puckPosition).length()

            if (distance < puck.radius + mallet.radius){
                puckVector = Geometry.vectorBetween(previousBlueMalletPosition, blueMalletPosition)
            }
        }
    }

    private fun clamp(value: Float, min: Float, max: Float): Float {
        return max.coerceAtMost(value.coerceAtLeast(min))
    }

}