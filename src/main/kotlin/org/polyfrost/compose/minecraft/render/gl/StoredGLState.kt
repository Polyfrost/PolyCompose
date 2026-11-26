package org.polyfrost.compose.minecraft.render.gl

//? if >= 1.21.5 {
import com.mojang.renderpearl.backend.opengl.GlStateManager
//?}

//? if > 1.8.9 {
import org.lwjgl.opengl.GL45.*
//?}

//? if < 1.21.5 {
/*import com.mojang.blaze3d.platform.GlStateManager
*///?}

//? if < 1.21.5 && > 1.8.9
//import com.mojang.blaze3d.platform.GlStateManager.Viewport

//? if < 1.21.5 && > 1.8.9
//import com.mojang.blaze3d.vertex.BufferUploader

//? if = 1.8.9 {
/*import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL21
import org.lwjgl.opengl.GL30
*///?}

fun resyncTextureBindCache() {
    //? if > 1.8.9 {
    for (unit in 0..7) {
        GlStateManager._activeTexture(GL_TEXTURE0 + unit)
        GlStateManager._bindTexture(0)
    }
    GlStateManager._activeTexture(GL_TEXTURE0)
    //?} else {
    /*for (unit in 0..7) {
        GlStateManager.activeTexture(GL13.GL_TEXTURE0 + unit)
        GlStateManager.bindTexture(0)
    }
    GlStateManager.activeTexture(GL13.GL_TEXTURE0)
    *///?}
}

class StoredGLState(val exact: Boolean = false) {
    @Suppress("UNUSED_PARAMETER")
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    constructor(glVersion: Int) : this()

    private var depth = 0

    fun capture() {
        if (depth++ == 0) save()
    }

    fun restore() {
        if (--depth == 0) load()
    }

    //? if > 1.8.9 {
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    @JvmName("capture")
    fun captureState(): StoredGLState = apply { capture() }

    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    @JvmName("restore")
    fun restoreState(): StoredGLState = apply { restore() }
    //?}

    fun restored(block: () -> Unit) {
        if (depth != 1) return block()
        restore()
        try {
            block()
        } finally {
            capture()
        }
    }

    //? if >= 1.21.11 {
    private fun save() {}

