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

package com.android.server.soundtrigger_middleware;

import android.hardware.soundtrigger.V2_1.ISoundTriggerHw;
import android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback;
import android.hardware.soundtrigger.V2_3.ModelParameterRange;
import android.hardware.soundtrigger.V2_3.Properties;
import android.hardware.soundtrigger.V2_3.RecognitionConfig;
import android.os.IHwBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * A decorator around a HAL, which adds some checks that the HAL is behaving as expected.
 * This is not necessarily a strict enforcement for the HAL contract, but a place to add checks for
 * common HAL malfunctions, to help track them and assist in debugging.
 *
 * The class is not thread-safe.
 */
public class SoundTriggerHw2Enforcer implements ISoundTriggerHw2 {
    static final String TAG = "SoundTriggerHw2Enforcer";

    final ISoundTriggerHw2 mUnderlying;
    Map<Integer, Boolean> mModelStates = new HashMap<>();

    public SoundTriggerHw2Enforcer(
            ISoundTriggerHw2 underlying) {
        mUnderlying = underlying;
    }

    @Override
    public Properties getProperties() {
        return mUnderlying.getProperties();
    }

    @Override
    public int loadSoundModel(ISoundTriggerHw.SoundModel soundModel, Callback callback,
            int cookie) {
        int handle = mUnderlying.loadSoundModel(soundModel, new CallbackEnforcer(callback), cookie);
        mModelStates.put(handle, false);
        return handle;
    }

    @Override
    public int loadPhraseSoundModel(ISoundTriggerHw.PhraseSoundModel soundModel, Callback callback,
            int cookie) {
        int handle = mUnderlying.loadPhraseSoundModel(soundModel, new CallbackEnforcer(callback),
                cookie);
        mModelStates.put(handle, false);
        return handle;
    }

    @Override
    public void unloadSoundModel(int modelHandle) {
        mUnderlying.unloadSoundModel(modelHandle);
        mModelStates.remove(modelHandle);
    }

    @Override
    public void stopRecognition(int modelHandle) {
        mUnderlying.stopRecognition(modelHandle);
        mModelStates.replace(modelHandle, false);
    }

    @Override
    public void stopAllRecognitions() {
        mUnderlying.stopAllRecognitions();
        for (Map.Entry<Integer, Boolean> entry : mModelStates.entrySet()) {
            entry.setValue(false);
        }
    }

    @Override
    public void startRecognition(int modelHandle, RecognitionConfig config, Callback callback,
            int cookie) {
        mUnderlying.startRecognition(modelHandle, config, new CallbackEnforcer(callback), cookie);
        mModelStates.replace(modelHandle, true);
    }

    @Override
    public void getModelState(int modelHandle) {
        mUnderlying.getModelState(modelHandle);
    }

    @Override
    public int getModelParameter(int modelHandle, int param) {
        return mUnderlying.getModelParameter(modelHandle, param);
    }

    @Override
    public void setModelParameter(int modelHandle, int param, int value) {
        mUnderlying.setModelParameter(modelHandle, param, value);
    }

    @Override
    public ModelParameterRange queryParameter(int modelHandle, int param) {
        return mUnderlying.queryParameter(modelHandle, param);
    }

    @Override
    public boolean linkToDeath(IHwBinder.DeathRecipient recipient, long cookie) {
        return mUnderlying.linkToDeath(recipient, cookie);
    }

    @Override
    public boolean unlinkToDeath(IHwBinder.DeathRecipient recipient) {
        return mUnderlying.unlinkToDeath(recipient);
    }

    @Override
    public String interfaceDescriptor() throws RemoteException {
        return mUnderlying.interfaceDescriptor();
    }

    private class CallbackEnforcer implements Callback {
        private final Callback mUnderlying;

        private CallbackEnforcer(
                Callback underlying) {
            mUnderlying = underlying;
        }

        @Override
        public void recognitionCallback(ISoundTriggerHwCallback.RecognitionEvent event,
                int cookie) {
            int model = event.header.model;
            if (!mModelStates.getOrDefault(model, false)) {
                Log.wtfStack(TAG, "Unexpected recognition event for model: " + model);
            }
            if (event.header.status
                    != android.media.soundtrigger_middleware.RecognitionStatus.FORCED) {
                mModelStates.replace(model, false);
            }
            mUnderlying.recognitionCallback(event, cookie);
        }

        @Override
        public void phraseRecognitionCallback(ISoundTriggerHwCallback.PhraseRecognitionEvent event,
                int cookie) {
            int model = event.common.header.model;
            if (!mModelStates.getOrDefault(model, false)) {
                Log.wtfStack(TAG, "Unexpected recognition event for model: " + model);
            }
            if (event.common.header.status
                    != android.media.soundtrigger_middleware.RecognitionStatus.FORCED) {
                mModelStates.replace(model, false);
            }
            mUnderlying.phraseRecognitionCallback(event, cookie);
        }
    }
}