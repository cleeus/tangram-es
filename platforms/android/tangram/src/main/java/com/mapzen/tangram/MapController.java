package com.mapzen.tangram;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;
import android.opengl.GLSurfaceView;
import android.opengl.GLSurfaceView.Renderer;
import android.os.Build;
import android.os.Handler;
import android.support.annotation.IntRange;
import android.support.annotation.Keep;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.LongSparseArray;

import com.mapzen.tangram.TouchInput.Gestures;
import com.mapzen.tangram.networking.DefaultHttpHandler;
import com.mapzen.tangram.networking.HttpHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * {@code MapController} is the main class for interacting with a Tangram map.
 */
public class MapController implements Renderer {


    /**
     * Options for interpolating map parameters
     */
    @Keep
    public enum EaseType {
        LINEAR,
        CUBIC,
        QUINT,
        SINE,
    }

    /**
     * Options for changing the appearance of 3D geometry
     */
    public enum CameraType {
        PERSPECTIVE,
        ISOMETRIC,
        FLAT,
    }

    /**
     * Options representing an error generated after from the map controller
     */
    public enum Error {
        NONE,
        SCENE_UPDATE_PATH_NOT_FOUND,
        SCENE_UPDATE_PATH_YAML_SYNTAX_ERROR,
        SCENE_UPDATE_VALUE_YAML_SYNTAX_ERROR,
        NO_VALID_SCENE,
    }

    protected static EaseType DEFAULT_EASE_TYPE = EaseType.CUBIC;

    /**
     * Options for enabling debug rendering features
     */
    public enum DebugFlag {
        FREEZE_TILES,
        PROXY_COLORS,
        TILE_BOUNDS,
        TILE_INFOS,
        LABELS,
        TANGRAM_INFOS,
        DRAW_ALL_LABELS,
        TANGRAM_STATS,
        SELECTION_BUFFER,
    }

    /**
     * Interface for a callback to receive information about features picked from the map
     * Triggered after a call of {@link #pickFeature(float, float)}
     * Listener should be set with {@link #setFeaturePickListener(FeaturePickListener)}
     * The callback will be run on the main (UI) thread.
     */
    @Keep
    public interface FeaturePickListener {
        /**
         * Receive information about features found in a call to {@link #pickFeature(float, float)}
         * @param properties A mapping of string keys to string or number values
         * @param positionX The horizontal screen coordinate of the picked location
         * @param positionY The vertical screen coordinate of the picked location
         */
        void onFeaturePick(final Map<String, String> properties, final float positionX, final float positionY);
    }
    /**
     * Interface for a callback to receive information about labels picked from the map
     * Triggered after a call of {@link #pickLabel(float, float)}
     * Listener should be set with {@link #setLabelPickListener(LabelPickListener)}
     * The callback will be run on the main (UI) thread.
     */
    @Keep
    public interface LabelPickListener {
        /**
         * Receive information about labels found in a call to {@link #pickLabel(float, float)}
         * @param labelPickResult The {@link LabelPickResult} that has been selected
         * @param positionX The horizontal screen coordinate of the picked location
         * @param positionY The vertical screen coordinate of the picked location
         */
        void onLabelPick(final LabelPickResult labelPickResult, final float positionX, final float positionY);
    }

    /**
     * Interface for a callback to receive the picked {@link Marker}
     * Triggered after a call of {@link #pickMarker(float, float)}
     * Listener should be set with {@link #setMarkerPickListener(MarkerPickListener)}
     * The callback will be run on the main (UI) thread.
     */
    @Keep
    public interface MarkerPickListener {
        /**
         * Receive information about marker found in a call to {@link #pickMarker(float, float)}
         * @param markerPickResult The {@link MarkerPickResult} the marker that has been selected
         * @param positionX The horizontal screen coordinate of the picked location
         * @param positionY The vertical screen coordinate of the picked location
         */
        void onMarkerPick(final MarkerPickResult markerPickResult, final float positionX, final float positionY);
    }

    /**
     * Interface for listening to scene load status information.
     * Triggered after a call of {@link #updateSceneAsync(List<SceneUpdate>)} or
     * {@link #loadSceneFileAsync(String, List<SceneUpdate>)} or {@link #loadSceneFile(String, List<SceneUpdate>)}
     * Listener should be set with {@link #setSceneLoadListener(SceneLoadListener)}
     * The callbacks will be run on the main (UI) thread.
     */
    @Keep
    public interface SceneLoadListener {
        /**
         * Received when a scene load or update finishes. If sceneError is not null then the operation did not succeed.
         * @param sceneId The identifier returned by {@link #updateSceneAsync(List<SceneUpdate>)} or
         * {@link #loadSceneFileAsync(String, List<SceneUpdate>)}.
         * @param sceneError A {@link SceneError} holding error information, or null if no error occurred.
         */
        void onSceneReady(final int sceneId, final SceneError sceneError);
    }

    public interface CameraAnimationCallback {
        void onFinish();
        void onCancel();
    }

    /**
     * Callback for {@link #captureFrame(FrameCaptureCallback, boolean) }
     */
    public interface FrameCaptureCallback {
        /**
         * Called on the render-thread when a frame was captured.
         */
        void onCaptured(@NonNull final Bitmap bitmap);
    }

    /**
     * Capture MapView as Bitmap.
     * @param waitForCompleteView Delay the capture until the view is fully loaded and
     *                            no ease- or label-animation is running.
     */
    public void captureFrame(@NonNull final FrameCaptureCallback callback, final boolean waitForCompleteView) {
        frameCaptureCallback = callback;
        frameCaptureAwaitCompleteView = waitForCompleteView;
        requestRender();
    }

    @NonNull
    private Bitmap capture() {
        final int w = mapView.getWidth();
        final int h = mapView.getHeight();

        final int b[] = new int[w * h];
        final int bt[] = new int[w * h];

        nativeCaptureSnapshot(mapPointer, b);

        for (int i = 0; i < h; i++) {
            for (int j = 0; j < w; j++) {
                final int pix = b[i * w + j];
                final int pb = (pix >> 16) & 0xff;
                final int pr = (pix << 16) & 0x00ff0000;
                final int pix1 = (pix & 0xff00ff00) | pr | pb;
                bt[(h - i - 1) * w + j] = pix1;
            }
        }

        return Bitmap.createBitmap(bt, w, h, Bitmap.Config.ARGB_8888);
    }

    /**
     * Construct a MapController using a custom scene file
     * @param view GLSurfaceView for the map display; input events from this
     * view will be handled by the MapController's TouchInput gesture detector.
     * It also provides the Context in which the map will function; the asset
     * bundle for this activity must contain all the local files that the map
     * will need.
     * @param handler {@link HttpHandler} to initialize httpHandler for network handling
     */
    protected MapController(@NonNull final GLSurfaceView view, @Nullable final HttpHandler handler) {
        if (Build.VERSION.SDK_INT > 18) {
            clientTileSources = new ArrayMap<>();
        } else {
            clientTileSources = new HashMap<>();
        }
        markers = new LongSparseArray<>();

        mapView = view;

        // Get configuration info from application
        displayMetrics = mapView.getContext().getResources().getDisplayMetrics();
        assetManager = mapView.getContext().getAssets();

        fontFileParser = new FontFileParser();

        // Use the DefaultHttpHandler if none is provided
        if (handler == null) {
            httpHandler = new DefaultHttpHandler();
        } else {
            httpHandler = handler;
        }

        // Parse font file description
        fontFileParser.parse();

        mapPointer = nativeInit(this, assetManager);
        if (mapPointer <= 0) {
            throw new RuntimeException("Unable to create a native Map object! There may be insufficient memory available.");
        }
    }

