/*
 * Copyright 2019 The Android Open Source Project
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

package android.media;

import android.content.Intent;
import android.media.IMediaRoute2ProviderClient;

/**
 * {@hide}
 */
oneway interface IMediaRoute2Provider {
    void setClient(IMediaRoute2ProviderClient client);
    void requestSelectRoute(String packageName, String id, int seq);
    void unselectRoute(String packageName, String id);
    void notifyControlRequestSent(String id, in Intent request);
    void requestSetVolume(String id, int volume);
    void requestUpdateVolume(String id, int delta);
}
