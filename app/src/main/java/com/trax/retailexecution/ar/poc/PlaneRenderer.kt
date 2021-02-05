package com.trax.retailexecution.ar.poc

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.*

class PlaneRenderer {
    private val TAG = PlaneRenderer::class.java.simpleName

    private val VERTEX_SHADER_NAME = "shaders/plane.vert"
    private val FRAGMENT_SHADER_NAME = "shaders/plane.frag"

    private val BYTES_PER_FLOAT = java.lang.Float.SIZE / 8
    private val BYTES_PER_SHORT = java.lang.Short.SIZE / 8
    private val COORDS_PER_VERTEX = 3 // x, z, alpha

    private val VERTS_PER_BOUNDARY_VERT = 2
    private val INDICES_PER_BOUNDARY_VERT = 3
    private val INITIAL_BUFFER_BOUNDARY_VERTS = 64

    private val INITIAL_VERTEX_BUFFER_SIZE_BYTES = BYTES_PER_FLOAT * COORDS_PER_VERTEX * VERTS_PER_BOUNDARY_VERT * INITIAL_BUFFER_BOUNDARY_VERTS

    private val INITIAL_INDEX_BUFFER_SIZE_BYTES = (BYTES_PER_SHORT
            * INDICES_PER_BOUNDARY_VERT
            * INDICES_PER_BOUNDARY_VERT
            * INITIAL_BUFFER_BOUNDARY_VERTS)

    private val FADE_RADIUS_M = 0.25f
    private val DOTS_PER_METER = 10.0f
    private val EQUILATERAL_TRIANGLE_SCALE = (1 / Math.sqrt(3.0)).toFloat()

    private val GRID_CONTROL = floatArrayOf(0.2f, 0.4f, 2.0f, 1.5f)

    private var planeProgram = 0
    private val textures = IntArray(1)

    private var planeXZPositionAlphaAttribute = 0

    private var planeModelUniform = 0
    private var planeNormalUniform = 0
    private var planeModelViewProjectionUniform = 0
    private var textureUniform = 0
    private var gridControlUniform = 0
    private var planeUvMatrixUniform = 0

    private var vertexBuffer = ByteBuffer.allocateDirect(INITIAL_VERTEX_BUFFER_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    private var indexBuffer = ByteBuffer.allocateDirect(INITIAL_INDEX_BUFFER_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()

    private val modelMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16)
    private val modelViewProjectionMatrix = FloatArray(16)
    private val planeAngleUvMatrix = FloatArray(4) // 2x2 rotation matrix applied to uv coords.

    private val planeIndexMap: MutableMap<Plane, Int> = HashMap()

    @Throws(IOException::class)
    fun createOnGlThread(context: Context, gridDistanceTextureName: String?) {
        val vertexShader = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_NAME)
        val passthroughShader = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_NAME)
        planeProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(planeProgram, vertexShader)
        GLES20.glAttachShader(planeProgram, passthroughShader)
        GLES20.glLinkProgram(planeProgram)
        GLES20.glUseProgram(planeProgram)
        ShaderUtil.checkGLError(TAG, "Program creation")

        val textureBitmap = BitmapFactory.decodeStream(context.assets.open(gridDistanceTextureName!!))
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glGenTextures(textures.size, textures, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, textureBitmap, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        ShaderUtil.checkGLError(TAG, "Texture loading")
        planeXZPositionAlphaAttribute = GLES20.glGetAttribLocation(planeProgram, "a_XZPositionAlpha")
        planeModelUniform = GLES20.glGetUniformLocation(planeProgram, "u_Model")
        planeNormalUniform = GLES20.glGetUniformLocation(planeProgram, "u_Normal")
        planeModelViewProjectionUniform = GLES20.glGetUniformLocation(planeProgram, "u_ModelViewProjection")
        textureUniform = GLES20.glGetUniformLocation(planeProgram, "u_Texture")
        gridControlUniform = GLES20.glGetUniformLocation(planeProgram, "u_gridControl")
        planeUvMatrixUniform = GLES20.glGetUniformLocation(planeProgram, "u_PlaneUvMatrix")
        ShaderUtil.checkGLError(TAG, "Program parameters")
    }

    private fun updatePlaneParameters(planeMatrix: FloatArray, extentX: Float, extentZ: Float, boundary: FloatBuffer?) {
        System.arraycopy(planeMatrix, 0, modelMatrix, 0, 16)
        if (boundary == null) {
            vertexBuffer.limit(0)
            indexBuffer.limit(0)
            return
        }

        boundary.rewind()
        val boundaryVertices = boundary.limit() / 2
        val numVertices: Int
        val numIndices: Int
        numVertices = boundaryVertices * VERTS_PER_BOUNDARY_VERT
        numIndices = boundaryVertices * INDICES_PER_BOUNDARY_VERT
        if (vertexBuffer.capacity() < numVertices * COORDS_PER_VERTEX) {
            var size = vertexBuffer.capacity()
            while (size < numVertices * COORDS_PER_VERTEX) {
                size *= 2
            }
            vertexBuffer = ByteBuffer.allocateDirect(BYTES_PER_FLOAT * size)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
        }
        vertexBuffer.rewind()
        vertexBuffer.limit(numVertices * COORDS_PER_VERTEX)
        if (indexBuffer.capacity() < numIndices) {
            var size = indexBuffer.capacity()
            while (size < numIndices) {
                size *= 2
            }
            indexBuffer = ByteBuffer.allocateDirect(BYTES_PER_SHORT * size)
                    .order(ByteOrder.nativeOrder())
                    .asShortBuffer()
        }
        indexBuffer.rewind()
        indexBuffer.limit(numIndices)

        val xScale = Math.max((extentX - 2 * FADE_RADIUS_M) / extentX, 0.0f)
        val zScale = Math.max((extentZ - 2 * FADE_RADIUS_M) / extentZ, 0.0f)
        while (boundary.hasRemaining()) {
            val x = boundary.get()
            val z = boundary.get()
            vertexBuffer.put(x)
            vertexBuffer.put(z)
            vertexBuffer.put(0.0f)
            vertexBuffer.put(x * xScale)
            vertexBuffer.put(z * zScale)
            vertexBuffer.put(1.0f)
        }

        indexBuffer.put(((boundaryVertices - 1) * 2).toShort())
        for (i in 0 until boundaryVertices) {
            indexBuffer.put((i * 2).toShort())
            indexBuffer.put((i * 2 + 1).toShort())
        }
        indexBuffer.put(1.toShort())

        for (i in 1 until boundaryVertices / 2) {
            indexBuffer.put(((boundaryVertices - 1 - i) * 2 + 1).toShort())
            indexBuffer.put((i * 2 + 1).toShort())
        }
        if (boundaryVertices % 2 != 0) {
            indexBuffer.put((boundaryVertices / 2 * 2 + 1).toShort())
        }
    }