    /**
     * Responsible to configure {@link MapController} configuration on the ui thread.
     * Must be called from the ui thread post instantiation of {@link MapController}
     */
    void UIThreadInit() {
        // Set up MapView
        mapView.setRenderer(this);
        isGLRendererSet = true;
        mapView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        mapView.setPreserveEGLContextOnPause(true);

        touchInput = new TouchInput(mapView.getContext());
        mapView.setOnTouchListener(touchInput);

        setPanResponder(null);
        setScaleResponder(null);
        setRotateResponder(null);
        setShoveResponder(null);

        touchInput.setSimultaneousDetectionAllowed(Gestures.SHOVE, Gestures.ROTATE, false);
        touchInput.setSimultaneousDetectionAllowed(Gestures.ROTATE, Gestures.SHOVE, false);
        touchInput.setSimultaneousDetectionAllowed(Gestures.SHOVE, Gestures.SCALE, false);
        touchInput.setSimultaneousDetectionAllowed(Gestures.SHOVE, Gestures.PAN, false);
        touchInput.setSimultaneousDetectionAllowed(Gestures.SCALE, Gestures.LONG_PRESS, false);

        uiThreadHandler = new Handler(mapView.getContext().getMainLooper());
    }

    void dispose() {
        // No GL setup from UI Thread, early return
        if (!isGLRendererSet) { return; }

        // Disposing native resources involves GL calls, so we need to run on the GL thread.
        queueEvent(new Runnable() {
            @Override
            public void run() {
                // Dispose each data sources by first removing it from the Map values and then
                // calling remove(), so that we don't improperly modify the Map while iterating.
                for (final Iterator<MapData> it = clientTileSources.values().iterator(); it.hasNext();) {
                    final MapData mapData = it.next();
                    it.remove();
                    mapData.remove();
                }
                nativeDispose(mapPointer);
                mapPointer = 0;
                clientTileSources.clear();
                markers.clear();
            }
        });

        // Dispose all listener and callbacks associated with mapController
        // This will help prevent leaks of references from the client code, possibly used in these listener/callbacks
        touchInput = null;
        mapChangeListener = null;
        featurePickListener = null;
        sceneLoadListener = null;
        labelPickListener = null;
        markerPickListener = null;
        cameraAnimationCallback = null;
        frameCaptureCallback = null;
    }

    /**
     * Load a new scene file synchronously.
     * Use {@link #setSceneLoadListener(SceneLoadListener)} for notification when the new scene is
     * ready.
     * @param path Location of the YAML scene file within the application assets
     * @return Scene ID An identifier for the scene being loaded, the same value will be passed to
     * {@link SceneLoadListener#onSceneReady(int sceneId, SceneError sceneError)} when loading is complete.
     */
    public int loadSceneFile(final String path) {
        return loadSceneFile(path, null);
    }

    /**
     * Load a new scene file asynchronously.
     * Use {@link #setSceneLoadListener(SceneLoadListener)} for notification when the new scene is
     * ready.
     * @param path Location of the YAML scene file within the application assets
     * @return Scene ID An identifier for the scene being loaded, the same value will be passed to
     * {@link SceneLoadListener#onSceneReady(int sceneId, SceneError sceneError)} when loading is complete.
     */
    public int loadSceneFileAsync(final String path) {
        return loadSceneFileAsync(path, null);
    }

    /**
     * Load a new scene file synchronously.
     * If scene updates triggers an error, they won't be applied.
     * Use {@link #setSceneLoadListener(SceneLoadListener)} for notification when the new scene is
     * ready.
     * @param path Location of the YAML scene file within the application assets
     * @param sceneUpdates List of {@code SceneUpdate}
     * @return Scene ID An identifier for the scene being loaded, the same value will be passed to
     * {@link SceneLoadListener#onSceneReady(int sceneId, SceneError sceneError)} when loading is complete.
     */
    public int loadSceneFile(final String path, @Nullable final List<SceneUpdate> sceneUpdates) {
        checkPointer(mapPointer);
        final String[] updateStrings = bundleSceneUpdates(sceneUpdates);
        final int sceneId = nativeLoadScene(mapPointer, path, updateStrings);
        removeAllMarkers();
        requestRender();
        return sceneId;
    }

    /**
     * Load a new scene file asynchronously.
     * If scene updates triggers an error, they won't be applied.
     * Use {@link #setSceneLoadListener(SceneLoadListener)} for notification when the new scene is
     * ready.
     * @param path Location of the YAML scene file within the application assets
     * @param sceneUpdates List of {@code SceneUpdate}
     * @return Scene ID An identifier for the scene being loaded, the same value will be passed to
     * {@link SceneLoadListener#onSceneReady(int sceneId, SceneError sceneError)} when loading is complete.
     */
    public int loadSceneFileAsync(final String path, @Nullable final List<SceneUpdate> sceneUpdates) {
        checkPointer(mapPointer);
        final String[] updateStrings = bundleSceneUpdates(sceneUpdates);
        final int sceneId = nativeLoadSceneAsync(mapPointer, path, updateStrings);
        removeAllMarkers();
        requestRender();
        return sceneId;
    }

    /**
     * Load a new scene synchronously, provided an explicit yaml scene string to load
     * If scene updates triggers an error, they won't be applied.
     * Use {@link #setSceneLoadListener(SceneLoadListener)} for notification when the new scene is
     * ready.
     * @param yaml YAML scene String
     * @param resourceRoot base path to resolve relative URLs
     * @param sceneUpdates List of {@code SceneUpdate}
     * @return Scene ID An identifier for the scene being loaded, the same value will be passed to
     */
    public int loadSceneYaml(final String yaml, final String resourceRoot,
                             @Nullable final List<SceneUpdate> sceneUpdates) {
        checkPointer(mapPointer);
        final String[] updateStrings = bundleSceneUpdates(sceneUpdates);
        final int sceneId = nativeLoadSceneYaml(mapPointer, yaml, resourceRoot, updateStrings);
        removeAllMarkers();
        requestRender();
        return sceneId;
    }

    /**
     * Load a new scene asynchronously, provided an explicit yaml scene string to load
     * If scene updates triggers an error, they won't be applied.
     * Use {@link #setSceneLoadListener(SceneLoadListener)} for notification when the new scene is
     * ready.
     * @param yaml YAML scene String
     * @param resourceRoot base path to resolve relative URLs
     * @param sceneUpdates List of {@code SceneUpdate}
     * @return Scene ID An identifier for the scene being loaded, the same value will be passed to
     */
    public int loadSceneYamlAsync(final String yaml, final String resourceRoot,
                                  @Nullable final List<SceneUpdate> sceneUpdates) {
        checkPointer(mapPointer);
        final String[] updateStrings = bundleSceneUpdates(sceneUpdates);
        final int sceneId = nativeLoadSceneYamlAsync(mapPointer, yaml, resourceRoot, updateStrings);
        removeAllMarkers();
        requestRender();
        return sceneId;
    }

    /**
     * Apply SceneUpdates to the current scene asyncronously
     * If a updates trigger an error, scene updates won't be applied.
     * Use {@link #setSceneLoadListener(SceneLoadListener)} for notification when the new scene is
     * ready.
     * @param sceneUpdates List of {@code SceneUpdate}
     * @return new scene ID
     */
    public int updateSceneAsync(@NonNull final List<SceneUpdate> sceneUpdates) {
        checkPointer(mapPointer);

        if (sceneUpdates == null || sceneUpdates.size() == 0) {
            throw new IllegalArgumentException("sceneUpdates can not be null or empty in queueSceneUpdates");
        }

        removeAllMarkers();

        final String[] updateStrings = bundleSceneUpdates(sceneUpdates);

        return nativeUpdateScene(mapPointer, updateStrings);
    }

