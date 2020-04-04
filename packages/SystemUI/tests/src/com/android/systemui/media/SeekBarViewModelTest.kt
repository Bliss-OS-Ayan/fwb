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

package com.android.systemui.media

import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.widget.SeekBar
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.test.filters.SmallTest

import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
public class SeekBarViewModelTest : SysuiTestCase() {

    private lateinit var viewModel: SeekBarViewModel
    private lateinit var fakeExecutor: FakeExecutor
    private val taskExecutor: TaskExecutor = object : TaskExecutor() {
        override fun executeOnDiskIO(runnable: Runnable) {
            runnable.run()
        }
        override fun postToMainThread(runnable: Runnable) {
            runnable.run()
        }
        override fun isMainThread(): Boolean {
            return true
        }
    }
    @Mock private lateinit var mockController: MediaController
    @Mock private lateinit var mockTransport: MediaController.TransportControls

    @Before
    fun setUp() {
        fakeExecutor = FakeExecutor(FakeSystemClock())
        viewModel = SeekBarViewModel(fakeExecutor)
        mockController = mock(MediaController::class.java)
        mockTransport = mock(MediaController.TransportControls::class.java)

        // LiveData to run synchronously
        ArchTaskExecutor.getInstance().setDelegate(taskExecutor)
    }

