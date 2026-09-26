package com.folium.reader.ink

/**
 * Whether the sheet surface should force `InProgressStrokesView`'s deprecated
 * `useHighLatencyRenderHelper` path rather than its default low-latency front-buffer renderer.
 *
 * On a GPU that does not actually support front-buffer hardware buffers, the low-latency path
 * silently draws nothing until the finger or stylus lifts: verified on a MediaTek MT8766 tablet
 * running Android 13, where every in-progress stroke was invisible while being drawn and only
 * appeared, all at once, on release. The high-latency helper falls back to a plain, always-visible
 * software-composited render instead, at the cost of the extra latency its name describes.
 *
 * True below API 33, where this app does not probe hardware buffer support at all and assumes the
 * worst; otherwise true exactly when [frontBufferSupported] reports the probe failed.
 */
fun prefersStandardInkRenderer(sdkInt: Int, frontBufferSupported: Boolean): Boolean =
    sdkInt < 33 || !frontBufferSupported

/**
 * Whether `InProgressStrokesView` must render into its own offscreen layer.
 *
 * The standard renderer applies `maskPath` by drawing it with a `PorterDuff.Mode.CLEAR` paint
 * straight onto the view's canvas. Without a layer of its own, that clear reaches the window's
 * buffer and wipes whatever the reader drew underneath, leaving the book page opaque black once the
 * first stroke triggers a draw. A hardware layer confines the clear to the view's own transparent
 * pixels. The front-buffer renderer draws into its own buffer and must not be wrapped, and a sheet
 * sets no mask, so only [standardRenderer] together with [masksToPage] needs it.
 */
fun needsOffscreenInProgressLayer(standardRenderer: Boolean, masksToPage: Boolean): Boolean =
    standardRenderer && masksToPage