    /**
     * Set the camera position of the map view
     * @param update CameraUpdate to modify current camera position
     */
    public void updateCameraPosition(@NonNull final CameraUpdate update) {
        updateCameraPosition(update, 0, DEFAULT_EASE_TYPE,null);
    }

    /**
     * Animate the camera position of the map view with the default easing function
     * @param update CameraUpdate to update current camera position
     * @param duration Time in milliseconds to ease to the updated position
     */
    public void updateCameraPosition(@NonNull final CameraUpdate update, final int duration) {
        updateCameraPosition(update, duration, DEFAULT_EASE_TYPE, null);
    }

    /**
     * Animate the camera position of the map view with an easing function
     * @param update CameraUpdate to update current camera position
     * @param duration Time in milliseconds to ease to the updated position
     * @param ease Type of easing to use
     */
    public void updateCameraPosition(@NonNull final CameraUpdate update, final int duration, @NonNull final EaseType ease) {
        updateCameraPosition(update, duration, ease, null);
    }

    /**
     * Animate the camera position of the map view and run a callback when the animation completes
     * @param update CameraUpdate to update current camera position
     * @param duration Time in milliseconds to ease to the updated position
     * @param cb Callback that will run when the animation is finished or canceled
     */
    public void updateCameraPosition(@NonNull final CameraUpdate update, final int duration, @NonNull final CameraAnimationCallback cb) {
        updateCameraPosition(update, duration, DEFAULT_EASE_TYPE, cb);
    }

    /**
     * Animate the camera position of the map view with an easing function and run a callback when
     * the animation completes
     * @param update CameraUpdate to update current camera position
     * @param duration Time in milliseconds to ease to the updated position
     * @param ease Type of easing to use
     * @param cb Callback that will run when the animation is finished or canceled
     */
    public void updateCameraPosition(@NonNull final CameraUpdate update, final int duration, @NonNull final EaseType ease, @Nullable final CameraAnimationCallback cb) {

        // TODO: Appropriately handle call to `mapChangeListener.onRegionIsChanging` during camera animation updates.

        checkPointer(mapPointer);

        final boolean animated = (duration != 0);
        onRegionWillChange(animated);

        CameraAnimationCallback callback = new CameraAnimationCallback() {
            @Override
            public void onFinish() {
                onRegionDidChange(animated);
                if (cb != null) {
                    cb.onFinish();
                }
            }

            @Override
            public void onCancel() {
                // Possible camera update was cancelled in between, so should account for this map change
                onRegionDidChange(animated);
                if (cb != null) {
                    cb.onCancel();
                }
            }
        };

        if (cameraAnimationCallback != null) {
            // NB: Prevent recursion loop when updateCameraPosition is called from onCancel callback
            CameraAnimationCallback prev = cameraAnimationCallback;
            cameraAnimationCallback = null;
            prev.onCancel();
        }

        final float seconds = duration / 1000.f;

        nativeUpdateCameraPosition(mapPointer, update.set, update.longitude, update.latitude, update.zoom,
                update.zoomBy, update.rotation, update.rotationBy, update.tilt, update.tiltBy,
                update.boundsLon1, update.boundsLat1, update.boundsLon2, update.boundsLat2, update.padding,
                seconds, ease.ordinal());

        if (duration > 0) {
            cameraAnimationCallback = callback;
        } else {
            callback.onFinish();
        }
    }

    /**
     * Get the {@link CameraPosition} of the map view
     * @return The current camera position
     */
    @NonNull
    public CameraPosition getCameraPosition() {
        return getCameraPosition(new CameraPosition());
    }

    /**
     * Get the {@link CameraPosition} of the map view
     * @param out CameraPosition to be reused as the output
     * @return the current camera position of the map view
     */
    @NonNull
    public CameraPosition getCameraPosition(@NonNull final CameraPosition out) {
        checkPointer(mapPointer);
        final double[] pos = { 0, 0 };
        final float[] zrt = { 0, 0, 0 };
        nativeGetCameraPosition(mapPointer, pos, zrt);
        out.longitude = pos[0];
        out.latitude = pos[1];
        out.zoom = zrt[0];
        out.rotation = zrt[1];
        out.tilt = zrt[2];
        return out;
    }

    /**
     * Cancel current camera animation
     */
    public void cancelCameraAnimation() {
        checkPointer(mapPointer);
        nativeCancelCameraAnimation(mapPointer);
    }

    /**
     * Get the {@link CameraPosition} that that encloses the given bounds with at least the given amount of padding on each side.
     * @param sw south-west coordinate
     * @param ne northwest coordinate
     * @param padding The minimum distance to keep between the bounds and the edges of the view
     * @return The enclosing camera position
     */
    @NonNull
    public CameraPosition getEnclosingCameraPosition(@NonNull LngLat sw, @NonNull LngLat ne, @NonNull Rect padding) {
        return getEnclosingCameraPosition(sw, ne, padding, new CameraPosition());
    }

    /**
     * Get the {@link CameraPosition} that that encloses the given bounds with at least the given amount of padding on each side.
     * @param sw south-west coordinate
     * @param ne northwest coordinate
     * @param padding The minimum distance to keep between the bounds and the edges of the view
     * @param out CameraPosition to be reused as the output
     * @return The enclosing camera position
     */
    @NonNull
    public CameraPosition getEnclosingCameraPosition(@NonNull LngLat sw, @NonNull LngLat ne, @NonNull Rect padding, @NonNull final CameraPosition out) {
        int pad[] = new int[]{padding.left, padding.top, padding.right, padding.bottom};
        double lngLatZoom[] = new double[3];
        nativeGetEnclosingCameraPosition(mapPointer, sw.longitude, sw.latitude, ne.longitude, ne.latitude, pad, lngLatZoom);
        out.longitude = lngLatZoom[0];
        out.latitude = lngLatZoom[1];
        out.zoom = (float)lngLatZoom[2];
        out.rotation = 0.f;
        out.tilt = 0.f;
        return out;
    }

    /**
     * Smoothly animate over an arc to an updated camera position for the map view
     * @param position LngLat of the position to set
     * @param zoom Zoom level; lower values show more area
     * @param duration Time in milliseconds to ease to given zoom
     * @param speed If duration is 0, speed is used as factor to change the duration that is
     *              calculated for the distance of the flight path. (Recommended range 0.1 - 10.0)
     */
    public void flyTo(@NonNull final LngLat position, final float zoom, final int duration, final float speed) {
        checkPointer(mapPointer);
        boolean animated = (duration != 0);
        onRegionWillChange(animated);
        final float seconds = duration / 1000.f;
        // TODO: Appropriately handle call to `mapChangeListener.onRegionIsChanging` during camera animation updates.
        nativeFlyTo(mapPointer, position.longitude, position.latitude, zoom, seconds, speed);
        onRegionDidChange(animated);
    }

    /**
     * Set the camera type for the map view
     * @param type A {@code CameraType}
     */
    public void setCameraType(@NonNull final CameraType type) {
        checkPointer(mapPointer);
        nativeSetCameraType(mapPointer, type.ordinal());
    }

    /**
     * Get the camera type currently in use for the map view
     * @return A {@code CameraType}
     */
    public CameraType getCameraType() {
        checkPointer(mapPointer);
        return CameraType.values()[nativeGetCameraType(mapPointer)];
    }

    /**
     * Get the minimum zoom level of the map view. The default minimum zoom is 0.
     * @return The zoom level
     */
    public float getMinimumZoomLevel() {
        checkPointer(mapPointer);
        return nativeGetMinZoom(mapPointer);
    }

