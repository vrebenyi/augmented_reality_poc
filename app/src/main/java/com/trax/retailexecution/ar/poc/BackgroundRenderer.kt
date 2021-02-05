package com.trax.retailexecution.ar.poc

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class BackgroundRenderer {
    private var quadCoords: FloatBuffer? = null
    private var quadTexCoords: FloatBuffer? = null
    private var cameraProgram = 0
    private var depthProgram = 0
    private var cameraPositionAttrib = 0
    private var cameraTexCoordAttrib = 0
    private var cameraTextureUniform = 0
    var textureId = -1
    private var suppressTimestampZeroRendering = true
    private var depthPositionAttrib = 0
    private var depthTexCoordAttrib = 0
    private var depthTextureUniform = 0
    private var depthTextureId = -1

    @JvmOverloads
    @Throws(IOException::class)
    fun createOnGlThread(context: Context, depthTextureId: Int = -1) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        val textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(textureTarget, textureId)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val numVertices = 4
        if (numVertices != QUAD_COORDS.size / COORDS_PER_VERTEX) {
            throw RuntimeException("Unexpected number of vertices in BackgroundRenderer.")
        }
        val bbCoords = ByteBuffer.allocateDirect(QUAD_COORDS.size * FLOAT_SIZE)
        bbCoords.order(ByteOrder.nativeOrder())
        quadCoords = bbCoords.asFloatBuffer()
        quadCoords?.put(QUAD_COORDS)
        quadCoords?.position(0)
        val bbTexCoordsTransformed = ByteBuffer.allocateDirect(numVertices * TEXCOORDS_PER_VERTEX * FLOAT_SIZE)
        bbTexCoordsTransformed.order(ByteOrder.nativeOrder())
        quadTexCoords = bbTexCoordsTransformed.asFloatBuffer()

        run {
            val vertexShader: Int = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_VERTEX_SHADER, CAMERA_VERTEX_SHADER_NAME)
            val fragmentShader: Int = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_FRAGMENT_SHADER, CAMERA_FRAGMENT_SHADER_NAME)
            cameraProgram = GLES20.glCreateProgram()
            GLES20.glAttachShader(cameraProgram, vertexShader)
            GLES20.glAttachShader(cameraProgram, fragmentShader)
            GLES20.glLinkProgram(cameraProgram)
            GLES20.glUseProgram(cameraProgram)
            cameraPositionAttrib = GLES20.glGetAttribLocation(cameraProgram, "a_Position")
            cameraTexCoordAttrib = GLES20.glGetAttribLocation(cameraProgram, "a_TexCoord")
            ShaderUtil.checkGLError(TAG, "Program creation")
            cameraTextureUniform = GLES20.glGetUniformLocation(cameraProgram, "sTexture")
            ShaderUtil.checkGLError(TAG, "Program parameters")
        }

        run {
            val vertexShader: Int = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_VERTEX_SHADER, DEPTH_VISUALIZER_VERTEX_SHADER_NAME)
            val fragmentShader: Int = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_FRAGMENT_SHADER, DEPTH_VISUALIZER_FRAGMENT_SHADER_NAME)
            depthProgram = GLES20.glCreateProgram()
            GLES20.glAttachShader(depthProgram, vertexShader)
            GLES20.glAttachShader(depthProgram, fragmentShader)
            GLES20.glLinkProgram(depthProgram)
            GLES20.glUseProgram(depthProgram)
            depthPositionAttrib = GLES20.glGetAttribLocation(depthProgram, "a_Position")
            depthTexCoordAttrib = GLES20.glGetAttribLocation(depthProgram, "a_TexCoord")
            ShaderUtil.checkGLError(TAG, "Program creation")
            depthTextureUniform = GLES20.glGetUniformLocation(depthProgram, "u_DepthTexture")
            ShaderUtil.checkGLError(TAG, "Program parameters")
        }
        this.depthTextureId = depthTextureId
    }

    fun suppressTimestampZeroRendering(suppressTimestampZeroRendering: Boolean) {
        this.suppressTimestampZeroRendering = suppressTimestampZeroRendering
    }

    @JvmOverloads
    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                    quadCoords,
                    Coordinates2d.TEXTURE_NORMALIZED,
                    quadTexCoords)
        }
        if (frame.timestamp == 0L && suppressTimestampZeroRendering) {
            return
        }
        draw()
    }

    private fun draw() {
        quadTexCoords!!.position(0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUseProgram(cameraProgram)
        GLES20.glUniform1i(cameraTextureUniform, 0)
        GLES20.glVertexAttribPointer(cameraPositionAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadCoords)
        GLES20.glVertexAttribPointer(cameraTexCoordAttrib, TEXCOORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadTexCoords)
        GLES20.glEnableVertexAttribArray(cameraPositionAttrib)
        GLES20.glEnableVertexAttribArray(cameraTexCoordAttrib)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(cameraPositionAttrib)
        GLES20.glDisableVertexAttribArray(cameraTexCoordAttrib)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        ShaderUtil.checkGLError(TAG, "BackgroundRendererDraw")
    }

    companion object {
        private val TAG = BackgroundRenderer::class.java.simpleName

        private const val CAMERA_VERTEX_SHADER_NAME = "shaders/screenquad.vert"
        private const val CAMERA_FRAGMENT_SHADER_NAME = "shaders/screenquad.frag"
        private const val DEPTH_VISUALIZER_VERTEX_SHADER_NAME = "shaders/background_show_depth_color_visualization.vert"
        private const val DEPTH_VISUALIZER_FRAGMENT_SHADER_NAME = "shaders/background_show_depth_color_visualization.frag"
        private const val COORDS_PER_VERTEX = 2
        private const val TEXCOORDS_PER_VERTEX = 2
        private const val FLOAT_SIZE = 4

        /**
         * (-1, 1) ------- (1, 1)
         * |    \           |
         * |       \        |
         * |          \     |
         * |             \  |
         * (-1, -1) ------ (1, -1)
         * Ensure triangles are front-facing, to support glCullFace().
         * This quad will be drawn using GL_TRIANGLE_STRIP which draws two
         * triangles: v0->v1->v2, then v2->v1->v3.
         */
        private val QUAD_COORDS = floatArrayOf(-1.0f, -1.0f, +1.0f, -1.0f, -1.0f, +1.0f, +1.0f, +1.0f)
    }
}