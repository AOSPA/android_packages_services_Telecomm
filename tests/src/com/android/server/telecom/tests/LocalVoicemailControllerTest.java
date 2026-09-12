/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.telecom.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.IBinder;
import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallState;
import com.android.server.telecom.LocalVoicemailController;
import com.android.server.telecom.TelecomSystem;
import com.android.internal.telecom.ILocalVoicemailService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class LocalVoicemailControllerTest extends TelecomTestCase {

    @Mock
    private LocalVoicemailController.CallsManagerAdapter mMockCallsManagerAdapter;
    @Mock
    private ScheduledExecutorService mMockScheduledExecutorService;
    @Mock
    private TelecomSystem.SyncRoot mMockLock;

    private LocalVoicemailController mController;
    private static final String PACKAGE_NAME = "com.android.server.telecom.tests";
    private static final UserHandle USER_HANDLE = UserHandle.of(100);

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        when(mMockCallsManagerAdapter.getCurrentUserHandle()).thenReturn(USER_HANDLE);
        mController = new LocalVoicemailController(mMockCallsManagerAdapter, mContext,
                mMockScheduledExecutorService, mMockLock, PACKAGE_NAME);
    }

    @SmallTest
    @Test
    public void testIsEligibleForLocalVoicemail() {
        Call mockCall = mock(Call.class);
        java.util.Set<Call> calls = new java.util.HashSet<>();
        calls.add(mockCall);

        assertTrue(LocalVoicemailController.isEligibleForLocalVoicemail(mockCall, calls));
        assertFalse(LocalVoicemailController.isEligibleForLocalVoicemail(null, calls));

        Call mockCall2 = mock(Call.class);
        calls.add(mockCall2);
        assertFalse(LocalVoicemailController.isEligibleForLocalVoicemail(mockCall, calls));
    }

    @SmallTest
    @Test
    public void testOnAudioModeChangedNoService() {
        mController = new LocalVoicemailController(mMockCallsManagerAdapter, mContext,
                mMockScheduledExecutorService, mMockLock, null);
        mController.onAudioModeChanged(AudioManager.MODE_CALL_REDIRECT);
        verify(mContext, never()).bindServiceAsUser(any(), any(), anyInt(), any());
    }

    @SmallTest
    @Test
    public void testOnCallAddedEligible() {
        Call mockCall = mock(Call.class);
        when(mockCall.isExternalCall()).thenReturn(false);
        when(mockCall.getState()).thenReturn(CallState.RINGING);
        when(mMockCallsManagerAdapter.getLocalVoicemailTimeout(any()))
                .thenReturn(Duration.ofSeconds(10));

        mController.onCallAdded(mockCall);

        verify(mMockScheduledExecutorService).schedule(any(Runnable.class), eq(10L),
                eq(TimeUnit.SECONDS));
    }

    @SmallTest
    @Test
    public void testAnswerForLocalVoicemail() {
        Call mockCall = mock(Call.class);
        when(mockCall.isExternalCall()).thenReturn(false);
        when(mockCall.getState()).thenReturn(CallState.RINGING);
        when(mMockCallsManagerAdapter.getLocalVoicemailTimeout(any()))
                .thenReturn(Duration.ofSeconds(10));

        mController.onCallAdded(mockCall);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mMockScheduledExecutorService).schedule(runnableCaptor.capture(), eq(10L),
                eq(TimeUnit.SECONDS));

        runnableCaptor.getValue().run();
        verify(mMockCallsManagerAdapter).startLocalVoicemail(mockCall);
    }

    @SmallTest
    @Test
    public void testOnCallAddedNotEligible() {
        Call mockCall1 = mock(Call.class);
        when(mockCall1.isExternalCall()).thenReturn(false);
        mController.onCallAdded(mockCall1);

        Call mockCall2 = mock(Call.class);
        when(mockCall2.isExternalCall()).thenReturn(false);
        mController.onCallAdded(mockCall2);

        verify(mMockScheduledExecutorService, never()).schedule(any(Runnable.class), anyLong(),
                any());
    }

    @SmallTest
    @Test
    public void testOnCallStateChangedSameState() {
        Call mockCall = mock(Call.class);
        mController.onCallStateChanged(mockCall, CallState.RINGING, CallState.RINGING);
        verify(mMockScheduledExecutorService, never()).schedule(any(Runnable.class), anyLong(),
                any());
    }

    @SmallTest
    @Test
    public void testOnAudioModeChangedBindingFailedNoService() {
        setupCallForBinding();

        PackageManager pm = mContext.getPackageManager();
        doReturn(Collections.emptyList()).when(pm).queryIntentServices(any(), anyInt());

        mController.onAudioModeChanged(AudioManager.MODE_CALL_REDIRECT);
        verify(mContext, never()).bindServiceAsUser(any(), any(), anyInt(), any());
    }

    @SmallTest
    @Test
    public void testOnAudioModeChangedBindingFailedNoServiceInfo() {
        setupCallForBinding();

        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> resolveInfos = new ArrayList<>();
        resolveInfos.add(new ResolveInfo()); // serviceInfo is null
        doReturn(resolveInfos).when(pm).queryIntentServices(any(), anyInt());

        mController.onAudioModeChanged(AudioManager.MODE_CALL_REDIRECT);
        verify(mContext, never()).bindServiceAsUser(any(), any(), anyInt(), any());
    }

    @SmallTest
    @Test
    public void testOnAudioModeChangedBindingFailedReturnsFalse() {
        Call mockCall = setupCallForBinding();

        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> resolveInfos = new ArrayList<>();
        ResolveInfo ri = new ResolveInfo();
        ri.serviceInfo = new ServiceInfo();
        ri.serviceInfo.packageName = PACKAGE_NAME;
        ri.serviceInfo.name = "LocalVoicemailService";
        resolveInfos.add(ri);
        doReturn(resolveInfos).when(pm).queryIntentServices(any(), anyInt());
        doReturn(false).when(mContext).bindServiceAsUser(any(), any(), anyInt(), any());

        mController.onAudioModeChanged(AudioManager.MODE_CALL_REDIRECT);
        verify(mMockCallsManagerAdapter).disconnectCall(mockCall);
    }

    @SmallTest
    @Test
    public void testOnCallRemoved() {
        Call mockCall = mock(Call.class);
        when(mockCall.isExternalCall()).thenReturn(false);
        when(mockCall.getState()).thenReturn(CallState.RINGING);
        when(mMockCallsManagerAdapter.getLocalVoicemailTimeout(any()))
                .thenReturn(Duration.ofSeconds(10));
        ScheduledFuture mockFuture = mock(ScheduledFuture.class);
        when(mMockScheduledExecutorService.schedule(any(Runnable.class), anyLong(), any()))
                .thenReturn(mockFuture);

        mController.onCallAdded(mockCall);
        mController.onCallRemoved(mockCall);

        verify(mockFuture).cancel(false);
    }

    @SmallTest
    @Test
    public void testOnCallStateChangedToLocalVoicemail() {
        Call mockCall = mock(Call.class);
        when(mockCall.isExternalCall()).thenReturn(false);
        when(mockCall.getState()).thenReturn(CallState.RINGING);
        when(mMockCallsManagerAdapter.getLocalVoicemailTimeout(any()))
                .thenReturn(Duration.ofSeconds(10));
        mController.onCallAdded(mockCall);

        mController.onCallStateChanged(mockCall, CallState.RINGING, CallState.LOCAL_VOICEMAIL);
        // Just logs, no other side effects to verify yet.
    }

    @SmallTest
    @Test
    public void testOnCallStateChangedToDisconnected() {
        Call mockCall = mock(Call.class);
        when(mockCall.isExternalCall()).thenReturn(false);
        when(mockCall.getState()).thenReturn(CallState.RINGING);
        when(mMockCallsManagerAdapter.getLocalVoicemailTimeout(any()))
                .thenReturn(Duration.ofSeconds(10));
        mController.onCallAdded(mockCall);

        mController.onCallStateChanged(mockCall, CallState.RINGING, CallState.DISCONNECTED);
        // This should trigger maybeUnbindLocalVoicemailService
        // mConnection is null, so it shouldn't unbind
        verify(mContext, never()).unbindService(any());
    }

    @SmallTest
    @Test
    public void testOnCallStateChangedRinging() {
        Call mockCall = mock(Call.class);
        when(mockCall.isExternalCall()).thenReturn(false);
        when(mMockCallsManagerAdapter.getLocalVoicemailTimeout(any()))
                .thenReturn(Duration.ofSeconds(10));

        mController.onCallStateChanged(mockCall, CallState.NEW, CallState.RINGING);
        verify(mMockScheduledExecutorService).schedule(any(Runnable.class), eq(10L),
                eq(TimeUnit.SECONDS));
    }

    @SmallTest
    @Test
    public void testGetActiveLocalVoicemailService() {
        assertEquals(PACKAGE_NAME, mController.getActiveLocalVoicemailService());

        mController.setTestLocalVoicemailService("test.package");
        assertEquals("test.package", mController.getActiveLocalVoicemailService());

        mController = new LocalVoicemailController(mMockCallsManagerAdapter, mContext,
                mMockScheduledExecutorService, mMockLock, null);
        assertNull(mController.getActiveLocalVoicemailService());
    }

    @SmallTest
    @Test
    public void testOnAudioModeChangedStartVoicemail() {
        Call mockCall = mock(Call.class);
        when(mockCall.isExternalCall()).thenReturn(false);
        when(mockCall.getState()).thenReturn(CallState.RINGING);
        when(mMockCallsManagerAdapter.getLocalVoicemailTimeout(any()))
                .thenReturn(Duration.ofSeconds(10));
        mController.onCallAdded(mockCall);

        // Now trigger audio mode change to MODE_CALL_REDIRECT
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> resolveInfos = new ArrayList<>();
        ResolveInfo ri = new ResolveInfo();
        ri.serviceInfo = new ServiceInfo();
        ri.serviceInfo.packageName = PACKAGE_NAME;
        ri.serviceInfo.name = "LocalVoicemailService";
        resolveInfos.add(ri);
        doReturn(resolveInfos).when(pm).queryIntentServices(any(Intent.class), anyInt());
        doReturn(true).when(mContext).bindServiceAsUser(any(), any(), anyInt(), any());
        doNothing().when(mContext).unbindService(any());

        mController.onAudioModeChanged(AudioManager.MODE_CALL_REDIRECT);
        verify(mContext).bindServiceAsUser(any(), any(), anyInt(), any());
    }

    @SmallTest
    @Test
    public void testPerformLocalVoicemailCorrectnessCheck() {
        Call mockCall = mock(Call.class);
        when(mockCall.isExternalCall()).thenReturn(false);
        when(mockCall.getState()).thenReturn(CallState.RINGING);
        when(mMockCallsManagerAdapter.getLocalVoicemailTimeout(any()))
                .thenReturn(Duration.ofSeconds(10));
        ScheduledFuture mockFuture = mock(ScheduledFuture.class);
        when(mMockScheduledExecutorService.schedule(any(Runnable.class), anyLong(), any()))
                .thenReturn(mockFuture);
        mController.onCallAdded(mockCall);
        // mCall is now mockCall

        Call mockCall2 = mock(Call.class);
        when(mockCall2.isExternalCall()).thenReturn(false);
        when(mockCall2.isActiveFocus()).thenReturn(true);
        mController.onCallAdded(mockCall2);

        // performLocalVoicemailCorrectnessCheck is called.
        // verify stopLocalVoicemail IS called.
        verify(mMockCallsManagerAdapter).disconnectCall(mockCall);
    }

    @SmallTest
    @Test
    public void testOnCallAddedExternal() {
        Call mockCall = mock(Call.class);
        when(mockCall.isExternalCall()).thenReturn(true);
        mController.onCallAdded(mockCall);
        verify(mMockScheduledExecutorService, never()).schedule(any(Runnable.class), anyLong(),
                any());
    }

    @SmallTest
    @Test
    public void testOnExternalCallChanged() {
        mController.onExternalCallChanged(mock(Call.class), true);
        // Should trigger correctness check, which returns early because mCall is null
    }

    @SmallTest
    @Test
    public void testServiceConnectionConnected() throws Exception {
        Call mockCall = setupCallForBinding();
        ServiceConnection connection = triggerBinding();

        ILocalVoicemailService mockService = mock(ILocalVoicemailService.class);
        IBinder mockBinder = mock(IBinder.class);
        when(mockBinder.queryLocalInterface(anyString())).thenReturn(mockService);

        connection.onServiceConnected(new ComponentName(PACKAGE_NAME, "LocalVoicemailService"),
                mockBinder);
        verify(mockService).setAdapter(any());
        verify(mockService).startLocalVoicemail(any());
    }

    @SmallTest
    @Test
    public void testServiceConnectionConnectedCallRemovedInBetween() throws Exception {
        Call mockCall = setupCallForBinding();
        ServiceConnection connection = triggerBinding();

        ILocalVoicemailService mockService = mock(ILocalVoicemailService.class);
        IBinder mockBinder = mock(IBinder.class);
        when(mockBinder.queryLocalInterface(anyString())).thenReturn(mockService);

        // Remove call before onServiceConnected
        mController.onCallRemoved(mockCall);

        connection.onServiceConnected(new ComponentName(PACKAGE_NAME, "LocalVoicemailService"),
                mockBinder);

        verify(mockService, never()).startLocalVoicemail(any());
    }

    @SmallTest
    @Test
    public void testServiceConnectionDisconnected() throws Exception {
        Call mockCall = setupCallForBinding();
        ServiceConnection connection = triggerBinding();

        ILocalVoicemailService mockService = mock(ILocalVoicemailService.class);
        IBinder mockBinder = mock(IBinder.class);
        when(mockBinder.queryLocalInterface(anyString())).thenReturn(mockService);
        connection.onServiceConnected(new ComponentName(PACKAGE_NAME, "LocalVoicemailService"),
                mockBinder);

        connection.onServiceDisconnected(new ComponentName(PACKAGE_NAME, "LocalVoicemailService"));
        // Verify stopLocalVoicemail is called on the disconnected call.
        verify(mockService).stopLocalVoicemail(any());
        verify(mMockCallsManagerAdapter).disconnectCall(mockCall);
    }

    @SmallTest
    @Test
    public void testServiceConnectionBindingDied() throws Exception {
        Call mockCall = setupCallForBinding();
        ServiceConnection connection = triggerBinding();

        ILocalVoicemailService mockService = mock(ILocalVoicemailService.class);
        IBinder mockBinder = mock(IBinder.class);
        when(mockBinder.queryLocalInterface(anyString())).thenReturn(mockService);
        connection.onServiceConnected(new ComponentName(PACKAGE_NAME, "LocalVoicemailService"),
                mockBinder);

        connection.onBindingDied(new ComponentName(PACKAGE_NAME, "LocalVoicemailService"));
        // Verify that stopLocalVoicemail called on the disconnected call.
        verify(mockService).stopLocalVoicemail(any());
        verify(mMockCallsManagerAdapter).disconnectCall(mockCall);
    }

    @SmallTest
    @Test
    public void testServiceConnectionNullBinding() throws Exception {
        Call mockCall = setupCallForBinding();
        ServiceConnection connection = triggerBinding();

        ILocalVoicemailService mockService = mock(ILocalVoicemailService.class);
        IBinder mockBinder = mock(IBinder.class);
        when(mockBinder.queryLocalInterface(anyString())).thenReturn(mockService);
        connection.onServiceConnected(new ComponentName(PACKAGE_NAME, "LocalVoicemailService"),
                mockBinder);

        connection.onNullBinding(new ComponentName(PACKAGE_NAME, "LocalVoicemailService"));
        // Verify stopLocalVoicemail is called on the disconnected call.
        verify(mockService).stopLocalVoicemail(any());
        verify(mMockCallsManagerAdapter).disconnectCall(mockCall);
    }

    @SmallTest
    @Test
    public void testOnAudioModeChangedStopVoicemail() throws Exception {
        Call mockCall = setupCallForBinding();
        ServiceConnection connection = triggerBinding();

        ILocalVoicemailService mockService = mock(ILocalVoicemailService.class);
        IBinder mockBinder = mock(IBinder.class);
        when(mockBinder.queryLocalInterface(anyString())).thenReturn(mockService);
        connection.onServiceConnected(new ComponentName(PACKAGE_NAME, "LocalVoicemailService"),
                mockBinder);

        // Change audio mode to something else while in local voicemail
        mController.onAudioModeChanged(AudioManager.MODE_IN_CALL);

        // This should call maybeUnbindLocalVoicemailService WITHOUT clearing mCall first.
        verify(mockService).stopLocalVoicemail(any());
        verify(mContext).unbindService(any());
    }

    private Call setupCallForBinding() {
        Call mockCall = mock(Call.class);
        when(mockCall.isExternalCall()).thenReturn(false);
        when(mockCall.getState()).thenReturn(CallState.RINGING);
        when(mMockCallsManagerAdapter.getLocalVoicemailTimeout(any()))
                .thenReturn(Duration.ofSeconds(10));
        ScheduledFuture mockFuture = mock(ScheduledFuture.class);
        when(mMockScheduledExecutorService.schedule(any(Runnable.class), anyLong(), any()))
                .thenReturn(mockFuture);
        mController.onCallAdded(mockCall);
        return mockCall;
    }

    private ServiceConnection triggerBinding() {
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> resolveInfos = new ArrayList<>();
        ResolveInfo ri = new ResolveInfo();
        ri.serviceInfo = new ServiceInfo();
        ri.serviceInfo.packageName = PACKAGE_NAME;
        ri.serviceInfo.name = "LocalVoicemailService";
        resolveInfos.add(ri);
        doReturn(resolveInfos).when(pm).queryIntentServices(any(Intent.class), anyInt());
        doReturn(true).when(mContext).bindServiceAsUser(any(), any(), anyInt(), any());
        doNothing().when(mContext).unbindService(any());

        mController.onAudioModeChanged(AudioManager.MODE_CALL_REDIRECT);

        ArgumentCaptor<ServiceConnection> connectionCaptor = ArgumentCaptor.forClass(
                ServiceConnection.class);
        verify(mContext).bindServiceAsUser(any(), connectionCaptor.capture(), anyInt(), any());
        return connectionCaptor.getValue();
    }

    @SmallTest
    @Test
    public void testLocalVoicemailServiceAdapter() throws Exception {
        Call mockCall = mock(Call.class);
        when(mockCall.isExternalCall()).thenReturn(false);
        when(mockCall.getState()).thenReturn(CallState.RINGING);
        when(mMockCallsManagerAdapter.getLocalVoicemailTimeout(any()))
                .thenReturn(Duration.ofSeconds(10));
        ScheduledFuture mockFuture = mock(ScheduledFuture.class);
        when(mMockScheduledExecutorService.schedule(any(Runnable.class), anyLong(), any()))
                .thenReturn(mockFuture);
        mController.onCallAdded(mockCall);

        LocalVoicemailController.LocalVoicemailServiceAdapter adapter =
                mController.new LocalVoicemailServiceAdapter(PACKAGE_NAME);

        adapter.disconnectCall("callId");
        verify(mMockCallsManagerAdapter).disconnectCall(mockCall);
    }

    @SmallTest
    @Test
    public void testDump() {
        Call mockCall = mock(Call.class);
        when(mockCall.getId()).thenReturn("call1");
        when(mockCall.isExternalCall()).thenReturn(false);
        when(mockCall.getState()).thenReturn(CallState.RINGING);
        when(mMockCallsManagerAdapter.getLocalVoicemailTimeout(any()))
                .thenReturn(Duration.ofSeconds(10));
        mController.onCallAdded(mockCall);

        android.util.IndentingPrintWriter pw = new android.util.IndentingPrintWriter(
                new java.io.PrintWriter(new java.io.StringWriter()));
        mController.dump(pw);
    }
}