    /**
     * Set the minimum zoom level of the map view.
     *
     * Values less than the default minimum zoom will be clamped. Assigning a value greater than the
     * current maximum zoom will set the maximum zoom to this value.
     * @param minimumZoom The zoom level
     */
    public void setMinimumZoomLevel(float minimumZoom) {
        checkPointer(mapPointer);
        nativeSetMinZoom(mapPointer, minimumZoom);
    }

    /**
     * Get the maximum zoom level of the map view. The default maximum zoom is 20.5.
     * @return The zoom level
     */
    public float getMaximumZoomLevel() {
        checkPointer(mapPointer);
        return nativeGetMaxZoom(mapPointer);
    }

    /**
     * Set the maximum zoom level of the map view.
     *
     * Values greater than the default maximum zoom will be clamped. Assigning a value less than the
     * current minimum zoom will set the minimum zoom to this value.
     * @param maximumZoom The zoom level
     */
    public void setMaximumZoomLevel(float maximumZoom) {
        checkPointer(mapPointer);
        nativeSetMaxZoom(mapPointer, maximumZoom);
    }

    /**
     * Find the geographic coordinates corresponding to the given position on screen
     * @param screenPosition Position in pixels from the top-left corner of the map area
     * @return LngLat corresponding to the given point, or null if the screen position
     * does not intersect a geographic location (this can happen at high tilt angles).
     */
    @Nullable
    public LngLat screenPositionToLngLat(@NonNull final PointF screenPosition) {
        checkPointer(mapPointer);
        final double[] tmp = { screenPosition.x, screenPosition.y };
        if (nativeScreenPositionToLngLat(mapPointer, tmp)) {
            return new LngLat(tmp[0], tmp[1]);
        }
        return null;
    }

    /**
     * Find the position on screen corresponding to the given geographic coordinates
     * @param lngLat Geographic coordinates
     * @return Position in pixels from the top-left corner of the map area (the point
     * may not lie within the viewable screen area)
     */
    @NonNull
    public PointF lngLatToScreenPosition(@NonNull final LngLat lngLat) {
        checkPointer(mapPointer);
        final double[] tmp = { lngLat.longitude, lngLat.latitude };
        nativeLngLatToScreenPosition(mapPointer, tmp);
        return new PointF((float)tmp[0], (float)tmp[1]);
    }

    /**
     * Construct a collection of drawable map features.
     * @param name The name of the data collection. Once added to a map, features from this
     * {@code MapData} will be available from a data source with this name, just like a data source
     * specified in a scene file. You cannot create more than one data source with the same name.
     * If you call {@code addDataLayer} with the same name more than once, the same {@code MapData}
     * object will be returned.
     */
    public MapData addDataLayer(final String name) {
        return addDataLayer(name, false);
    }

    /**
     * Construct a collection of drawable map features.
     * @param name The name of the data collection. Once added to a map, features from this
     * @param generateCentroid boolean to control <a href=
     * "https://mapzen.com/documentation/tangram/sources/#generate_label_centroids"> label centroid
     * generation</a> for polygon geometry
     * {@code MapData} will be available from a data source with this name, just like a data source
     * specified in a scene file. You cannot create more than one data source with the same name.
     * If you call {@code addDataLayer} with the same name more than once, the same {@code MapData}
     * object will be returned.
     */
    @NonNull
    public MapData addDataLayer(final String name, final boolean generateCentroid) {
        MapData mapData = clientTileSources.get(name);
        if (mapData != null) {
            return mapData;
        }
        checkPointer(mapPointer);
        final long pointer = nativeAddTileSource(mapPointer, name, generateCentroid);
        if (pointer <= 0) {
            throw new RuntimeException("Unable to create new data source");
        }
        mapData = new MapData(name, pointer, this);
        clientTileSources.put(name, mapData);
        return mapData;
    }

    /**
     * For package-internal use only; remove a {@code MapData} from this map
     * @param mapData The {@code MapData} to remove
     */
    void removeDataLayer(@NonNull final MapData mapData) {
        clientTileSources.remove(mapData.name);
        checkPointer(mapPointer);
        checkPointer(mapData.pointer);
        nativeRemoveTileSource(mapPointer, mapData.pointer);
    }

    /**
     * Manually trigger a re-draw of the map view
     *
     * Typically this does not need to be called from outside Tangram, see {@link #setRenderMode(int)}.
     */
    @Keep
    public void requestRender() {
        mapView.requestRender();
    }

    /**
     * Set whether the map view re-draws continuously
     *
     * Typically this does not need to be called from outside Tangram. The map automatically re-renders when the view
     * changes or when any animation in the map requires rendering.
     * @param renderMode Either 1, to render continuously, or 0, to render only when needed.
     */
    @Keep
    public void setRenderMode(@IntRange(from=0,to=1) final int renderMode) {
        mapView.setRenderMode(renderMode);
    }

    /**
     * Set a responder for tap gestures
     * @param responder TapResponder to call
     */
    public void setTapResponder(@Nullable final TouchInput.TapResponder responder) {
        touchInput.setTapResponder(new TouchInput.TapResponder() {
            @Override
            public boolean onSingleTapUp(final float x, final float y) {
                return responder != null && responder.onSingleTapUp(x, y);
            }

            @Override
            public boolean onSingleTapConfirmed(final float x, final float y) {
                return responder != null && responder.onSingleTapConfirmed(x, y);
            }
        });
    }

    /**
     * Set a responder for double-tap gestures
     * @param responder DoubleTapResponder to call
     */
    public void setDoubleTapResponder(@Nullable final TouchInput.DoubleTapResponder responder) {
        touchInput.setDoubleTapResponder(new TouchInput.DoubleTapResponder() {
            @Override
            public boolean onDoubleTap(final float x, final float y) {
                return responder != null && responder.onDoubleTap(x, y);
            }
        });
    }

    /**
     * Set a responder for long press gestures
     * @param responder LongPressResponder to call
     */
    public void setLongPressResponder(@Nullable final TouchInput.LongPressResponder responder) {
        touchInput.setLongPressResponder(new TouchInput.LongPressResponder() {
            @Override
            public void onLongPress(final float x, final float y) {
                if (responder != null) {
                    responder.onLongPress(x, y);
                }
            }
        });
    }

    /**
     * Set a responder for pan gestures
     * @param responder PanResponder to call; if onPan returns true, normal panning behavior will not occur
     */
    public void setPanResponder(@Nullable final TouchInput.PanResponder responder) {
        touchInput.setPanResponder(new TouchInput.PanResponder() {
            @Override
            public boolean onPanBegin() {
                if (responder == null || !responder.onPanBegin()) {
                    if (panGesturesEnabled) {
                        onRegionWillChange(true);
                    }
                }
                return true;
            }

            @Override
            public boolean onPan(final float startX, final float startY, final float endX, final float endY) {
                if (responder == null || !responder.onPan(startX, startY, endX, endY)) {
                    if (panGesturesEnabled) {
                        onRegionIsChanging();
                        nativeHandlePanGesture(mapPointer, startX, startY, endX, endY);
                    }
                }
                return true;
            }

            @Override
            public boolean onPanEnd() {
                if (responder == null || !responder.onPanEnd()) {
                    if (panGesturesEnabled) {
                        onRegionDidChange(true);
                    }
                }
                return true;
            }

            @Override
            public boolean onFling(final float posX, final float posY, final float velocityX, final float velocityY) {
                if (responder == null || !responder.onFling(posX, posY, velocityX, velocityY)) {
                    if (panGesturesEnabled) {
                        nativeHandleFlingGesture(mapPointer, posX, posY, velocityX, velocityY);
                    }
                }
                return true;
            }

            @Override
            public boolean onCancelFling() {
                if (responder == null || !responder.onCancelFling()) {
                    if (panGesturesEnabled) {
                        cancelCameraAnimation();
                        // TODO: Ideally should call onRegionDidChange if map state "InChanging" - VT(09/10/2018)
                    }
                }
                return true;
            }
        });
    }