    @After
    fun tearDown() {
        ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test
    fun updateColor() {
        viewModel.updateController(mockController, Color.RED)
        assertThat(viewModel.progress.value!!.color).isEqualTo(Color.RED)
    }

    @Test
    fun updateDuration() {
        // GIVEN that the duration is contained within the metadata
        val duration = 12000L
        val metadata = MediaMetadata.Builder().run {
            putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
            build()
        }
        whenever(mockController.getMetadata()).thenReturn(metadata)
        // WHEN the controller is updated
        viewModel.updateController(mockController, Color.RED)
        // THEN the duration is extracted
        assertThat(viewModel.progress.value!!.duration).isEqualTo(duration)
        assertThat(viewModel.progress.value!!.enabled).isTrue()
    }

    @Test
    fun updateDurationNegative() {
        // GIVEN that the duration is negative
        val duration = -1L
        val metadata = MediaMetadata.Builder().run {
            putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
            build()
        }
        whenever(mockController.getMetadata()).thenReturn(metadata)
        // WHEN the controller is updated
        viewModel.updateController(mockController, Color.RED)
        // THEN the seek bar is disabled
        assertThat(viewModel.progress.value!!.enabled).isFalse()
    }

    @Test
    fun updateDurationZero() {
        // GIVEN that the duration is zero
        val duration = 0L
        val metadata = MediaMetadata.Builder().run {
            putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
            build()
        }
        whenever(mockController.getMetadata()).thenReturn(metadata)
        // WHEN the controller is updated
        viewModel.updateController(mockController, Color.RED)
        // THEN the seek bar is disabled
        assertThat(viewModel.progress.value!!.enabled).isFalse()
    }

    @Test
    fun updateElapsedTime() {
        // GIVEN that the PlaybackState contins the current position
        val position = 200L
        val state = PlaybackState.Builder().run {
            setState(PlaybackState.STATE_PLAYING, position, 1f)
            build()
        }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        // WHEN the controller is updated
        viewModel.updateController(mockController, Color.RED)
        // THEN elapsed time is captured
        assertThat(viewModel.progress.value!!.elapsedTime).isEqualTo(200.toInt())
    }

    @Test
    fun updateSeekAvailable() {
        // GIVEN that seek is included in actions
        val state = PlaybackState.Builder().run {
            setActions(PlaybackState.ACTION_SEEK_TO)
            build()
        }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        // WHEN the controller is updated
        viewModel.updateController(mockController, Color.RED)
        // THEN seek is available
        assertThat(viewModel.progress.value!!.seekAvailable).isTrue()
    }

    @Test
    fun updateSeekNotAvailable() {
        // GIVEN that seek is not included in actions
        val state = PlaybackState.Builder().run {
            setActions(PlaybackState.ACTION_PLAY)
            build()
        }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        // WHEN the controller is updated
        viewModel.updateController(mockController, Color.RED)
        // THEN seek is not available
        assertThat(viewModel.progress.value!!.seekAvailable).isFalse()
    }

    @Test
    fun handleSeek() {
        whenever(mockController.getTransportControls()).thenReturn(mockTransport)
        viewModel.updateController(mockController, Color.RED)
        // WHEN user input is dispatched
        val pos = 42L
        viewModel.onSeek(pos)
        fakeExecutor.runAllReady()
        // THEN transport controls should be used
        verify(mockTransport).seekTo(pos)
    }

    @Test
    fun handleProgressChangedUser() {
        whenever(mockController.getTransportControls()).thenReturn(mockTransport)
        viewModel.updateController(mockController, Color.RED)
        // WHEN user starts dragging the seek bar
        val pos = 42
        viewModel.seekBarListener.onProgressChanged(SeekBar(context), pos, true)
        fakeExecutor.runAllReady()
        // THEN transport controls should be used
        verify(mockTransport).seekTo(pos.toLong())
    }

    @Test
    fun handleProgressChangedOther() {
        whenever(mockController.getTransportControls()).thenReturn(mockTransport)
        viewModel.updateController(mockController, Color.RED)
        // WHEN user starts dragging the seek bar
        val pos = 42
        viewModel.seekBarListener.onProgressChanged(SeekBar(context), pos, false)
        fakeExecutor.runAllReady()
        // THEN transport controls should be used
        verify(mockTransport, never()).seekTo(pos.toLong())
    }

    @Test
    fun handleStartTrackingTouch() {
        whenever(mockController.getTransportControls()).thenReturn(mockTransport)
        viewModel.updateController(mockController, Color.RED)
        // WHEN user starts dragging the seek bar
        val pos = 42
        val bar = SeekBar(context).apply {
            progress = pos
        }
        viewModel.seekBarListener.onStartTrackingTouch(bar)
        fakeExecutor.runAllReady()
        // THEN transport controls should be used
        verify(mockTransport, never()).seekTo(pos.toLong())
    }

    @Test
    fun handleStopTrackingTouch() {
        whenever(mockController.getTransportControls()).thenReturn(mockTransport)
        viewModel.updateController(mockController, Color.RED)
        // WHEN user ends drag
        val pos = 42
        val bar = SeekBar(context).apply {
            progress = pos
        }
        viewModel.seekBarListener.onStopTrackingTouch(bar)
        fakeExecutor.runAllReady()
        // THEN transport controls should be used
        verify(mockTransport).seekTo(pos.toLong())
    }

    @Test
    fun queuePollTaskWhenPlaying() {
        // GIVEN that the track is playing
        val state = PlaybackState.Builder().run {
            setState(PlaybackState.STATE_PLAYING, 100L, 1f)
            build()
        }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        // WHEN the controller is updated
        viewModel.updateController(mockController, Color.RED)
        // THEN a task is queued
        assertThat(fakeExecutor.numPending()).isEqualTo(1)
    }

    @Test
    fun noQueuePollTaskWhenStopped() {
        // GIVEN that the playback state is stopped
        val state = PlaybackState.Builder().run {
            setState(PlaybackState.STATE_STOPPED, 200L, 1f)
            build()
        }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        // WHEN updated
        viewModel.updateController(mockController, Color.RED)
        // THEN an update task is not queued
        assertThat(fakeExecutor.numPending()).isEqualTo(0)
    }

    @Test
    fun queuePollTaskWhenListening() {
        // GIVEN listening
        viewModel.listening = true
        with(fakeExecutor) {
            advanceClockToNext()
            runAllReady()
        }
        // AND the playback state is playing
        val state = PlaybackState.Builder().run {
            setState(PlaybackState.STATE_PLAYING, 200L, 1f)
            build()
        }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        // WHEN updated
        viewModel.updateController(mockController, Color.RED)
        // THEN an update task is queued
        assertThat(fakeExecutor.numPending()).isEqualTo(1)
    }

    @Test
    fun noQueuePollTaskWhenNotListening() {
        // GIVEN not listening
        viewModel.listening = false
        with(fakeExecutor) {
            advanceClockToNext()
            runAllReady()
        }
        // AND the playback state is playing
        val state = PlaybackState.Builder().run {
            setState(PlaybackState.STATE_STOPPED, 200L, 1f)
            build()
        }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        // WHEN updated
        viewModel.updateController(mockController, Color.RED)
        // THEN an update task is not queued
        assertThat(fakeExecutor.numPending()).isEqualTo(0)
    }

    @Test
    fun pollTaskQueuesAnotherPollTaskWhenPlaying() {
        // GIVEN that the track is playing
        val state = PlaybackState.Builder().run {
            setState(PlaybackState.STATE_PLAYING, 100L, 1f)
            build()
        }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        viewModel.updateController(mockController, Color.RED)
        // WHEN the next task runs
        with(fakeExecutor) {
            advanceClockToNext()
            runAllReady()
        }
        // THEN another task is queued
        assertThat(fakeExecutor.numPending()).isEqualTo(1)
    }

    @Test
    fun taskUpdatesProgress() {
        // GIVEN that the PlaybackState contins the current position
        val position = 200L
        val state = PlaybackState.Builder().run {
            setState(PlaybackState.STATE_PLAYING, position, 1f)
            build()
        }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        viewModel.updateController(mockController, Color.RED)
        // AND the playback state advances
        val nextPosition = 300L
        val nextState = PlaybackState.Builder().run {
            setState(PlaybackState.STATE_PLAYING, nextPosition, 1f)
            build()
        }
        whenever(mockController.getPlaybackState()).thenReturn(nextState)
        // WHEN the task runs
        with(fakeExecutor) {
            advanceClockToNext()
            runAllReady()
        }
        // THEN elapsed time is captured
        assertThat(viewModel.progress.value!!.elapsedTime).isEqualTo(nextPosition.toInt())
    }

    @Test
    fun startListeningQueuesPollTask() {
        // GIVEN not listening
        viewModel.listening = false
        with(fakeExecutor) {
            advanceClockToNext()
            runAllReady()
        }
        // AND the playback state is playing
        val state = PlaybackState.Builder().run {
            setState(PlaybackState.STATE_STOPPED, 200L, 1f)
            build()
        }
        whenever(mockController.getPlaybackState()).thenReturn(state)
        viewModel.updateController(mockController, Color.RED)
        // WHEN start listening
        viewModel.listening = true
        // THEN an update task is queued
        assertThat(fakeExecutor.numPending()).isEqualTo(1)
    }
}