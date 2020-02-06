/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row;

import android.annotation.MainThread;
import android.util.ArrayMap;

import androidx.annotation.NonNull;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.Map;

/**
 * A {@link BindStage} is an abstraction for a unit of work in inflating/binding/unbinding
 * views to a notification. Used by {@link NotifBindPipeline}.
 *
 * Clients may also use {@link #getStageParams} to provide parameters for this stage for a given
 * notification and request a rebind.
 *
 * @param <Params> params to do this stage
 */
@MainThread
public abstract class BindStage<Params> extends BindRequester {

    private Map<NotificationEntry, Params> mContentParams = new ArrayMap<>();

    /**
     * Execute the stage asynchronously.
     *
     * @param row notification top-level view to bind views to
     * @param callback callback after stage finishes
     */
    protected abstract void executeStage(
            @NonNull NotificationEntry entry,
            @NonNull ExpandableNotificationRow row,
            @NonNull StageCallback callback);

    /**
     * Abort the stage if in progress.
     *
     * @param row notification top-level view to bind views to
     */
    protected abstract void abortStage(
            @NonNull NotificationEntry entry,
            @NonNull ExpandableNotificationRow row);

    /**
     * Get the stage parameters for the entry. Clients should use this to modify how the stage
     * handles the notification content.
     */
    public final Params getStageParams(@NonNull NotificationEntry entry) {
        Params params = mContentParams.get(entry);
        if (params == null) {
            throw new IllegalStateException(
                    String.format("Entry does not have any stage parameters. key: %s",
                            entry.getKey()));
        }
        return params;
    }

    /**
     * Create a params entry for the notification for this stage.
     */
    final void createStageParams(@NonNull NotificationEntry entry) {
        mContentParams.put(entry, newStageParams());
    }

    /**
     * Delete params entry for notification.
     */
    final void deleteStageParams(@NonNull NotificationEntry entry) {
        mContentParams.remove(entry);
    }

    /**
     * Create a new, empty stage params object.
     */
    protected abstract Params newStageParams();

    /**
     * Interface for callback.
     */
    interface StageCallback {
        /**
         * Callback for when the stage is complete.
         */
        void onStageFinished(NotificationEntry entry);
    }
}