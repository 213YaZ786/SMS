package com.yaz.sms.core.call

import android.app.Presentation
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * The call's page drawn into a Surface that Dialer lends, as Flutter draws a platform view: the WebView
 * lives in a Presentation on a private VirtualDisplay, and the display's output is the given Surface.
 * Ported from Flutter's VirtualDisplayController, BSD-3:
 * https://raw.githubusercontent.com/flutter/flutter/master/engine/src/flutter/shell/platform/android/io/flutter/plugin/platform/VirtualDisplayController.java#VirtualDisplayController
 *
 * Changed: Flutter's PlatformViewRenderTarget is [RenderTarget], the Surface Dialer lends, or a spare
 * ImageReader's while Dialer lends none (the screen off, the call screen left), so the display and the
 * page stay attached; the one view is the WebView; minSdk is 31, so a resize is always resize31.
 *
 * not ported: accessibilityEventsDelegate, viewId, createParams, focusChangeListener (create's parameters: one view,
 *   no Flutter accessibility tree, no focus: the display takes no input)
 * not ported: resize before API 31 (minSdk is 31: a virtual display resizes itself)
 * not ported: OneTimeOnDrawListener (only the resize before API 31 used it)
 * not ported: schedule (OneTimeOnDrawListener's)
 * not ported: onDraw (OneTimeOnDrawListener's)
 * not ported: run (the runnables of OneTimeOnDrawListener, and of resize before API 31)
 * not ported: runnable (OneTimeOnDrawListener's field, same reason)
 * not ported: onViewAttachedToWindow (resize before API 31 waited for the view to attach)
 * not ported: onViewDetachedFromWindow (same, empty in Flutter)
 * not ported: resetSurface (it rebuilds the display to give a render target a new surface: setSurface does it)
 * not ported: onFlutterViewAttached (Flutter's view, not ours)
 * not ported: onFlutterViewDetached (Flutter's view, not ours)
 * not ported: onInputConnectionLocked (the page has no text input)
 * not ported: onInputConnectionUnlocked (the page has no text input)
 * not ported: dispatchTouchEvent (the page takes no touch: Dialer's screen has the controls)
 */
class PageDisplay private constructor(
    private val context: Context,
    private var virtualDisplay: VirtualDisplay,
    private val renderTarget: RenderTarget,
    private val view: View,
    private val densityDpi: Int,
    private val onSize: (remote: Boolean, width: Int, height: Int) -> Unit,
) {

    internal var presentation: PagePresentation? = null

    init {
        presentation = PagePresentation(context, virtualDisplay.display, view).also { it.show() }
    }

    /** What the display is drawn on (VirtualDisplayController.getRenderTargetWidth). */
    fun getRenderTargetWidth(): Int = renderTarget.width

    /** What the display is drawn on (VirtualDisplayController.getRenderTargetHeight). */
    fun getRenderTargetHeight(): Int = renderTarget.height

    /**
     * Dialer's Surface for the other side's picture; null when it takes it back: the display
     * then draws into the spare surface, so the page stays attached with the screen off.
     */
    fun showRemote(surface: Surface?) {
        renderTarget.lent = surface
        virtualDisplay.setSurface(renderTarget.surface)
    }

    /**
     * A new size, as Flutter's resize: nothing when it is the same, else resize31.
     * [onNewSizeFrameAvailable] runs once it is done.
     */
    fun resize(width: Int, height: Int, onNewSizeFrameAvailable: Runnable) {
        // When the size has not changed there is nothing to do.
        if (width == getRenderTargetWidth() && height == getRenderTargetHeight()) {
            getView().postDelayed(onNewSizeFrameAvailable, 0)
            return
        }
        resize31(getView(), width, height, onNewSizeFrameAvailable)
    }

    /** Resizing of a Virtual Display's Presentation is natively supported from API 31 (Flutter's resize31). */
    private fun resize31(embeddedView: View, width: Int, height: Int, onNewSizeFrameAvailable: Runnable) {
        val retired = renderTarget.resize(width, height)
        virtualDisplay.resize(width, height, densityDpi)
        // Must update the surface to match the renderTarget's current surface.
        virtualDisplay.setSurface(renderTarget.surface)
        retired?.close()
        embeddedView.postDelayed(onNewSizeFrameAvailable, 0)
        onSize(true, width, height)
    }

    /** The device turned to [degrees] (Telecom's onSetDeviceOrientation): upright 720x1280, on its side 1280x720. */
    fun setDeviceOrientation(degrees: Int) {
        val sideways = degrees == 90 || degrees == 270
        val width = if (sideways) LONG else SHORT
        val height = if (sideways) SHORT else LONG
        resize(width, height) {}
    }

    fun dispose() {
        // Fix rare crash on HuaWei device described in: https://github.com/flutter/engine/pull/9192
        presentation?.cancel()
        presentation = null
        virtualDisplay.release()
        renderTarget.release()
    }

    fun clearSurface() {
        virtualDisplay.setSurface(null)
    }

    fun getView(): View = view

    /**
     * What a display is drawn on: Dialer's Surface when it lends one, else an ImageReader's
     * (frames drained and dropped as they come). Flutter's PlatformViewRenderTarget
     * (getSurface, resize, release, getWidth, getHeight).
     */
    class RenderTarget(var width: Int, var height: Int) {
        var lent: Surface? = null
        private var spare: ImageReader? = null
        private val handler = Handler(Looper.getMainLooper())

        val surface: Surface
            get() = lent?.takeIf { it.isValid } ?: spare().surface

        private fun spare(): ImageReader = spare ?: ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { reader ->
            reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
            spare = reader
        }

        /** The new size; the spare reader of the old size is returned, to close once the display left it. */
        fun resize(width: Int, height: Int): ImageReader? {
            this.width = width
            this.height = height
            val old = spare
            spare = null
            return old
        }

        fun release() {
            lent = null
            spare?.close()
            spare = null
        }
    }

    companion object {
        private const val TAG = "PageDisplay"
        const val SHORT = 720
        const val LONG = 1280

        private val callback = object : VirtualDisplay.Callback() {
            override fun onPaused() {}
            override fun onResumed() {}
        }

        /** The display of the page, [width] by [height], or null when Android refuses one. */
        fun create(
            context: Context,
            view: View,
            renderTarget: RenderTarget,
            width: Int,
            height: Int,
            onSize: (remote: Boolean, width: Int, height: Int) -> Unit,
        ): PageDisplay? {
            if (width == 0 || height == 0) {
                return null
            }
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val metrics = context.resources.displayMetrics
            renderTarget.resize(width, height)?.close()
            val flags = 0
            val virtualDisplay = displayManager.createVirtualDisplay(
                "call-page",
                width,
                height,
                metrics.densityDpi,
                renderTarget.surface,
                flags,
                callback,
                null /* handler */
            )
            if (virtualDisplay == null) {
                Log.w(TAG, "no virtual display")
                return null
            }
            return PageDisplay(context, virtualDisplay, renderTarget, view, metrics.densityDpi, onSize)
        }
    }
}

/**
 * A presentation holding the page's one view on the private display. Flutter's SingleViewPresentation, BSD-3:
 * https://raw.githubusercontent.com/flutter/flutter/master/engine/src/flutter/shell/platform/android/io/flutter/plugin/platform/SingleViewPresentation.java#SingleViewPresentation
 *
 * not ported: PresentationState (it carries the view to a new display when a resize before API 31 made one)
 * not ported: detachState (same reason)
 * not ported: ImmContext (the input method cache: the page has no text input)
 * not ported: createDisplayContext (ImmContext's)
 * not ported: PresentationContext (a window manager that keeps pop-ups inside the display: the page opens none)
 * not ported: getSystemService (PresentationContext's and ImmContext's)
 * not ported: getWindowManager (PresentationContext's)
 * not ported: isCalledFromAlertDialog (PresentationContext's)
 * not ported: SingleViewFakeWindowViewGroup (pop-ups, same reason)
 * not ported: WindowManagerHandler (pop-ups, same reason)
 * not ported: AccessibilityDelegatingFrameLayout (no Flutter accessibility tree: the display is not shown)
 * not ported: requestSendAccessibilityEvent (AccessibilityDelegatingFrameLayout's)
 * not ported: ality (a word of Flutter's warning text about the view's context, not a function)
 * not ported: the constructor with startFocused and focusChangeListener (the display takes no focus)
 */
internal class PagePresentation(
    outerContext: Context,
    display: Display,
    private val embeddedView: View,
) : Presentation(outerContext, display) {

    // The root view for the presentation: the container with the embedded view.
    private var container: FrameLayout? = null

    init {
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        window?.setType(WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This makes sure we preserve alpha for the VD's content.
        window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        container = FrameLayout(context)

        // The view was made with the app's context; from here it belongs to the display.
        val embeddedContext = embeddedView.context
        if (embeddedContext is MutableContextWrapper) {
            embeddedContext.baseContext = context
        } else {
            Log.w("PagePresentation", "Unexpected view context; some parts may not work correctly.")
        }

        container!!.addView(embeddedView)
        setContentView(container!!)
    }

    fun getView(): View = embeddedView
}
