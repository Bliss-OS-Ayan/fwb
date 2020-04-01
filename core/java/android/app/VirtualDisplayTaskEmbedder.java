/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app;

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
import static android.view.Display.INVALID_DISPLAY;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.IWindow;
import android.view.IWindowManager;
import android.view.IWindowSession;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.WindowManagerGlobal;
import android.view.inputmethod.InputMethodManager;

import java.util.List;

/**
 * A component which handles embedded display of tasks within another window. The embedded task can
 * be presented using the SurfaceControl provided from {@link #getSurfaceControl()}.
 *
 * @hide
 */
public class VirtualDisplayTaskEmbedder extends TaskEmbedder {
    private static final String TAG = "VirDispTaskEmbedder";
    private static final String DISPLAY_NAME = "TaskVirtualDisplay";

    // For Virtual Displays
    private int mDisplayDensityDpi;
    private final boolean mSingleTaskInstance;
    private VirtualDisplay mVirtualDisplay;
    private Insets mForwardedInsets;
    private DisplayMetrics mTmpDisplayMetrics;

    /**
     * Constructs a new TaskEmbedder.
     *
     * @param context the context
     * @param host the host for this embedded task
     * @param singleTaskInstance whether to apply a single-task constraint to this container,
     *                           only applicable if virtual displays are used
     */
    VirtualDisplayTaskEmbedder(Context context, VirtualDisplayTaskEmbedder.Host host,
            boolean singleTaskInstance) {
        super(context, host);
        mSingleTaskInstance = singleTaskInstance;
    }

    @Override
    public TaskStackListener createTaskStackListener() {
        return new TaskStackListenerImpl();
    }

    /**
     * Whether this container has been initialized.
     *
     * @return true if initialized
     */
    @Override
    public boolean isInitialized() {
        return mVirtualDisplay != null;
    }

    @Override
    public boolean onInitialize() {
        final DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
        mDisplayDensityDpi = getBaseDisplayDensity();
        mVirtualDisplay = displayManager.createVirtualDisplay(
                DISPLAY_NAME + "@" + System.identityHashCode(this), mHost.getWidth(),
                mHost.getHeight(), mDisplayDensityDpi, null,
                VIRTUAL_DISPLAY_FLAG_PUBLIC | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                        | VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL);

        if (mVirtualDisplay == null) {
            Log.e(TAG, "Failed to initialize TaskEmbedder");
            return false;
        }

        try {
            // TODO: Find a way to consolidate these calls to the server.
            final int displayId = getDisplayId();
            final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
            WindowManagerGlobal.getWindowSession().reparentDisplayContent(
                    mHost.getWindow(), mSurfaceControl, displayId);
            wm.dontOverrideDisplayInfo(displayId);
            if (mSingleTaskInstance) {
                mContext.getSystemService(ActivityTaskManager.class)
                        .setDisplayToSingleTaskInstance(displayId);
            }
            setForwardedInsets(mForwardedInsets);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }

        if (mHost.getWindow() != null) {
            updateLocationAndTapExcludeRegion();
        }
        return true;
    }