    /**
     * Set a responder for rotate gestures
     * @param responder RotateResponder to call; if onRotate returns true, normal rotation behavior will not occur
     */
    public void setRotateResponder(@Nullable final TouchInput.RotateResponder responder) {
        touchInput.setRotateResponder(new TouchInput.RotateResponder() {
            @Override
            public boolean onRotateBegin() {
                if (responder == null || !responder.onRotateBegin()) {
                    if (rotateGesturesEnabled) {
                        onRegionWillChange(true);
                    }
                }
                return true;
            }

            @Override
            public boolean onRotate(final float x, final float y, final float rotation) {
                if (responder == null || !responder.onRotate(x, y, rotation)) {
                    if (rotateGesturesEnabled) {
                        onRegionIsChanging();
                        nativeHandleRotateGesture(mapPointer, x, y, rotation);
                    }
                }
                return true;
            }

            @Override
            public boolean onRotateEnd() {
                if (responder == null || !responder.onRotateEnd()) {
                    if (rotateGesturesEnabled) {
                        onRegionDidChange(true);
                    }
                }
                return true;
            }
        });
    }

    /**
     * Set a responder for scale gestures
     * @param responder ScaleResponder to call; if onScale returns true, normal scaling behavior will not occur
     */
    public void setScaleResponder(@Nullable final TouchInput.ScaleResponder responder) {
        touchInput.setScaleResponder(new TouchInput.ScaleResponder() {
            @Override
            public boolean onScaleBegin() {
                if (responder == null || !responder.onScaleBegin()) {
                    if (zoomGesturesEnabled) {
                        onRegionWillChange(true);
                    }
                }
                return true;
            }

            @Override
            public boolean onScale(final float x, final float y, final float scale, final float velocity) {
                if (responder == null || !responder.onScale(x, y, scale, velocity)) {
                    if (zoomGesturesEnabled) {
                        onRegionIsChanging();
                        nativeHandlePinchGesture(mapPointer, x, y, scale, velocity);
                    }
                }
                return true;
            }

            @Override
            public boolean onScaleEnd() {
                if (responder == null || !responder.onScaleEnd()) {
                    if (zoomGesturesEnabled) {
                        onRegionDidChange(true);
                    }
                }
                return true;
            }
        });
    }

    /**
     * Set a responder for shove (vertical two-finger drag) gestures
     * @param responder ShoveResponder to call; if onShove returns true, normal tilting behavior will not occur
     */
    public void setShoveResponder(@Nullable final TouchInput.ShoveResponder responder) {
        touchInput.setShoveResponder(new TouchInput.ShoveResponder() {
            @Override
            public boolean onShoveBegin() {
                if (responder == null || !responder.onShoveBegin()) {
                    if (tiltGesturesEnabled) {
                        onRegionWillChange(true);
                    }
                }
                return true;
            }

            @Override
            public boolean onShove(final float distance) {
                if (responder == null || !responder.onShove(distance)) {
                    if (tiltGesturesEnabled) {
                        onRegionIsChanging();
                        nativeHandleShoveGesture(mapPointer, distance);
                    }
                }
                return true;
            }

            @Override
            public boolean onShoveEnd() {
                if (responder == null || !responder.onShoveEnd()) {
                    if (tiltGesturesEnabled) {
                        onRegionDidChange(true);
                    }
                }
                return true;
            }
        });
    }

    /**
     * Set whether the gesture {@code second} can be recognized while {@code first} is in progress
     * @param first Initial gesture type
     * @param second Subsequent gesture type
     * @param allowed True if {@code second} should be recognized, else false
     */
    public void setSimultaneousGestureAllowed(final Gestures first, final Gestures second, final boolean allowed) {
        touchInput.setSimultaneousDetectionAllowed(first, second, allowed);
    }

    /**
     * Get whether the gesture {@code second} can be recognized while {@code first} is in progress
     * @param first Initial gesture type
     * @param second Subsequent gesture type
     * @return True if {@code second} will be recognized, else false
     */
    public boolean isSimultaneousGestureAllowed(final Gestures first, final Gestures second) {
        return touchInput.isSimultaneousDetectionAllowed(first, second);
    }

    /**
     * Set whether the user can move the map view with panning gestures. This is true by default.
     *
     * When this is false you can still move the map view programmatically.
     * @param enabled If true, panning is enabled.
     */
    public void setPanGesturesEnabled(boolean enabled) {
        panGesturesEnabled = enabled;
    }

    /**
     * Get whether the user can move the map view with panning gestures.
     * @return True if panning is enabled.
     */
    public boolean isPanGesturesEnabled() {
        return panGesturesEnabled;
    }

    /**
     * Set whether the user can zoom the map view with pinch and double-tap-move gestures. This is
     * true by default.
     *
     * When this is false you can still zoom the map view programmatically.
     * @param enabled If true, zooming with gestures is enabled.
     */
    public void setZoomGesturesEnabled(boolean enabled) {
        zoomGesturesEnabled = enabled;
    }

    /**
     * Get whether the user can zoom the map view with pinch and double-tap-zoom gestures.
     * @return True if zooming with gestures is enabled.
     */
    public boolean isZoomGesturesEnabled() {
        return zoomGesturesEnabled;
    }

    /**
     * Set whether the user can rotate the map view with gestures. This is true by default.
     *
     * When this is false you can still rotate the map view programmatically.
     * @param enabled If true, rotating with gestures is enabled.
     */
    public void setRotateGesturesEnabled(boolean enabled) {
        rotateGesturesEnabled = enabled;
    }

    /**
     * Get whether the user can rotate the map view with gestures.
     * @return True if rotating with gestures is enabled.
     */
    public boolean isRotateGesturesEnabled() {
        return rotateGesturesEnabled;
    }

    /**
     * Set whether the user can tilt the map with shove gestures. This is true by default.
     *
     * When this is false you can still tilt the map view programmatically.
     * @param enabled If true, tilting with gestures is enabled.
     */
    public void setTiltGesturesEnabled(boolean enabled) {
        tiltGesturesEnabled = enabled;
    }

    /**
     * Get whether the user can rotate the map with with shove gestures.
     * @return True if tilting with gestures is enabled.
     */
    public boolean isTiltGesturesEnabled() {
        return tiltGesturesEnabled;
    }

    /**
     * Set whether the user can change the map with all gestures.
     * @param enabled If true, all gestures are enabled. If false, all gestures are disabled.
     */
    public void setAllGesturesEnabled(boolean enabled) {
        setPanGesturesEnabled(enabled);
        setZoomGesturesEnabled(enabled);
        setRotateGesturesEnabled(enabled);
        setTiltGesturesEnabled(enabled);
    }

    /**
     * Set the radius to use when picking features on the map. The default radius is 0.5 dp.
     * @param radius The radius in dp (density-independent pixels).
     */
    public void setPickRadius(final float radius) {
        checkPointer(mapPointer);
        nativeSetPickRadius(mapPointer, radius);
    }