    private fun draw(cameraView: FloatArray, cameraPerspective: FloatArray, planeNormal: FloatArray) {
        Matrix.multiplyMM(modelViewMatrix, 0, cameraView, 0, modelMatrix, 0)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, cameraPerspective, 0, modelViewMatrix, 0)

        vertexBuffer.rewind()
        GLES20.glVertexAttribPointer(
                planeXZPositionAlphaAttribute,
                COORDS_PER_VERTEX,
                GLES20.GL_FLOAT,
                false,
                BYTES_PER_FLOAT * COORDS_PER_VERTEX,
                vertexBuffer)

        GLES20.glUniformMatrix4fv(planeModelUniform, 1, false, modelMatrix, 0)
        GLES20.glUniform3f(planeNormalUniform, planeNormal[0], planeNormal[1], planeNormal[2])
        GLES20.glUniformMatrix4fv(planeModelViewProjectionUniform, 1, false, modelViewProjectionMatrix, 0)
        indexBuffer.rewind()
        GLES20.glDrawElements(GLES20.GL_TRIANGLE_STRIP, indexBuffer.limit(), GLES20.GL_UNSIGNED_SHORT, indexBuffer)
        ShaderUtil.checkGLError(TAG, "Drawing plane")
    }

    internal class SortablePlane(val distance: Float, val plane: Plane)

    fun drawPlanes(allPlanes: Collection<Plane>, cameraPose: Pose, cameraPerspective: FloatArray) {
        val sortedPlanes: MutableList<SortablePlane> = ArrayList()
        for (plane in allPlanes) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) {
                continue
            }
            val distance = calculateDistanceToPlane(plane.centerPose, cameraPose)
            if (distance < 0) { // Plane is back-facing.
                continue
            }
            sortedPlanes.add(SortablePlane(distance, plane))
        }
        sortedPlanes.sortWith(Comparator { a, b -> b.distance.compareTo(a.distance) })
        val cameraView = FloatArray(16)
        cameraPose.inverse().toMatrix(cameraView, 0)

        GLES20.glDepthMask(false)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(planeProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glUniform1i(textureUniform, 0)
        GLES20.glUniform4fv(gridControlUniform, 1, GRID_CONTROL, 0)
        GLES20.glEnableVertexAttribArray(planeXZPositionAlphaAttribute)
        ShaderUtil.checkGLError(TAG, "Setting up to draw planes")
        for (sortedPlane in sortedPlanes) {
            val plane = sortedPlane.plane
            val planeMatrix = FloatArray(16)
            plane.centerPose.toMatrix(planeMatrix, 0)
            val normal = FloatArray(3)
            plane.centerPose.getTransformedAxis(1, 1.0f, normal, 0)
            updatePlaneParameters(planeMatrix, plane.extentX, plane.extentZ, plane.polygon)

            var planeIndex = planeIndexMap[plane]
            if (planeIndex == null) {
                planeIndex = planeIndexMap.size
                planeIndexMap[plane] = planeIndex
            }

            val angleRadians = planeIndex * 0.144f
            val uScale = DOTS_PER_METER
            val vScale = DOTS_PER_METER * EQUILATERAL_TRIANGLE_SCALE
            planeAngleUvMatrix[0] = (+Math.cos(angleRadians.toDouble())).toFloat() * uScale
            planeAngleUvMatrix[1] = (-Math.sin(angleRadians.toDouble())).toFloat() * vScale
            planeAngleUvMatrix[2] = (+Math.sin(angleRadians.toDouble())).toFloat() * uScale
            planeAngleUvMatrix[3] = (+Math.cos(angleRadians.toDouble())).toFloat() * vScale
            GLES20.glUniformMatrix2fv(planeUvMatrixUniform, 1, false, planeAngleUvMatrix, 0)
            draw(cameraView, cameraPerspective, normal)
        }

        GLES20.glDisableVertexAttribArray(planeXZPositionAlphaAttribute)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDepthMask(true)
        ShaderUtil.checkGLError(TAG, "Cleaning up after drawing planes")
    }

    fun calculateDistanceToPlane(planePose: Pose, cameraPose: Pose): Float {
        val normal = FloatArray(3)
        val cameraX = cameraPose.tx()
        val cameraY = cameraPose.ty()
        val cameraZ = cameraPose.tz()
        planePose.getTransformedAxis(1, 1.0f, normal, 0)
        return (cameraX - planePose.tx()) * normal[0] + (cameraY - planePose.ty()) * normal[1] + (cameraZ - planePose.tz()) * normal[2]
    }
}