    @Override
    protected boolean onRelease() {
        // Clear activity view geometry for IME on this display
        clearActivityViewGeometryForIme();

        // Clear tap-exclude region (if any) for this window.
        clearTapExcludeRegion();

        if (isInitialized()) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
            return true;
        }
        return false;
    }

    /**
     * Starts presentation of tasks in this container.
     */
    @Override
    public void start() {
        if (isInitialized()) {
            mVirtualDisplay.setDisplayState(true);
            updateLocationAndTapExcludeRegion();
        }
    }

    /**
     * Stops presentation of tasks in this container.
     */
    @Override
    public void stop() {
        if (isInitialized()) {
            mVirtualDisplay.setDisplayState(false);
            clearActivityViewGeometryForIme();
            clearTapExcludeRegion();
        }
    }

    /**
     * This should be called whenever the position or size of the surface changes
     * or if touchable areas above the surface are added or removed.
     */
    @Override
    public void notifyBoundsChanged() {
        updateLocationAndTapExcludeRegion();
    }

    /**
     * Called to update the dimensions whenever the host size changes.
     *
     * @param width the new width of the surface
     * @param height the new height of the surface
     */
    @Override
    public void resizeTask(int width, int height) {
        mDisplayDensityDpi = getBaseDisplayDensity();
        if (isInitialized()) {
            mVirtualDisplay.resize(width, height, mDisplayDensityDpi);
        }
    }

    /**
     * Injects a pair of down/up key events with keycode {@link KeyEvent#KEYCODE_BACK} to the
     * virtual display.
     */
    @Override
    public void performBackPress() {
        if (!isInitialized()) {
            return;
        }
        final int displayId = mVirtualDisplay.getDisplay().getDisplayId();
        final InputManager im = InputManager.getInstance();
        im.injectInputEvent(createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, displayId),
                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        im.injectInputEvent(createKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, displayId),
                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    @Override
    public boolean gatherTransparentRegion(Region region) {
        // The tap exclude region may be affected by any view on top of it, so we detect the
        // possible change by monitoring this function. The tap exclude region is only used
        // for virtual displays.
        notifyBoundsChanged();
        return super.gatherTransparentRegion(region);
    }

    /** An opaque unique identifier for this task surface among others being managed by the app. */
    @Override
    public int getId() {
        return getDisplayId();
    }

    @Override
    public int getDisplayId() {
        if (isInitialized()) {
            return mVirtualDisplay.getDisplay().getDisplayId();
        }
        return INVALID_DISPLAY;
    }

    /**
     * Check if container is ready to launch and create {@link ActivityOptions} to target the
     * virtual display.
     * @param options The existing options to amend, or null if the caller wants new options to be
     *                created
     */
    @Override
    protected ActivityOptions prepareActivityOptions(ActivityOptions options) {
        options = super.prepareActivityOptions(options);
        options.setLaunchDisplayId(getDisplayId());
        options.setLaunchWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        options.setTaskAlwaysOnTop(true);
        return options;
    }

    /**
     * Set forwarded insets on the virtual display.
     *
     * @see IWindowManager#setForwardedInsets
     */
    @Override
    public void setForwardedInsets(Insets insets) {
        mForwardedInsets = insets;
        if (!isInitialized()) {
            return;
        }
        try {
            final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
            wm.setForwardedInsets(getDisplayId(), mForwardedInsets);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Updates position and bounds information needed by WM and IME to manage window
     * focus and touch events properly.
     * <p>
     * This should be called whenever the position or size of the surface changes
     * or if touchable areas above the surface are added or removed.
     */
    private void updateLocationAndTapExcludeRegion() {
        if (!isInitialized() || mHost.getWindow() == null) {
            return;
        }
        reportLocation(mHost.getScreenToTaskMatrix(), mHost.getPositionInWindow());
        applyTapExcludeRegion(mHost.getWindow(), mHost.getTapExcludeRegion());
    }

    /**
     * Call to update the position and transform matrix for the embedded surface.
     * <p>
     * This should not normally be called directly, but through
     * {@link #updateLocationAndTapExcludeRegion()}. This method
     * is provided as an optimization when managing multiple TaskSurfaces within a view.
     *
     * @param screenToViewMatrix the matrix/transform from screen space to view space
     * @param positionInWindow the window-relative position of the surface
     *
     * @see InputMethodManager#reportActivityView(int, Matrix)
     */
    private void reportLocation(Matrix screenToViewMatrix, Point positionInWindow) {
        try {
            final int displayId = getDisplayId();
            mContext.getSystemService(InputMethodManager.class)
                    .reportActivityView(displayId, screenToViewMatrix);
            IWindowSession session = WindowManagerGlobal.getWindowSession();
            session.updateDisplayContentLocation(mHost.getWindow(), positionInWindow.x,
                    positionInWindow.y, displayId);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Call to update the tap exclude region for the window.
     * <p>
     * This should not normally be called directly, but through
     * {@link #updateLocationAndTapExcludeRegion()}. This method
     * is provided as an optimization when managing multiple TaskSurfaces within a view.
     *
     * @see IWindowSession#updateTapExcludeRegion(IWindow, Region)
     */
    private void applyTapExcludeRegion(IWindow window, @Nullable Region tapExcludeRegion) {
        try {
            IWindowSession session = WindowManagerGlobal.getWindowSession();
            session.updateTapExcludeRegion(window, tapExcludeRegion);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * @see InputMethodManager#reportActivityView(int, Matrix)
     */
    private void clearActivityViewGeometryForIme() {
        final int displayId = getDisplayId();
        mContext.getSystemService(InputMethodManager.class).reportActivityView(displayId, null);
    }

    /**
     * Removes the tap exclude region set by {@link #updateLocationAndTapExcludeRegion()}.
     */
    private void clearTapExcludeRegion() {
        if (mHost.getWindow() == null) {
            Log.w(TAG, "clearTapExcludeRegion: not attached to window!");
            return;
        }
        applyTapExcludeRegion(mHost.getWindow(), null);
    }

    private static KeyEvent createKeyEvent(int action, int code, int displayId) {
        long when = SystemClock.uptimeMillis();
        final KeyEvent ev = new KeyEvent(when, when, action, code, 0 /* repeat */,
                0 /* metaState */, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /* scancode */,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);
        ev.setDisplayId(displayId);
        return ev;
    }

    /** Get density of the hosting display. */
    private int getBaseDisplayDensity() {
        if (mTmpDisplayMetrics == null) {
            mTmpDisplayMetrics = new DisplayMetrics();
        }
        mContext.getDisplayNoVerify().getRealMetrics(mTmpDisplayMetrics);
        return mTmpDisplayMetrics.densityDpi;
    }

    /**
     * A task change listener that detects background color change of the topmost stack on our
     * virtual display and updates the background of the surface view. This background will be shown
     * when surface view is resized, but the app hasn't drawn its content in new size yet.
     * It also calls StateCallback.onTaskMovedToFront to notify interested parties that the stack
     * associated with the {@link ActivityView} has had a Task moved to the front. This is useful
     * when needing to also bring the host Activity to the foreground at the same time.
     */
    private class TaskStackListenerImpl extends TaskStackListener {

        @Override
        public void onTaskDescriptionChanged(ActivityManager.RunningTaskInfo taskInfo)
                throws RemoteException {
            if (!isInitialized()) {
                return;
            }
            if (taskInfo.displayId != getDisplayId()) {
                return;
            }
            ActivityManager.StackInfo stackInfo = getTopMostStackInfo();
            if (stackInfo == null) {
                return;
            }
            // Found the topmost stack on target display. Now check if the topmost task's
            // description changed.
            if (taskInfo.taskId == stackInfo.taskIds[stackInfo.taskIds.length - 1]) {
                mHost.onTaskBackgroundColorChanged(VirtualDisplayTaskEmbedder.this,
                        taskInfo.taskDescription.getBackgroundColor());
            }
        }

        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo)
                throws RemoteException {
            if (!isInitialized() || mListener == null
                    || taskInfo.displayId != getDisplayId()) {
                return;
            }

            ActivityManager.StackInfo stackInfo = getTopMostStackInfo();
            // if StackInfo was null or unrelated to the "move to front" then there's no use
            // notifying the callback
            if (stackInfo != null
                    && taskInfo.taskId == stackInfo.taskIds[stackInfo.taskIds.length - 1]) {
                mListener.onTaskMovedToFront(taskInfo.taskId);
            }
        }

        @Override
        public void onTaskCreated(int taskId, ComponentName componentName) throws RemoteException {
            if (mListener == null || !isInitialized()) {
                return;
            }

            ActivityManager.StackInfo stackInfo = getTopMostStackInfo();
            // if StackInfo was null or unrelated to the task creation then there's no use
            // notifying the callback
            if (stackInfo != null
                    && taskId == stackInfo.taskIds[stackInfo.taskIds.length - 1]) {
                mListener.onTaskCreated(taskId, componentName);
            }
        }

        @Override
        public void onTaskRemovalStarted(ActivityManager.RunningTaskInfo taskInfo)
                throws RemoteException {
            if (mListener == null || !isInitialized()
                    || taskInfo.displayId != getDisplayId()) {
                return;
            }

            mListener.onTaskRemovalStarted(taskInfo.taskId);
        }

        private ActivityManager.StackInfo getTopMostStackInfo() throws RemoteException {
            // Find the topmost task on our virtual display - it will define the background
            // color of the surface view during resizing.
            final int displayId = getDisplayId();
            final List<ActivityManager.StackInfo> stackInfoList =
                    mActivityTaskManager.getAllStackInfosOnDisplay(displayId);
            if (stackInfoList.isEmpty()) {
                return null;
            }
            return stackInfoList.get(0);
        }
    }
}