    /**
     * Set a listener for feature pick events
     * @param listener The {@link FeaturePickListener} to call
     */
    public void setFeaturePickListener(@Nullable final FeaturePickListener listener) {
        featurePickListener = (listener == null) ? null : new FeaturePickListener() {
            @Override
            public void onFeaturePick(final Map<String, String> properties, final float positionX, final float positionY) {
                uiThreadHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onFeaturePick(properties, positionX, positionY);
                    }
                });
            }
        };
    }

    /**
     * Set a listener for scene update error statuses
     * @param listener The {@link SceneLoadListener} to call after scene has loaded
     */
    public void setSceneLoadListener(@Nullable final SceneLoadListener listener) {
        sceneLoadListener = listener;
    }

    /**
     * Set a listener for label pick events
     * @param listener The {@link LabelPickListener} to call
     */
    public void setLabelPickListener(@Nullable final LabelPickListener listener) {
        labelPickListener = (listener == null) ? null : new LabelPickListener() {
            @Override
            public void onLabelPick(final LabelPickResult labelPickResult, final float positionX, final float positionY) {
                uiThreadHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onLabelPick(labelPickResult, positionX, positionY);
                    }
                });
            }
        };
    }

    /**
     * Set a listener for marker pick events
     * @param listener The {@link MarkerPickListener} to call
     */
    public void setMarkerPickListener(@Nullable final MarkerPickListener listener) {
        markerPickListener = (listener == null) ? null : new MarkerPickListener() {
            @Override
            public void onMarkerPick(final MarkerPickResult markerPickResult, final float positionX, final float positionY) {
                uiThreadHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onMarkerPick(markerPickResult, positionX, positionY);
                    }
                });
            }
        };
    }

    /**
     * Query the map for geometry features at the given screen coordinates; results will be returned
     * in a callback to the object set by {@link #setFeaturePickListener(FeaturePickListener)}
     * @param posX The horizontal screen coordinate
     * @param posY The vertical screen coordinate
     */
    public void pickFeature(final float posX, final float posY) {
        if (featurePickListener != null) {
            checkPointer(mapPointer);
            nativePickFeature(mapPointer, posX, posY, featurePickListener);
        }
    }

    /**
     * Query the map for labeled features at the given screen coordinates; results will be returned
     * in a callback to the object set by {@link #setLabelPickListener(LabelPickListener)}
     * @param posX The horizontal screen coordinate
     * @param posY The vertical screen coordinate
     */
    public void pickLabel(final float posX, final float posY) {
        if (labelPickListener != null) {
            checkPointer(mapPointer);
            nativePickLabel(mapPointer, posX, posY, labelPickListener);
        }
    }

    /**
     * Query the map for a {@link Marker} at the given screen coordinates; results will be returned
     * in a callback to the object set by {@link #setMarkerPickListener(MarkerPickListener)}
     * @param posX The horizontal screen coordinate
     * @param posY The vertical screen coordinate
     */
    public void pickMarker(final float posX, final float posY) {
        if (markerPickListener != null) {
            checkPointer(mapPointer);
            nativePickMarker(this, mapPointer, posX, posY, markerPickListener);
        }
    }

    /**
     * Adds a {@link Marker} to the map which can be used to dynamically add points and polylines
     * to the map.
     * @return Newly created {@link Marker} object.
     */
    @NonNull
    public Marker addMarker() {
        checkPointer(mapPointer);
        final long markerId = nativeMarkerAdd(mapPointer);

        final Marker marker = new Marker(mapView.getContext(), markerId, this);
        markers.put(markerId, marker);

        return marker;
    }

    /**
     * Removes the passed in {@link Marker} from the map.
     * Alias of Marker{@link #removeMarker(long)}
     * @param marker to remove from the map.
     * @return whether or not the marker was removed
     */
    public boolean removeMarker(@NonNull final Marker marker) {
        return this.removeMarker(marker.getMarkerId());
    }

    /**
     * Removes the passed in {@link Marker} from the map.
     * @param markerId to remove from the map.
     * @return whether or not the marker was removed
     */
    public boolean removeMarker(final long markerId) {
        checkPointer(mapPointer);
        checkId(markerId);
        markers.remove(markerId);
        return nativeMarkerRemove(mapPointer, markerId);
    }

    /**
     * Remove all the {@link Marker} objects from the map.
     */
    public void removeAllMarkers() {
        checkPointer(mapPointer);
        nativeMarkerRemoveAll(mapPointer);

        // Invalidate all markers so their ids are unusable
        for (int i = 0; i < markers.size(); i++) {
            final Marker marker = markers.valueAt(i);
            marker.invalidate();
        }

        markers.clear();
    }

    /**
     * Set a listener for map change events
     * @param listener The {@link MapChangeListener} to call when the map change events occur due to camera updates or user interaction
     */
    public void setMapChangeListener(@Nullable final MapChangeListener listener) {
        final Runnable regionIsChanging = (listener == null) ? null : new Runnable() {
            @Override
            public void run() {
                listener.onRegionIsChanging();
            }
        };

        mapChangeListener = (listener == null) ? null : new MapChangeListener() {
            @Override
            public void onViewComplete() {
                uiThreadHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onViewComplete();
                    }
                });
            }

            @Override
            public void onRegionWillChange(final boolean animated) {
                uiThreadHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onRegionWillChange(animated);
                    }
                });
            }

            @Override
            public void onRegionIsChanging() {
                uiThreadHandler.post(regionIsChanging);
            }

            @Override
            public void onRegionDidChange(final boolean animated) {
                uiThreadHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onRegionDidChange(animated);
                    }
                });
            }
        };
    }

    //Convenience member functions
    private void onRegionWillChange(boolean animated) {
        if (mapChangeListener != null) {
            mapChangeListener.onRegionWillChange(animated);
        }
    }

    private void onRegionDidChange(boolean animated) {
        if (mapChangeListener != null) {
            mapChangeListener.onRegionDidChange(animated);
        }
    }

    private void onRegionIsChanging() {
        if (mapChangeListener != null) {
            mapChangeListener.onRegionIsChanging();
        }
    }

    /**
     * Enqueue a Runnable to be executed synchronously on the rendering thread
     * @param r Runnable to run
     */
    public void queueEvent(@NonNull final Runnable r) {
        mapView.queueEvent(r);
    }

    /**
     * Make a debugging feature active or inactive
     * @param flag The feature to set
     * @param on True to activate the feature, false to deactivate
     */
    public void setDebugFlag(@NonNull final DebugFlag flag, final boolean on) {
        nativeSetDebugFlag(flag.ordinal(), on);
    }

    /**
     * Set whether the OpenGL state will be cached between subsequent frames. This improves
     * rendering efficiency, but can cause errors if your application code makes OpenGL calls.
     * @param use Whether to use a cached OpenGL state; false by default
     */
    public void useCachedGlState(final boolean use) {
        checkPointer(mapPointer);
        nativeUseCachedGlState(mapPointer, use);
    }

    /**
     * Sets an opaque background color used as default color when a scene is being loaded
     * @param red red component of the background color
     * @param green green component of the background color
     * @param blue blue component of the background color
     */
    public void setDefaultBackgroundColor(final float red, final float green, final float blue) {
        checkPointer(mapPointer);
        nativeSetDefaultBackgroundColor(mapPointer, red, green, blue);
    }

    // Package private methods
    // =======================

    void onLowMemory() {
        checkPointer(mapPointer);
        nativeOnLowMemory(mapPointer);
    }

    void removeTileSource(final long sourcePtr) {
        checkPointer(mapPointer);
        checkPointer(sourcePtr);
        nativeRemoveTileSource(mapPointer, sourcePtr);
    }

    void clearTileSource(final long sourcePtr) {
        checkPointer(mapPointer);
        checkPointer(sourcePtr);
        nativeClearTileSource(mapPointer, sourcePtr);
    }

    void addFeature(final long sourcePtr, final double[] coordinates, final int[] rings, final String[] properties) {
        checkPointer(mapPointer);
        checkPointer(sourcePtr);
        nativeAddFeature(mapPointer, sourcePtr, coordinates, rings, properties);
    }

    void addGeoJson(final long sourcePtr, final String geoJson) {
        checkPointer(mapPointer);
        checkPointer(sourcePtr);
        nativeAddGeoJson(mapPointer, sourcePtr, geoJson);
    }

    void checkPointer(final long ptr) {
        if (ptr <= 0) {
            throw new RuntimeException("Tried to perform an operation on an invalid pointer! This means you may have used an object that has been disposed and is no longer valid.");
        }
    }

    void checkId(final long id) {
        if (id <= 0) {
            throw new RuntimeException("Tried to perform an operation on an invalid id! This means you may have used an object that has been disposed and is no longer valid.");
        }
    }

    @Nullable
    private String[] bundleSceneUpdates(@Nullable final List<SceneUpdate> sceneUpdates) {
        if (sceneUpdates == null) {
            return null;
        }

        final String[] updateStrings = new String[sceneUpdates.size() * 2];
        int index = 0;
        for (final SceneUpdate sceneUpdate : sceneUpdates) {
            updateStrings[index++] = sceneUpdate.getPath();
            updateStrings[index++] = sceneUpdate.getValue();
        }
        return updateStrings;
    }

    boolean setMarkerStylingFromString(final long markerId, final String styleString) {
        checkPointer(mapPointer);
        checkId(markerId);
        return nativeMarkerSetStylingFromString(mapPointer, markerId, styleString);
    }

    boolean setMarkerStylingFromPath(final long markerId, final String path) {
        checkPointer(mapPointer);
        checkId(markerId);
        return nativeMarkerSetStylingFromPath(mapPointer, markerId, path);
    }

    boolean setMarkerBitmap(final long markerId, final int width, final int height, final int[] data) {
        checkPointer(mapPointer);
        checkId(markerId);
        return nativeMarkerSetBitmap(mapPointer, markerId, width, height, data);
    }

    boolean setMarkerPoint(final long markerId, final double lng, final double lat) {
        checkPointer(mapPointer);
        checkId(markerId);
        return nativeMarkerSetPoint(mapPointer, markerId, lng, lat);
    }

    boolean setMarkerPointEased(final long markerId, final double lng, final double lat, final int duration,
                                @NonNull final EaseType ease) {
        checkPointer(mapPointer);
        checkId(markerId);
        final float seconds = duration / 1000.f;
        return nativeMarkerSetPointEased(mapPointer, markerId, lng, lat, seconds, ease.ordinal());
    }

    boolean setMarkerPolyline(final long markerId, final double[] coordinates, final int count) {
        checkPointer(mapPointer);
        checkId(markerId);
        return nativeMarkerSetPolyline(mapPointer, markerId, coordinates, count);
    }

    boolean setMarkerPolygon(final long markerId, final double[] coordinates, final int[] rings, final int count) {
        checkPointer(mapPointer);
        checkId(markerId);
        return nativeMarkerSetPolygon(mapPointer, markerId, coordinates, rings, count);
    }

    boolean setMarkerVisible(final long markerId, final boolean visible) {
        checkPointer(mapPointer);
        checkId(markerId);
        return nativeMarkerSetVisible(mapPointer, markerId, visible);
    }

    boolean setMarkerDrawOrder(final long markerId, final int drawOrder) {
        checkPointer(mapPointer);
        checkId(markerId);
        return nativeMarkerSetDrawOrder(mapPointer, markerId, drawOrder);
    }

    @Keep
    Marker markerById(final long markerId) {
        return markers.get(markerId);
    }

    // Native methods
    // ==============

    private synchronized native void nativeOnLowMemory(long mapPtr);
    private synchronized native long nativeInit(MapController instance, AssetManager assetManager);
    private synchronized native void nativeDispose(long mapPtr);
    private synchronized native int nativeLoadScene(long mapPtr, String path, String[] updateStrings);
    private synchronized native int nativeLoadSceneAsync(long mapPtr, String path, String[] updateStrings);
    private synchronized native int nativeLoadSceneYaml(long mapPtr, String yaml, String resourceRoot, String[] updateStrings);
    private synchronized native int nativeLoadSceneYamlAsync(long mapPtr, String yaml, String resourceRoot, String[] updateStrings);
    private synchronized native void nativeSetupGL(long mapPtr);
    private synchronized native void nativeResize(long mapPtr, int width, int height);
    private synchronized native boolean nativeUpdate(long mapPtr, float dt);
    private synchronized native void nativeRender(long mapPtr);
    private synchronized native void nativeGetCameraPosition(long mapPtr, double[] lonLatOut, float[] zoomRotationTiltOut);
    private synchronized native void nativeUpdateCameraPosition(long mapPtr, int set, double lon, double lat, float zoom, float zoomBy,
                                                                float rotation, float rotateBy, float tilt, float tiltBy,
                                                                double b1lon, double b1lat, double b2lon, double b2lat, int[] padding,
                                                                float duration, int ease);
    private synchronized native void nativeFlyTo(long mapPtr, double lon, double lat, float zoom, float duration, float speed);
    private synchronized native void nativeGetEnclosingCameraPosition(long mapPtr, double aLng, double aLat, double bLng, double bLat, int[] buffer, double[] lngLatZoom);
    private synchronized native void nativeCancelCameraAnimation(long mapPtr);
    private synchronized native boolean nativeScreenPositionToLngLat(long mapPtr, double[] coordinates);
    private synchronized native boolean nativeLngLatToScreenPosition(long mapPtr, double[] coordinates);
    private synchronized native void nativeSetPixelScale(long mapPtr, float scale);
    private synchronized native void nativeSetCameraType(long mapPtr, int type);
    private synchronized native int nativeGetCameraType(long mapPtr);
    private synchronized native float nativeGetMinZoom(long mapPtr);
    private synchronized native void nativeSetMinZoom(long mapPtr, float minZoom);
    private synchronized native float nativeGetMaxZoom(long mapPtr);
    private synchronized native void nativeSetMaxZoom(long mapPtr, float maxZoom);
    private synchronized native void nativeHandleTapGesture(long mapPtr, float posX, float posY);
    private synchronized native void nativeHandleDoubleTapGesture(long mapPtr, float posX, float posY);
    private synchronized native void nativeHandlePanGesture(long mapPtr, float startX, float startY, float endX, float endY);
    private synchronized native void nativeHandleFlingGesture(long mapPtr, float posX, float posY, float velocityX, float velocityY);
    private synchronized native void nativeHandlePinchGesture(long mapPtr, float posX, float posY, float scale, float velocity);
    private synchronized native void nativeHandleRotateGesture(long mapPtr, float posX, float posY, float rotation);
    private synchronized native void nativeHandleShoveGesture(long mapPtr, float distance);
    private synchronized native int nativeUpdateScene(long mapPtr, String[] updateStrings);
    private synchronized native void nativeSetPickRadius(long mapPtr, float radius);
    private synchronized native void nativePickFeature(long mapPtr, float posX, float posY, FeaturePickListener listener);
    private synchronized native void nativePickLabel(long mapPtr, float posX, float posY, LabelPickListener listener);
    private synchronized native void nativePickMarker(MapController instance, long mapPtr, float posX, float posY, MarkerPickListener listener);
    private synchronized native long nativeMarkerAdd(long mapPtr);
    private synchronized native boolean nativeMarkerRemove(long mapPtr, long markerID);
    private synchronized native boolean nativeMarkerSetStylingFromString(long mapPtr, long markerID, String styling);
    private synchronized native boolean nativeMarkerSetStylingFromPath(long mapPtr, long markerID, String path);
    private synchronized native boolean nativeMarkerSetBitmap(long mapPtr, long markerID, int width, int height, int[] data);
    private synchronized native boolean nativeMarkerSetPoint(long mapPtr, long markerID, double lng, double lat);
    private synchronized native boolean nativeMarkerSetPointEased(long mapPtr, long markerID, double lng, double lat, float duration, int ease);
    private synchronized native boolean nativeMarkerSetPolyline(long mapPtr, long markerID, double[] coordinates, int count);
    private synchronized native boolean nativeMarkerSetPolygon(long mapPtr, long markerID, double[] coordinates, int[] rings, int count);
    private synchronized native boolean nativeMarkerSetVisible(long mapPtr, long markerID, boolean visible);
    private synchronized native boolean nativeMarkerSetDrawOrder(long mapPtr, long markerID, int drawOrder);
    private synchronized native void nativeMarkerRemoveAll(long mapPtr);

    private synchronized native void nativeUseCachedGlState(long mapPtr, boolean use);
    private synchronized native void nativeCaptureSnapshot(long mapPtr, int[] buffer);

    private synchronized native void nativeSetDefaultBackgroundColor(long mapPtr, float r, float g, float b);

    private native void nativeOnUrlComplete(long mapPtr, long requestHandle, byte[] rawDataBytes, String errorMessage);

    synchronized native long nativeAddTileSource(long mapPtr, String name, boolean generateCentroid);
    synchronized native void nativeRemoveTileSource(long mapPtr, long sourcePtr);
    synchronized native void nativeClearTileSource(long mapPtr, long sourcePtr);
    synchronized native void nativeAddFeature(long mapPtr, long sourcePtr, double[] coordinates, int[] rings, String[] properties);
    synchronized native void nativeAddGeoJson(long mapPtr, long sourcePtr, String geoJson);

    native void nativeSetDebugFlag(int flag, boolean on);

    // Private members
    // ===============

    private long mapPointer;
    private long time = System.nanoTime();
    private GLSurfaceView mapView;
    private AssetManager assetManager;
    private TouchInput touchInput;
    private FontFileParser fontFileParser;
    private DisplayMetrics displayMetrics = new DisplayMetrics();
    private HttpHandler httpHandler;
    private LongSparseArray<Object> httpRequestHandles = new LongSparseArray<>();
    private MapChangeListener mapChangeListener;
    private FeaturePickListener featurePickListener;
    private SceneLoadListener sceneLoadListener;
    private LabelPickListener labelPickListener;
    private MarkerPickListener markerPickListener;
    private FrameCaptureCallback frameCaptureCallback;
    private boolean frameCaptureAwaitCompleteView;
    private Map<String, MapData> clientTileSources;
    private LongSparseArray<Marker> markers;
    private Handler uiThreadHandler;
    private CameraAnimationCallback cameraAnimationCallback;
    private boolean panGesturesEnabled = true;
    private boolean zoomGesturesEnabled = true;
    private boolean rotateGesturesEnabled = true;
    private boolean tiltGesturesEnabled = true;
    private boolean isGLRendererSet = false;

    // GLSurfaceView.Renderer methods
    // ==============================

    @Override
    public void onDrawFrame(final GL10 gl) {
        final long newTime = System.nanoTime();
        final float delta = (newTime - time) / 1000000000.0f;
        time = newTime;

        if (mapPointer <= 0) {
            // No native instance is initialized, so stop here. This can happen during Activity
            // shutdown when the map has been disposed but the View hasn't been destroyed yet.
            return;
        }

        boolean viewComplete;
        synchronized(this) {
            viewComplete = nativeUpdate(mapPointer, delta);
            nativeRender(mapPointer);
        }

        if (viewComplete) {
            if (mapChangeListener != null) {
                mapChangeListener.onViewComplete();
            }
        }
        if (frameCaptureCallback != null) {
            if (!frameCaptureAwaitCompleteView || viewComplete) {
                frameCaptureCallback.onCaptured(capture());
                frameCaptureCallback = null;
            }
        }
    }

    @Override
    public void onSurfaceChanged(final GL10 gl, final int width, final int height) {
        if (mapPointer <= 0) {
            // No native instance is initialized, so stop here. This can happen during Activity
            // shutdown when the map has been disposed but the View hasn't been destroyed yet.
            return;
        }

        nativeSetPixelScale(mapPointer, displayMetrics.density);
        nativeResize(mapPointer, width, height);
    }

    @Override
    public void onSurfaceCreated(final GL10 gl, final EGLConfig config) {
        if (mapPointer <= 0) {
            // No native instance is initialized, so stop here. This can happen during Activity
            // shutdown when the map has been disposed but the View hasn't been destroyed yet.
            return;
        }

        nativeSetupGL(mapPointer);
    }

    // Networking methods
    // ==================

    private void syncRemoveHttpHandle(final long requestHandle) {
        synchronized(this) {
            httpRequestHandles.remove(requestHandle);
        }
    }

    @Keep
    void cancelUrlRequest(final long requestHandle) {
        if (httpHandler != null) {
            Object request;
            synchronized(this) {
                request = httpRequestHandles.get(requestHandle);
                httpRequestHandles.remove(requestHandle);
            }
            if (request != null) {
                httpHandler.cancelRequest(request);
            }
        }
    }

    @Keep
    void startUrlRequest(@NonNull final String url, final long requestHandle) {
        if (httpHandler == null) {
            return;
        }

        final HttpHandler.Callback callback = new HttpHandler.Callback() {
            @Override
            public void onFailure(@Nullable final IOException e) {
                String msg = (e == null) ? "" : e.getMessage();
                nativeOnUrlComplete(mapPointer, requestHandle, null, msg);
                syncRemoveHttpHandle(requestHandle);
            }

            @Override
            public void onResponse(final int code, @Nullable final byte[] rawDataBytes) {
                if (code >= 200 && code < 300) {
                    nativeOnUrlComplete(mapPointer, requestHandle, rawDataBytes, null);
                }
                else {
                    nativeOnUrlComplete(mapPointer, requestHandle, null, "Unexpected response code: " + code + " for URL: " + url);
                }
                syncRemoveHttpHandle(requestHandle);
            }

            @Override
            public void onCancel() {
                nativeOnUrlComplete(mapPointer, requestHandle, null, null);
                syncRemoveHttpHandle(requestHandle);
            }
        };

        Object request = httpHandler.startRequest(url, callback);
        if (request != null) {
            synchronized (this) {
                httpRequestHandles.put(requestHandle, request);
            }
        }
    }

    // Called from JNI on worker or render-thread.
    @Keep
    void sceneReadyCallback(final int sceneId, final SceneError error) {

        final SceneLoadListener cb = sceneLoadListener;
        if (cb != null) {
            uiThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    cb.onSceneReady(sceneId, error);
                }
            });
        }
    }

    // Called from JNI on worker or render-thread.
    @Keep
    void cameraAnimationCallback(final boolean finished) {

        final CameraAnimationCallback cb = cameraAnimationCallback;
        if (cb != null) {
            cameraAnimationCallback = null;

            uiThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (finished) {
                        cb.onFinish();
                    } else {
                        cb.onCancel();
                    }
                }
            });
        }
    }

    // Font Fetching
    // =============
    @Keep
    String getFontFilePath(final String key) {
        return fontFileParser.getFontFile(key);
    }

    @Keep
    String getFontFallbackFilePath(final int importance, final int weightHint) {
        return fontFileParser.getFontFallback(importance, weightHint);
    }

}