    private fun load() {}
    //?} elif > 1.8.9 {
    /*private var drawFramebuffer = 0
    private var readFramebuffer = 0
    private var program = 0
    private var arrayBuffer = 0
    private var activeTexture = 0
    private var texture = 0
    private var blend = false
    private var blendSrcRgb = 0
    private var blendDstRgb = 0
    private var blendSrcAlpha = 0
    private var blendDstAlpha = 0
    private var depthTest = false
    private var cull = false
    private var scissor = false
    private var red = true
    private var green = true
    private var blue = true
    private var alpha = true
    private var unpackBuffer = 0
    private val viewport = IntArray(4)
    private val colorMask = IntArray(4)
    private var vertexArray = 0
    private var stencil = false
    private var blendEquationRgb = GL_FUNC_ADD
    private var blendEquationAlpha = GL_FUNC_ADD

    private fun save() {
        if (exact || !saveBindingsFromCache()) {
            drawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)
            readFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING)
        }
        activeTexture = if (exact) glGetInteger(GL_ACTIVE_TEXTURE) - GL_TEXTURE0 else GlStateManager.activeTexture
        program = glGetInteger(GL_CURRENT_PROGRAM)
        arrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING)
        unpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING)
        if (unpackBuffer != 0) glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0)
        glActiveTexture(GL_TEXTURE0)
        GlStateManager.activeTexture = 0
        if (exact) saveFromGl() else saveFromCache()
    }

    private fun saveBindingsFromCache(): Boolean {
        //? if >= 1.21.5 {
        /^drawFramebuffer = GlStateManager.getFrameBuffer(GL_DRAW_FRAMEBUFFER)
        readFramebuffer = GlStateManager.getFrameBuffer(GL_READ_FRAMEBUFFER)
        return true
        ^///?} else
        //return false
    }

    private fun saveFromCache() {
        texture = GlStateManager.TEXTURES[0].binding
        saveCapsFromCache()
        scissor = GlStateManager.SCISSOR.mode.enabled
        //? if < 1.21.5
        //viewport.let { it[0] = Viewport.x(); it[1] = Viewport.y(); it[2] = Viewport.width(); it[3] = Viewport.height() }
        //? if >= 1.21.5
        //glGetIntegerv(GL_VIEWPORT, viewport)
        saveUntracked()
    }

    private fun saveUntracked() {
        vertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING)
        stencil = glIsEnabled(GL_STENCIL_TEST)
        blendEquationRgb = glGetInteger(GL_BLEND_EQUATION_RGB)
        blendEquationAlpha = glGetInteger(GL_BLEND_EQUATION_ALPHA)
    }

    private fun saveCapsFromCache() {
        val blendState = GlStateManager.BLEND
        blend = blendState.mode.enabled
        blendSrcRgb = blendState.srcRgb
        blendDstRgb = blendState.dstRgb
        blendSrcAlpha = blendState.srcAlpha
        blendDstAlpha = blendState.dstAlpha
        depthTest = GlStateManager.DEPTH.mode.enabled
        cull = GlStateManager.CULL.enable.enabled
        val mask = GlStateManager.COLOR_MASK
        red = mask.red
        green = mask.green
        blue = mask.blue
        alpha = mask.alpha
    }

    private fun saveFromGl() {
        blend = glIsEnabled(GL_BLEND)
        blendSrcRgb = glGetInteger(GL_BLEND_SRC_RGB)
        blendDstRgb = glGetInteger(GL_BLEND_DST_RGB)
        blendSrcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA)
        blendDstAlpha = glGetInteger(GL_BLEND_DST_ALPHA)
        depthTest = glIsEnabled(GL_DEPTH_TEST)
        cull = glIsEnabled(GL_CULL_FACE)
        glGetIntegerv(GL_COLOR_WRITEMASK, colorMask)
        red = colorMask[0] != 0
        green = colorMask[1] != 0
        blue = colorMask[2] != 0
        alpha = colorMask[3] != 0
        texture = glGetInteger(GL_TEXTURE_BINDING_2D)
        scissor = glIsEnabled(GL_SCISSOR_TEST)
        glGetIntegerv(GL_VIEWPORT, viewport)
        saveUntracked()
    }

    private fun load() {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer)
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer)
        glUseProgram(program)
        //? if < 1.21.5
        //BufferUploader.invalidate()
        glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer)
        if (unpackBuffer != 0) glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer)
        glActiveTexture(GL_TEXTURE0)
        val bound = texture.coerceAtLeast(0)
        glBindTexture(GL_TEXTURE_2D, bound)
        GlStateManager.TEXTURES[0].binding = bound
        if (activeTexture != 0) glActiveTexture(GL_TEXTURE0 + activeTexture)
        GlStateManager.activeTexture = activeTexture
        toggle(GL_BLEND, blend)
        glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha)
        toggle(GL_DEPTH_TEST, depthTest)
        toggle(GL_CULL_FACE, cull)
        toggle(GL_SCISSOR_TEST, scissor)
        glColorMask(red, green, blue, alpha)
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        glBindVertexArray(vertexArray)
        toggle(GL_STENCIL_TEST, stencil)
        glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha)
    }

    private fun toggle(cap: Int, enabled: Boolean) {
        if (enabled) glEnable(cap) else glDisable(cap)
    }
    *///?} else {
    /*var drawFramebuffer = 0
        private set
    private var readFramebuffer = 0
    private var program = 0
    private var activeTexture = 0
    private var unit = 0
    private var texture = 0
    private var boundTexture = 0
    private var alphaTest = false
    private var depthTest = false
    private var blend = false
    private var blendSrcRgb = 0
    private var blendDstRgb = 0
    private var blendSrcAlpha = 0
    private var blendDstAlpha = 0
    private var shadeModel = 0
    private var depthMask = true
    private var depthFunc = 0
    private var unpackBuffer = 0

    private fun save() {
        drawFramebuffer = GlFbo.binding(GL30.GL_DRAW_FRAMEBUFFER)
        readFramebuffer = if (GlFbo.canBlit) GlFbo.binding(GL30.GL_READ_FRAMEBUFFER) else drawFramebuffer
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        activeTexture = GlStateManager.texture
        unit = if (exact) GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0 else activeTexture
        texture = GlStateManager.TEXTURES[0].texture
        alphaTest = GlStateManager.ALPHA_TEST.state.enabled
        val depthState = GlStateManager.DEPTH
        depthTest = depthState.state.enabled
        depthMask = depthState.mask
        depthFunc = depthState.func
        val blendState = GlStateManager.BLEND
        blend = blendState.state.enabled
        blendSrcRgb = blendState.sfactorRGB
        blendDstRgb = blendState.dfactorRGB
        blendSrcAlpha = blendState.sfactorAlpha
        blendDstAlpha = blendState.dfactorAlpha
        shadeModel = GlStateManager.shadeModel
        if (exact) saveFromGl()
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT)
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT or GL11.GL_VIEWPORT_BIT)
        unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING)
        if (unpackBuffer != 0) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0)
        activeTexture(GL13.GL_TEXTURE0)
        boundTexture = if (exact) GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) else texture
    }

    private fun saveFromGl() {
        alphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
        depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
        depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
        depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC)
        blend = GL11.glIsEnabled(GL11.GL_BLEND)
        blendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB)
        blendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB)
        blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA)
        blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)
        shadeModel = GL11.glGetInteger(GL11.GL_SHADE_MODEL)
    }

    private fun load() {
        GlFbo.bind(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer)
        GlFbo.bind(GL30.GL_READ_FRAMEBUFFER, readFramebuffer)
        GL11.glPopAttrib()
        GL11.glPopClientAttrib()
        if (unpackBuffer != 0) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer)
        GL20.glUseProgram(program)
        activeTexture(GL13.GL_TEXTURE0)
        GlStateManager.bindTexture(texture)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, boundTexture)
        GlStateManager.activeTexture(GL13.GL_TEXTURE0 + activeTexture)
        if (exact) GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
        if (alphaTest) GlStateManager.enableAlphaTest() else GlStateManager.disableAlphaTest()
        if (depthTest) GlStateManager.enableDepthTest() else GlStateManager.disableDepthTest()
        GlStateManager.depthMask(depthMask)
        GlStateManager.depthFunc(depthFunc)
        if (blend) GlStateManager.enableBlend() else GlStateManager.disableBlend()
        GlStateManager.blendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha)
        GlStateManager.shadeModel(shadeModel)
        if (exact) GL11.glShadeModel(shadeModel)
    }

    private fun activeTexture(unit: Int) {
        GlStateManager.activeTexture(unit)
        GL13.glActiveTexture(unit)
    }
    *///?}
}
