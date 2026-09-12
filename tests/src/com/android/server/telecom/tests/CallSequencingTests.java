/*
 * Copyright (C) 2025 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.telecom.tests;

import static com.android.server.telecom.CallsManager.CALL_FILTER_ALL;
import static com.android.server.telecom.CallsManager.ONGOING_CALL_STATES;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.TestCase.fail;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.telecom.CallAttributes;
import android.telecom.CallException;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.CarrierConfigManager;

import androidx.test.filters.SmallTest;

import com.android.internal.telecom.flags.Flags;
import com.android.server.telecom.AnomalyReporterAdapter;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallState;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.ClockProxy;
import com.android.server.telecom.ConnectionServiceFocusManager;
import com.android.server.telecom.MmiUtils;
import com.android.server.telecom.PhoneAccountRegistrar;
import com.android.server.telecom.TelecomResourceId;
import com.android.server.telecom.Timeouts;
import com.android.server.telecom.callsequencing.CallSequencingController;
import com.android.server.telecom.callsequencing.CallTransaction;
import com.android.server.telecom.callsequencing.voip.OutgoingCallTransactionSequencing;
import com.android.server.telecom.metrics.TelecomMetricsController;
import com.android.server.telecom.stats.CallFailureCause;
import com.android.server.telecom.ui.UiConstants;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public class CallSequencingTests extends TelecomTestCase {
    private static final long SEQUENCING_TIMEOUT_MS = 2000L;
    private static final PhoneAccountHandle mHandle1 = new PhoneAccountHandle(
            new ComponentName("foo", "bar"), "1");
    private static final PhoneAccountHandle mHandle2 = new PhoneAccountHandle(
            new ComponentName("bar", "foo"), "2");
    private static final String TEST_NAME = "Alan Turing";
    private static final Uri TEST_URI = Uri.fromParts("tel", "abc", "123");
    private static final String ACTIVE_CALL_ID = "TC@1";
    private static final String NEW_CALL_ID = "TC@2";

    private CallSequencingController mController;
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Mock CallsManager mCallsManager;
    @Mock Context mContext;
    @Mock ClockProxy mClockProxy;
    @Mock AnomalyReporterAdapter mAnomalyReporter;
    @Mock Timeouts.Adapter mTimeoutsAdapter;
    @Mock TelecomMetricsController mMetricsController;
    @Mock MmiUtils mMmiUtils;
    @Mock
    ConnectionServiceFocusManager mConnectionServiceFocusManager;
    @Mock Call mActiveCall;
    @Mock Call mHeldCall;
    @Mock Call mNewCall;
    @Mock Call mRingingCall;
    @Mock Resources mResources;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        when(mContext.getResources()).thenReturn(mResources);
        TelecomResourceId.setTelecomContext(mContext);
        mController = new CallSequencingController(mCallsManager, mContext, mClockProxy,
                mAnomalyReporter, mTimeoutsAdapter, mMetricsController, mMmiUtils,
                TELECOM_UI_PACKAGE_NAME, mFeatureFlags);

        when(mActiveCall.getState()).thenReturn(CallState.ACTIVE);
        when(mRingingCall.getState()).thenReturn(CallState.RINGING);
        when(mHeldCall.getState()).thenReturn(CallState.ON_HOLD);

        when(mActiveCall.getId()).thenReturn(ACTIVE_CALL_ID);
        when(mNewCall.getId()).thenReturn(NEW_CALL_ID);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        if (mController != null && mController.getHandler() != null) {
            mController.getHandler().removeCallbacksAndMessages(null);
        }
        TelecomResourceId.setTelecomContext(null);
        super.tearDown();
    }


    @Test
    @SmallTest
    public void testTransactionOutgoingCall_CallNotPermitted() {
        String callingPkg = "testPkg";
        CallAttributes outgoingCallAttributes = getOutgoingCallAttributes();

        // Outgoing call is not permitted
        when(mCallsManager.isOutgoingCallPermitted(mHandle1)).thenReturn(false);
        CompletableFuture<CallTransaction> transactionFuture = mController
                .createTransactionalOutgoingCall("callId", outgoingCallAttributes,
                        new Bundle(), callingPkg);
        OutgoingCallTransactionSequencing transaction = (OutgoingCallTransactionSequencing)
                transactionFuture.getNow(null);
        assertNotNull(transaction);
        assertTrue(transaction.getCallNotPermitted());

        // Call future is null
        when(mCallsManager.isOutgoingCallPermitted(mHandle1)).thenReturn(true);
        when(mCallsManager.startOutgoingCall(any(Uri.class), any(PhoneAccountHandle.class),
                any(Bundle.class), any(UserHandle.class), any(Intent.class), anyString()))
                .thenReturn(null);
        transactionFuture = mController
                .createTransactionalOutgoingCall("callId", outgoingCallAttributes,
                        new Bundle(), callingPkg);
        transaction = (OutgoingCallTransactionSequencing) transactionFuture
                .getNow(null);
        assertNotNull(transaction);
        assertTrue(transaction.getCallNotPermitted());
    }

    @Test
    @SmallTest
    public void testTransactionOutgoingCall() {
        String callingPkg = "testPkg";
        CallAttributes outgoingCallAttributes = getOutgoingCallAttributes();

        when(mCallsManager.isOutgoingCallPermitted(mHandle1)).thenReturn(true);
        when(mCallsManager.startOutgoingCall(any(Uri.class), any(PhoneAccountHandle.class),
                any(Bundle.class), any(UserHandle.class), any(Intent.class), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mNewCall));
        CompletableFuture<CallTransaction> transactionFuture = mController
                .createTransactionalOutgoingCall("callId", outgoingCallAttributes,
                        new Bundle(), callingPkg);
        try {
            OutgoingCallTransactionSequencing transaction = (OutgoingCallTransactionSequencing)
                    transactionFuture.get(SEQUENCING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertNotNull(transaction);
            assertFalse(transaction.getCallNotPermitted());
        } catch (Exception e) {
            fail("Failed to retrieve future in allocated time (" + SEQUENCING_TIMEOUT_MS + ").");
        }
    }

    @SmallTest
    @Test
    public void testAnswerCall() {
        // This will allow holdActiveCallForNewCallWithSequencing to immediately return true
        setActiveCallFocus(null);
        mController.answerCall(mNewCall, 0, CallsManager.REQUEST_ORIGIN_UNKNOWN);
        verify(mCallsManager, timeout(SEQUENCING_TIMEOUT_MS))
                .requestFocusActionAnswerCall(eq(mNewCall), eq(0),
                        eq(CallsManager.REQUEST_ORIGIN_UNKNOWN));
    }

    @SmallTest
    @Test
    public void testAnswerCallFail() {
        setupHoldActiveCallForNewCallFailMocks();
        mController.answerCall(mNewCall, 0, CallsManager.REQUEST_ORIGIN_UNKNOWN);
        verify(mCallsManager, timeout(SEQUENCING_TIMEOUT_MS).times(0))
                .requestFocusActionAnswerCall(eq(mNewCall), eq(0),
                        eq(CallsManager.REQUEST_ORIGIN_UNKNOWN));
    }

    @SmallTest
    @Test
    public void testAnswerCallAcceptedFromTelecom_HoldableManaged() {
        setPhoneAccounts(mNewCall, mActiveCall, false);
        setActiveCallFocus(mActiveCall);
        when(mCallsManager.canHold(mActiveCall)).thenReturn(true);
        when(mActiveCall.hold(anyString())).thenReturn(CompletableFuture.completedFuture(true));

        when(mHeldCall.isSelfManaged()).thenReturn(false);
        when(mNewCall.isSelfManaged()).thenReturn(true);
        mController.answerCall(mNewCall, 0, CallsManager.REQUEST_ORIGIN_TELECOM_DISAMBIGUATION);
        verify(mCallsManager, timeout(SEQUENCING_TIMEOUT_MS).times(1))
                .requestFocusActionAnswerCall(eq(mNewCall), eq(0),
                        eq(CallsManager.REQUEST_ORIGIN_TELECOM_DISAMBIGUATION));
    }

    @SmallTest
    @Test
    public void testAnswerCallAcceptedFromTelecom_NonholdableManaged() {
        setPhoneAccounts(mNewCall, mActiveCall, false);
        setActiveCallFocus(mActiveCall);
        when(mCallsManager.canHold(mActiveCall)).thenReturn(false);

        when(mHeldCall.isSelfManaged()).thenReturn(false);
        when(mNewCall.isSelfManaged()).thenReturn(true);
        mController.answerCall(mNewCall, 0, CallsManager.REQUEST_ORIGIN_TELECOM_DISAMBIGUATION);
        verify(mCallsManager, timeout(SEQUENCING_TIMEOUT_MS).times(1))
                .requestFocusActionAnswerCall(eq(mNewCall), eq(0),
                        eq(CallsManager.REQUEST_ORIGIN_TELECOM_DISAMBIGUATION));
    }

    @SmallTest
    @Test
    public void testSetSelfManagedCallActive() {
        // This will allow holdActiveCallForNewCallWithSequencing to immediately return true
        setActiveCallFocus(null);
        when(mNewCall.isSelfManaged()).thenReturn(true);
        mController.handleSetCallActive(mNewCall);
        verify(mCallsManager, timeout(SEQUENCING_TIMEOUT_MS))
                .requestActionSetActiveCall(eq(mNewCall), anyString());
    }

    @SmallTest
    @Test
    public void testSetSelfManagedCallActiveFail() {
        setupHoldActiveCallForNewCallFailMocks();
        when(mNewCall.isSelfManaged()).thenReturn(true);
        mController.handleSetCallActive(mNewCall);
        verify(mCallsManager, timeout(SEQUENCING_TIMEOUT_MS).times(0))
                .requestActionSetActiveCall(eq(mNewCall), anyString());
    }

    @SmallTest
    @Test
    public void testSetManagedCallActive() {
        // This will allow holdActiveCallForNewCallWithSequencing to immediately return true
        setActiveCallFocus(null);
        mController.handleSetCallActive(mNewCall);
        verify(mCallsManager, timeout(SEQUENCING_TIMEOUT_MS))
                .requestFocusForSetManagedActive(eq(mNewCall));
    }

    @SmallTest
    @Test
    public void testSetManagedCallActiveFail() {
        setupHoldActiveCallForNewCallFailMocks();
        mController.handleSetCallActive(mNewCall);
        verify(mCallsManager, timeout(SEQUENCING_TIMEOUT_MS).times(0))
                .requestFocusForSetManagedActive(eq(mNewCall));
    }

    @SmallTest
    @Test
    public void testTransactionHoldActiveCallForNewCall() throws InterruptedException {
        // This will allow holdActiveCallForNewCallWithSequencing to immediately return true
        setActiveCallFocus(null);
        CountDownLatch latch = new CountDownLatch(1);
        OutcomeReceiver<Boolean, CallException> callback = new OutcomeReceiver<>() {
            @Override
            public void onResult(Boolean result) {
                // Expected result
                latch.countDown();
            }
            @Override
            public void onError(CallException exception) {
            }
        };
        verifyTransactionHoldActiveCallForNewCall(callback, latch);
    }

    @SmallTest
    @Test
    public void testTransactionHoldActiveCallForNewCallFail() {
        setupHoldActiveCallForNewCallFailMocks();
        CountDownLatch latch = new CountDownLatch(1);
        OutcomeReceiver<Boolean, CallException> callback = new OutcomeReceiver<>() {
            @Override
            public void onResult(Boolean result) {
            }

            @Override
            public void onError(CallException exception) {
                // Expected result
                latch.countDown();
            }
        };
        verifyTransactionHoldActiveCallForNewCall(callback, latch);
    }

    @Test
    @SmallTest
    public void testHoldCallForNewCall_NoActiveCall() {
        setActiveCallFocus(null);
        CompletableFuture<Boolean> resultFuture = mController
                .holdActiveCallForNewCallWithSequencing(mNewCall,
                        CallsManager.REQUEST_ORIGIN_UNKNOWN);
        assertTrue(waitForFutureResult(resultFuture, false));
    }

    @Test
    @SmallTest
    public void testHoldCallForNewCall_CanHold() {
        setPhoneAccounts(mNewCall, mActiveCall, false);
        setActiveCallFocus(mActiveCall);
        when(mCallsManager.canHold(mActiveCall)).thenReturn(true);
        when(mActiveCall.hold(anyString())).thenReturn(CompletableFuture.completedFuture(true));

        // Cross phone account case (sequencing enabled)
        assertFalse(CallSequencingController.arePhoneAccountsSame(mNewCall, mActiveCall));
        CompletableFuture<Boolean> resultFuture = mController
                .holdActiveCallForNewCallWithSequencing(mNewCall,
                        CallsManager.REQUEST_ORIGIN_UNKNOWN);
        assertTrue(waitForFutureResult(resultFuture, false));

        // Same phone account case
        setPhoneAccounts(mNewCall, mActiveCall, true);
        assertTrue(CallSequencingController.arePhoneAccountsSame(mNewCall, mActiveCall));
        resultFuture = mController.holdActiveCallForNewCallWithSequencing(mNewCall,
                CallsManager.REQUEST_ORIGIN_UNKNOWN);
        assertTrue(waitForFutureResult(resultFuture, false));
    }

    @Test
    @SmallTest
    public void testHoldCallForNewCall_SupportsHold() {
        setPhoneAccounts(mNewCall, mActiveCall, false);
        setActiveCallFocus(mActiveCall);
        when(mCallsManager.canHold(mActiveCall)).thenReturn(false);
        when(mCallsManager.supportsHold(mActiveCall)).thenReturn(true);
        when(mCallsManager.getFirstCallWithState(anyInt())).thenReturn(mHeldCall);
        when(mHeldCall.isSelfManaged()).thenReturn(true);
        when(mNewCall.isSelfManaged()).thenReturn(false);
        when(mHeldCall.disconnect()).thenReturn(CompletableFuture.completedFuture(true));
        when(mActiveCall.hold()).thenReturn(CompletableFuture.completedFuture(true));

        // Verify that we abort transaction when there's a new (VOIP) call and we're trying to
        // disconnect the active (carrier) call.
        assertFalse(CallSequencingController.arePhoneAccountsSame(mNewCall, mActiveCall));
        CompletableFuture<Boolean> resultFuture = mController
                .holdActiveCallForNewCallWithSequencing(mNewCall,
                        CallsManager.REQUEST_ORIGIN_UNKNOWN);
        verify(mHeldCall, timeout(SEQUENCING_TIMEOUT_MS)).disconnect();
        verify(mActiveCall, timeout(SEQUENCING_TIMEOUT_MS)).hold();
        verify(mNewCall).increaseHeldByThisCallCount();
        assertTrue(waitForFutureResult(resultFuture, false));
    }

    @Test
    @SmallTest
    public void testHoldCallForNewCall_SupportsHold_NoHeldCall() {
        setPhoneAccounts(mNewCall, mActiveCall, false);
        setActiveCallFocus(mActiveCall);
        when(mCallsManager.canHold(mActiveCall)).thenReturn(false);
        when(mCallsManager.supportsHold(mActiveCall)).thenReturn(true);
        when(mCallsManager.getFirstCallWithState(anyInt())).thenReturn(null);
        when(mActiveCall.hold()).thenReturn(CompletableFuture.completedFuture(true));

        // Cross phone account case (sequencing enabled)
        assertFalse(CallSequencingController.arePhoneAccountsSame(mNewCall, mActiveCall));
        CompletableFuture<Boolean> resultFuture = mController
                .holdActiveCallForNewCallWithSequencing(mNewCall,
                        CallsManager.REQUEST_ORIGIN_UNKNOWN);
        verify(mActiveCall, timeout(SEQUENCING_TIMEOUT_MS)).hold();
        verify(mNewCall).increaseHeldByThisCallCount();
        assertTrue(waitForFutureResult(resultFuture, false));
    }

    @Test
    @SmallTest
    public void testHoldCallForNewCall_DoesNotSupportHold_Disconnect() {
        setPhoneAccounts(mNewCall, mActiveCall, false);
        setActiveCallFocus(mActiveCall);
        when(mCallsManager.getCalls()).thenReturn(Collections.singletonList(mActiveCall));
        when(mCallsManager.canHold(mActiveCall)).thenReturn(false);
        when(mCallsManager.supportsHold(mActiveCall)).thenReturn(false);
        when(mActiveCall.disconnect(anyString())).thenReturn(
                CompletableFuture.completedFuture(true));
        when(mActiveCall.isEmergencyCall()).thenReturn(false);

        assertFalse(CallSequencingController.arePhoneAccountsSame(mNewCall, mActiveCall));
        CompletableFuture<Boolean> resultFuture = mController
                .holdActiveCallForNewCallWithSequencing(mNewCall,
                        CallsManager.REQUEST_ORIGIN_UNKNOWN);
        verify(mActiveCall, timeout(SEQUENCING_TIMEOUT_MS)).disconnect(anyString());
        assertTrue(waitForFutureResult(resultFuture, false));
    }

    @Test
    @SmallTest
    public void testHoldCallForNewCallFail_SupportsHold_VoipPstn() {
        setPhoneAccounts(mNewCall, mActiveCall, false);
        setActiveCallFocus(mActiveCall);
        when(mCallsManager.canHold(mActiveCall)).thenReturn(false);
        when(mCallsManager.supportsHold(mActiveCall)).thenReturn(true);
        when(mCallsManager.getFirstCallWithState(anyInt())).thenReturn(mHeldCall);
        when(mHeldCall.isSelfManaged()).thenReturn(false);
        when(mNewCall.isSelfManaged()).thenReturn(true);

        // Verify that we abort transaction when there's a new (VOIP) call and we're trying to
        // disconnect the active (carrier) call.
        assertFalse(CallSequencingController.arePhoneAccountsSame(mNewCall, mActiveCall));
        CompletableFuture<Boolean> resultFuture = mController
                .holdActiveCallForNewCallWithSequencing(mNewCall,
                        CallsManager.REQUEST_ORIGIN_UNKNOWN);
        assertFalse(waitForFutureResult(resultFuture, true));
    }

    @Test
    @SmallTest
    public void testHoldCallForNewCall_DoesNotSupportHold_SameManagedPA() {
        setPhoneAccounts(mNewCall, mActiveCall, true);
        setActiveCallFocus(mActiveCall);
        when(mCallsManager.canHold(mActiveCall)).thenReturn(false);
        when(mCallsManager.supportsHold(mActiveCall)).thenReturn(false);
        when(mActiveCall.isEmergencyCall()).thenReturn(false);

        assertTrue(CallSequencingController.arePhoneAccountsSame(mNewCall, mActiveCall));
        CompletableFuture<Boolean> resultFuture = mController
                .holdActiveCallForNewCallWithSequencing(mNewCall,
                        CallsManager.REQUEST_ORIGIN_UNKNOWN);
        assertTrue(waitForFutureResult(resultFuture, true));
    }

    @Test
    @SmallTest
    public void testHoldCallForNewCallFail_DoesNotSupportHold_Reject() {
        setPhoneAccounts(mNewCall, mActiveCall, false);
        setActiveCallFocus(mActiveCall);
        when(mCallsManager.canHold(mActiveCall)).thenReturn(false);
        when(mCallsManager.supportsHold(mActiveCall)).thenReturn(false);
        when(mNewCall.reject(anyBoolean(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(mActiveCall.isEmergencyCall()).thenReturn(true);

        assertFalse(CallSequencingController.arePhoneAccountsSame(mNewCall, mActiveCall));
        CompletableFuture<Boolean> resultFuture = mController
                .holdActiveCallForNewCallWithSequencing(mNewCall,
                        CallsManager.REQUEST_ORIGIN_UNKNOWN);
        verify(mNewCall, timeout(SEQUENCING_TIMEOUT_MS)).reject(
                anyBoolean(), anyString(), anyString());
        assertFalse(waitForFutureResult(resultFuture, true));
    }

    @Test
    @SmallTest
    public void testHoldCallForNewCallFail_DoesNotSupportHold_Abort() {
        setPhoneAccounts(mNewCall, mActiveCall, false);
        setActiveCallFocus(mActiveCall);
        when(mCallsManager.canHold(mActiveCall)).thenReturn(false);
        when(mCallsManager.supportsHold(mActiveCall)).thenReturn(false);
        when(mActiveCall.isEmergencyCall()).thenReturn(false);
        when(mActiveCall.isSelfManaged()).thenReturn(false);
        when(mNewCall.isSelfManaged()).thenReturn(true);

        assertFalse(CallSequencingController.arePhoneAccountsSame(mNewCall, mActiveCall));
        CompletableFuture<Boolean> resultFuture = mController
                .holdActiveCallForNewCallWithSequencing(mNewCall,
                        CallsManager.REQUEST_ORIGIN_UNKNOWN);
        assertFalse(waitForFutureResult(resultFuture, true));
    }

    @Test
    @SmallTest
    public void testUnholdCallNoActiveCall() {
        setActiveCallFocus(null);
        mController.unholdCall(mHeldCall);
        verify(mCallsManager).requestActionUnholdCall(eq(mHeldCall), eq(null));
    }

    @Test
    @SmallTest
    public void testUnholdCallSwapCase() {
        when(mActiveCall.can(eq(Connection.CAPABILITY_SUPPORT_HOLD))).thenReturn(true);
        when(mActiveCall.hold(anyString())).thenReturn(CompletableFuture.completedFuture(true));
        when(mActiveCall.isLocallyDisconnecting()).thenReturn(false);
        setPhoneAccounts(mHeldCall, mActiveCall, false);
        setActiveCallFocus(mActiveCall);

        mController.unholdCall(mHeldCall);
        assertFalse(CallSequencingController.arePhoneAccountsSame(mActiveCall, mHeldCall));
        verify(mActiveCall).hold(anyString());
        verify(mCallsManager, timeout(SEQUENCING_TIMEOUT_MS))
                .requestActionUnholdCall(eq(mHeldCall), eq(ACTIVE_CALL_ID));
    }

    @Test
    @SmallTest
    public void testUnholdCallFail_DoesNotSupportHold() {
        when(mActiveCall.can(eq(Connection.CAPABILITY_SUPPORT_HOLD))).thenReturn(false);
        when(mActiveCall.isEmergencyCall()).thenReturn(true);
        when(mActiveCall.isLocallyDisconnecting()).thenReturn(false);
        setPhoneAccounts(mHeldCall, mActiveCall, false);
        setActiveCallFocus(mActiveCall);

        // Emergency call case
        mController.unholdCall(mHeldCall);
        assertFalse(CallSequencingController.arePhoneAccountsSame(mActiveCall, mHeldCall));
        verify(mCallsManager, timeout(SEQUENCING_TIMEOUT_MS).times(0))
                .requestActionUnholdCall(eq(mHeldCall), anyString());
    }

    @Test
    @SmallTest
    public void testUnholdFail() {
        // Fail the hold.
        when(mActiveCall.can(eq(Connection.CAPABILITY_SUPPORT_HOLD))).thenReturn(true);
        when(mActiveCall.hold(anyString())).thenReturn(CompletableFuture.completedFuture(false));
        when(mActiveCall.isLocallyDisconnecting()).thenReturn(false);
        // Use different phone accounts so that the sequencing code path is hit.
        setPhoneAccounts(mHeldCall, mActiveCall, false);
        setActiveCallFocus(mActiveCall);

        mController.unholdCall(mHeldCall);
        assertFalse(CallSequencingController.arePhoneAccountsSame(mActiveCall, mHeldCall));
        verify(mActiveCall).hold(anyString());
        // Verify unhold is never reached.
        verify(mCallsManager, never())
                .requestActionUnholdCall(eq(mHeldCall), anyString());
    }

    @SmallTest
    @Test
    public void testMakeRoomForOutgoingEmergencyCall_SamePkg() {
        // Ensure that the live call and emergency call are from the same pkg.
        when(mActiveCall.getTargetPhoneAccount()).thenReturn(mHandle1);
        when(mNewCall.getTargetPhoneAccount()).thenReturn(mHandle1);
        when(mRingingCall.getTargetPhoneAccount()).thenReturn(mHandle2);
        setupMakeRoomForOutgoingEmergencyCallMocks();

        CompletableFuture<Boolean> future = mController.makeRoomForOutgoingCall(true, mNewCall);
        verify(mRingingCall, timeout(SEQUENCING_TIMEOUT_MS))
                .reject(anyBoolean(), eq(null), anyString());
        // Verify we don't send the hold request when the normal call and ECC are from the same
        // phone accounts (this task is left up to Telephony to handle).
        verify(mActiveCall, timeout(SEQUENCING_TIMEOUT_MS).times(0)).hold(anyString());
        assertTrue(waitForFutureResult(future, false));
    }

    @SmallTest
    @Test
    public void testMakeRoomForOutgoingEmergencyCall_CanHold() {
        // Ensure that the live call and emergency call are from different pkgs.
        when(mActiveCall.getTargetPhoneAccount()).thenReturn(mHandle1);
        when(mNewCall.getTargetPhoneAccount()).thenReturn(mHandle2);
        when(mRingingCall.getTargetPhoneAccount()).thenReturn(mHandle2);
        setupMakeRoomForOutgoingEmergencyCallMocks();

        CompletableFuture<Boolean> future = mController.makeRoomForOutgoingCall(true, mNewCall);
        verify(mRingingCall, timeout(SEQUENCING_TIMEOUT_MS))
                .reject(anyBoolean(), eq(null), anyString());
        verify(mActiveCall, timeout(SEQUENCING_TIMEOUT_MS)).hold(anyString());
        assertTrue(waitForFutureResult(future, false));
    }

    @SmallTest
    @Test
    public void testMakeRoomForOutgoingEmergencyCall_DoesNotSupportHoldingEmergency() {
        setupMakeRoomForOutgoingEmergencyCallMocks();
        when(mCallsManager.getCalls()).thenReturn(List.of(mActiveCall, mRingingCall));
        when(mActiveCall.getTargetPhoneAccount()).thenReturn(mHandle1);
        // Set the KEY_ALLOW_HOLD_CALL_DURING_EMERGENCY_BOOL carrier config to false for the active
        // call's phone account.
        PersistableBundle bundle = new PersistableBundle();
        bundle.putBoolean(CarrierConfigManager.KEY_ALLOW_HOLD_CALL_DURING_EMERGENCY_BOOL, false);
        when(mCallsManager.getCarrierConfigForPhoneAccount(eq(mHandle1))).thenReturn(bundle);
        when(mNewCall.getTargetPhoneAccount()).thenReturn(mHandle2);
        when(mRingingCall.getTargetPhoneAccount()).thenReturn(mHandle2);

        mController.makeRoomForOutgoingCall(true, mNewCall);
        // Verify that the active call got disconnected as it doesn't support holding for emergency.
        verify(mActiveCall, timeout(SEQUENCING_TIMEOUT_MS)).disconnect(anyString());
    }

    @Test
    @SmallTest
    public void testMakeRoomForOutgoingCall() {
        setupMakeRoomForOutgoingCallMocks();
        when(mActiveCall.hold(anyString())).thenReturn(CompletableFuture.completedFuture(true));
        when(mCallsManager.canHold(mActiveCall)).thenReturn(true);

        CompletableFuture<Boolean> future = mController.makeRoomForOutgoingCall(false, mNewCall);
        verify(mActiveCall, timeout(SEQUENCING_TIMEOUT_MS)).hold(anyString());
        assertTrue(waitForFutureResult(future, false));
    }

    @Test
    @SmallTest
    public void testMakeRoomForOutgoingCallFail_MaxCalls() {
        setupMakeRoomForOutgoingCallMocks();
        when(mCallsManager.canHold(mActiveCall)).thenReturn(false);
        when(mCallsManager.hasMaximumManagedHoldingCalls(mNewCall)).thenReturn(true);

        CompletableFuture<Boolean> future = mController.makeRoomForOutgoingCall(false, mNewCall);
        verify(mNewCall).setStartFailCause(eq(CallFailureCause.MAX_OUTGOING_CALLS));
        assertFalse(waitForFutureResult(future, true));
    }

    @Test
    @SmallTest
    public void testMakeRoomForOutgoingCall_MaxOutgoingCalls_SelectPhoneAccount() {
        setupMakeRoomForOutgoingCallMocks();

        // Setup: New call is in SELECT_PHONE_ACCOUNT state.
        when(mNewCall.getState()).thenReturn(CallState.SELECT_PHONE_ACCOUNT);
        when(mNewCall.getCreationTimeMillis()).thenReturn(1000L);
        when(mNewCall.getHandle()).thenReturn(Uri.parse("tel:1234"));

        // Setup: We have another dialing call.
        Call dialingCall = mock(Call.class);
        when(dialingCall.getState()).thenReturn(CallState.DIALING);
        when(dialingCall.getCreationTimeMillis()).thenReturn(500L); // different creation time
        when(dialingCall.getHandle()).thenReturn(Uri.parse("tel:5678")); // different handle
        when(dialingCall.getId()).thenReturn("TC@8");

        // Setup: CallsManager configuration.
        when(mCallsManager.hasMaximumOutgoingCalls(mNewCall)).thenReturn(true);
        when(mCallsManager.getFirstCallWithStateExcept(mNewCall, CallsManager.OUTGOING_CALL_STATES))
                .thenReturn(dialingCall);

        // Mock Context and Executor.
        when(mContext.getMainExecutor()).thenReturn(command -> command.run());
        when(mContext.getPackageName()).thenReturn("com.android.server.telecom");
        TelecomResourceId.setTelecomContext(mContext);

        // Mock Resources for dialog.
        when(mResources.getText(anyInt())).thenReturn("Dummy Error Message");

        // Run the test
        CompletableFuture<Boolean> future = mController.makeRoomForOutgoingCall(false, mNewCall);

        // Verify:
        // 1. Future returns false.
        try {
            assertFalse(future.get(SEQUENCING_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            fail("Exception occurred: " + e);
        }
        // 2. setStartFailCause was called on mNewCall.
        verify(mNewCall).setStartFailCause(eq(CallFailureCause.MAX_OUTGOING_CALLS));
        // 3. Error dialog activity was started via context.
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, timeout(SEQUENCING_TIMEOUT_MS)).startActivityAsUser(
                intentCaptor.capture(), any(UserHandle.class));
        Intent intent = intentCaptor.getValue();
        assertNotNull(intent);
        assertEquals("Dummy Error Message", intent.getCharSequenceExtra(
                UiConstants.ERROR_MESSAGE_STRING_EXTRA));
    }





    @Test
    @SmallTest
    public void testMakeRoomForOutgoingCallFail_CannotHold() {
        setupMakeRoomForOutgoingCallMocks();
        when(mCallsManager.canHold(mActiveCall)).thenReturn(false);
        when(mCallsManager.hasMaximumManagedHoldingCalls(mNewCall)).thenReturn(false);

        CompletableFuture<Boolean> future = mController.makeRoomForOutgoingCall(false, mNewCall);
        verify(mNewCall).setStartFailCause(eq(CallFailureCause.CANNOT_HOLD_CALL));
        assertFalse(waitForFutureResult(future, true));
    }

    @Test
    @SmallTest
    @EnableFlags(Flags.FLAG_ADD_ESCAPE_HATCH_FOR_STUCK_VOIP)
    public void testMakeRoomForOutgoingCallFail_RingingCall_FlagEnabled() {
        when(mNewCall.isSelfManaged()).thenReturn(false);
        when(mCallsManager.hasManagedRingingOrSimulatedRingingCall()).thenReturn(true);
        when(mCallsManager.getRingingOrSimulatedRingingCall()).thenReturn(mRingingCall);
        when(mRingingCall.getTargetPhoneAccountLabel()).thenReturn("TestApp");

        CompletableFuture<Boolean> future = mController.makeRoomForOutgoingCall(false, mNewCall);
        assertFalse(waitForFutureResult(future, true));

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext).startActivityAsUser(intentCaptor.capture(), eq(UserHandle.CURRENT));
        Intent intent = intentCaptor.getValue();
        assertEquals(UiConstants.COMPONENT_CONFIRM_CALL_DIALOG,
                intent.getComponent().getClassName());
        assertEquals("TestApp", intent.getCharSequenceExtra(
                UiConstants.EXTRA_ONGOING_APP_NAME).toString());
        assertEquals(NEW_CALL_ID, intent.getStringExtra(
                UiConstants.EXTRA_OUTGOING_CALL_ID));
    }

    @Test
    @SmallTest
    @DisableFlags(Flags.FLAG_ADD_ESCAPE_HATCH_FOR_STUCK_VOIP)
    public void testMakeRoomForOutgoingCallFail_RingingCall_FlagDisabled() {
        when(mNewCall.isSelfManaged()).thenReturn(false);
        when(mCallsManager.hasManagedRingingOrSimulatedRingingCall()).thenReturn(true);

        CompletableFuture<Boolean> future = mController.makeRoomForOutgoingCall(false, mNewCall);
        assertFalse(waitForFutureResult(future, true));

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, timeout(SEQUENCING_TIMEOUT_MS)).startActivityAsUser(
                intentCaptor.capture(), eq(UserHandle.CURRENT));
        Intent intent = intentCaptor.getValue();
        assertEquals(UiConstants.COMPONENT_ERROR_DIALOG,
                intent.getComponent().getClassName());
    }

    @Test
    @SmallTest
    public void testDisconnectCallSuccess() {
        when(mActiveCall.disconnect()).thenReturn(CompletableFuture.completedFuture(true));
        int previousState = CallState.ACTIVE;
        mController.disconnectCall(mActiveCall, previousState);
        verify(mCallsManager).notifyCallStateChangeForDisconnect(any(Call.class), anyInt());
        verify(mCallsManager, timeout(SEQUENCING_TIMEOUT_MS))
                .processDisconnectCallAndCleanup(eq(mActiveCall), eq(previousState));
    }

    @Test
    @SmallTest
    public void testDisconnectCallFail() {
        when(mActiveCall.disconnect()).thenReturn(CompletableFuture.completedFuture(false));
        int previousState = CallState.ACTIVE;
        mController.disconnectCall(mActiveCall, previousState);
        verify(mCallsManager).notifyCallStateChangeForDisconnect(any(Call.class), anyInt());
        verify(mCallsManager, timeout(SEQUENCING_TIMEOUT_MS).times(0))
                .processDisconnectCallAndCleanup(eq(mActiveCall), eq(previousState));
        verify(mCallsManager, timeout(SEQUENCING_TIMEOUT_MS).times(2))
                .notifyCallStateChangeForDisconnect(any(Call.class), anyInt());
        verify(mActiveCall, timeout(SEQUENCING_TIMEOUT_MS)).setLocallyDisconnecting(eq(false));
    }

    @Test
    @SmallTest
    public void testMmiCodeRestrictionReject() {
        // Verify that when calls are detected across other phone accounts,
        // that the MMI code is rejected.
        when(mNewCall.getTargetPhoneAccount()).thenReturn(mHandle1);
        when(mCallsManager.getNumCallsWithStateWithoutHandle(CALL_FILTER_ALL, mNewCall,
                mHandle1, ONGOING_CALL_STATES)).thenReturn(1);
        assertTrue(mController.hasMmiCodeRestriction(mNewCall));
        verify(mNewCall).setOverrideDisconnectCauseCode(any(DisconnectCause.class));
    }

    @Test
    @SmallTest
    public void testMmiCodeRestrictionAllow() {
        // Verify that when no calls are detected across other phone accounts,
        // that the MMI code is allowed.
        when(mNewCall.getTargetPhoneAccount()).thenReturn(mHandle1);
        when(mCallsManager.getNumCallsWithStateWithoutHandle(CALL_FILTER_ALL, mNewCall,
                mHandle1, ONGOING_CALL_STATES)).thenReturn(0);
        assertFalse(mController.hasMmiCodeRestriction(mNewCall));
        verify(mNewCall, times(0)).setOverrideDisconnectCauseCode(any(DisconnectCause.class));
    }

    /**
     * Verifies that when an incoming call arrives on the same PhoneAccount as an active,
     * unholdable Transactional call, Telecom explicitly disconnects the active call to ensure
     * focus can be granted to the new call, while ensuring the new call itself is not disconnected.
     */
    @Test
    @SmallTest
    public void testHoldCallForNewCall_TransactionalSameAccount_Disconnect() {
        setPhoneAccounts(mNewCall, mActiveCall, true); // Same account
        setActiveCallFocus(mActiveCall);
        when(mCallsManager.canHold(mActiveCall)).thenReturn(false);
        when(mCallsManager.supportsHold(mActiveCall)).thenReturn(false);
        when(mActiveCall.isTransactionalCall()).thenReturn(true);
        // Include BOTH calls in the list to verify exclusion logic
        when(mCallsManager.getCalls()).thenReturn(Arrays.asList(mActiveCall, mNewCall));
        when(mActiveCall.disconnect(anyString())).thenReturn(
                CompletableFuture.completedFuture(true));
        when(mNewCall.disconnect(anyString())).thenReturn(
                CompletableFuture.completedFuture(true));

        assertTrue(CallSequencingController.arePhoneAccountsSame(mNewCall, mActiveCall));
        CompletableFuture<Boolean> resultFuture = mController
                .holdActiveCallForNewCallWithSequencing(mNewCall,
                        CallsManager.REQUEST_ORIGIN_UNKNOWN);

        // This should now disconnect the active call
        verify(mActiveCall, timeout(SEQUENCING_TIMEOUT_MS)).disconnect(anyString());
        // But it should NOT disconnect the new call
        verify(mNewCall, never()).disconnect(anyString());
        assertTrue(waitForFutureResult(resultFuture, false));
    }

    @Test
    @SmallTest
    public void testMaybeAddAnsweringCallDropsFg_VoLTE_VoWiFi() {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putBoolean(CarrierConfigManager.KEY_SHOW_VOWIFI_DROP_DIALOG_ON_DSDS_BOOL, true);
        when(mCallsManager.getCarrierConfigForPhoneAccount(any())).thenReturn(bundle);

        when(mCallsManager.isDsdaCallingPossible()).thenReturn(false);
        when(mActiveCall.wasVolte()).thenReturn(true);
        when(mNewCall.wasWifi()).thenReturn(true);
        when(mActiveCall.getTargetPhoneAccountLabel()).thenReturn("ActiveApp");
        when(mCallsManager.supportsHold(mActiveCall)).thenReturn(true);
        setPhoneAccounts(mNewCall, mActiveCall, false);

        mController.maybeAddAnsweringCallDropsFg(mActiveCall, mNewCall);

        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mNewCall).putInternalExtras(bundleCaptor.capture());
        Bundle resultBundle = bundleCaptor.getValue();
        assertTrue(resultBundle.getBoolean(Connection.EXTRA_ANSWERING_DROPS_FG_CALL));
        assertEquals("ActiveApp", resultBundle.getCharSequence(
                Connection.EXTRA_ANSWERING_DROPS_FG_CALL_APP_NAME));
    }

    @Test
    @SmallTest
    public void testMaybeAddAnsweringCallDropsFg_VoLTE_VoWiFi_ConfigDisabled() {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putBoolean(CarrierConfigManager.KEY_SHOW_VOWIFI_DROP_DIALOG_ON_DSDS_BOOL, false);
        when(mCallsManager.getCarrierConfigForPhoneAccount(any())).thenReturn(bundle);

        when(mCallsManager.isDsdaCallingPossible()).thenReturn(false);
        when(mActiveCall.wasVolte()).thenReturn(true);
        when(mNewCall.wasWifi()).thenReturn(true);
        when(mActiveCall.getTargetPhoneAccountLabel()).thenReturn("ActiveApp");
        when(mCallsManager.supportsHold(mActiveCall)).thenReturn(true);
        setPhoneAccounts(mNewCall, mActiveCall, false);

        mController.maybeAddAnsweringCallDropsFg(mActiveCall, mNewCall);

        verify(mNewCall, never()).putInternalExtras(any(Bundle.class));
    }

    @Test
    @SmallTest
    public void testMaybeAddAnsweringCallDropsFg_VoLTE_VoWiFi_Dsda() {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putBoolean(CarrierConfigManager.KEY_SHOW_VOWIFI_DROP_DIALOG_ON_DSDS_BOOL, true);
        when(mCallsManager.getCarrierConfigForPhoneAccount(any())).thenReturn(bundle);
        when(mCallsManager.isDsdaCallingPossible()).thenReturn(true);
        when(mActiveCall.wasVolte()).thenReturn(true);
        when(mNewCall.wasWifi()).thenReturn(true);
        when(mCallsManager.supportsHold(mActiveCall)).thenReturn(true);
        when(mActiveCall.getTargetPhoneAccountLabel()).thenReturn("ActiveApp");
        setPhoneAccounts(mNewCall, mActiveCall, false);

        mController.maybeAddAnsweringCallDropsFg(mActiveCall, mNewCall);
        verify(mNewCall, never()).putInternalExtras(any(Bundle.class));
    }

    @Test
    @SmallTest
    public void testMaybeAddAnsweringCallDropsFg_NoVoLTE_VoWiFi() {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putBoolean(CarrierConfigManager.KEY_SHOW_VOWIFI_DROP_DIALOG_ON_DSDS_BOOL, true);
        when(mCallsManager.getCarrierConfigForPhoneAccount(any())).thenReturn(bundle);
        when(mActiveCall.wasVolte()).thenReturn(false);
        when(mNewCall.wasWifi()).thenReturn(true);
        setPhoneAccounts(mNewCall, mActiveCall, false);
        when(mCallsManager.supportsHold(mActiveCall)).thenReturn(true);

        mController.maybeAddAnsweringCallDropsFg(mActiveCall, mNewCall);

        verify(mNewCall, never()).putInternalExtras(any(Bundle.class));
    }

    /* Helpers */
    private void setPhoneAccounts(Call call1, Call call2, boolean useSamePhoneAccount) {
        when(call1.getTargetPhoneAccount()).thenReturn(mHandle1);
        when(call1.getDelegatePhoneAccountHandle()).thenReturn(mHandle1);
        when(call2.getTargetPhoneAccount()).thenReturn(useSamePhoneAccount ? mHandle1 : mHandle2);
        when(call2.getDelegatePhoneAccountHandle())
                .thenReturn(useSamePhoneAccount ? mHandle1 : mHandle2);
    }

    private void setActiveCallFocus(Call call) {
        when(mCallsManager.getConnectionServiceFocusManager())
                .thenReturn(mConnectionServiceFocusManager);
        when(mConnectionServiceFocusManager.getCurrentFocusCall()).thenReturn(call);
    }

    private void setupMakeRoomForOutgoingEmergencyCallMocks() {
        when(mNewCall.isEmergencyCall()).thenReturn(true);
        when(mCallsManager.hasRingingOrSimulatedRingingCall()).thenReturn(true);
        when(mCallsManager.getRingingOrSimulatedRingingCall()).thenReturn(mRingingCall);
        when(mCallsManager.hasMaximumLiveCalls(mNewCall)).thenReturn(true);
        when(mCallsManager.getFirstCallWithLiveState(mNewCall)).thenReturn(mActiveCall);
        when(mCallsManager.hasMaximumOutgoingCalls(mNewCall)).thenReturn(false);
        when(mCallsManager.hasMaximumManagedHoldingCalls(mNewCall)).thenReturn(false);
        when(mCallsManager.canHold(mActiveCall)).thenReturn(true);

        // Setup analytics mocks

        // Setup ecall related checks
        setupEmergencyCallPaCapabilities();
        setupCarrierConfigAllowEmergencyCallHold();

        // Setup CompletableFuture mocking for call actions
        when(mRingingCall.reject(anyBoolean(), eq(null), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(mActiveCall.hold(anyString())).thenReturn(
                CompletableFuture.completedFuture(true));
    }

    private void setupEmergencyCallPaCapabilities() {
        PhoneAccount pa = mock(PhoneAccount.class);
        PhoneAccountRegistrar paRegistrar = mock(PhoneAccountRegistrar.class);
        when(mCallsManager.getPhoneAccountRegistrar()).thenReturn(paRegistrar);
        when(paRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class))).thenReturn(pa);
        when(pa.getCapabilities()).thenReturn(PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS);
    }

    private void setupCarrierConfigAllowEmergencyCallHold() {
        PersistableBundle bundle = mock(PersistableBundle.class);
        when(mCallsManager.getCarrierConfigForPhoneAccount(any(PhoneAccountHandle.class)))
                .thenReturn(bundle);
        when(bundle.getBoolean(
                CarrierConfigManager.KEY_ALLOW_HOLD_CALL_DURING_EMERGENCY_BOOL, true))
                .thenReturn(true);
    }

    private void setupMakeRoomForOutgoingCallMocks() {
        when(mCallsManager.hasMaximumLiveCalls(mNewCall)).thenReturn(true);
        when(mCallsManager.getFirstCallWithLiveState(mNewCall)).thenReturn(mActiveCall);
        setPhoneAccounts(mActiveCall, mNewCall, false);
        when(mActiveCall.isConference()).thenReturn(false);
        when(mCallsManager.hasMaximumOutgoingCalls(mNewCall)).thenReturn(false);
    }

    private void setupHoldActiveCallForNewCallFailMocks() {
        // Setup holdActiveCallForNewCallWithSequencing to fail.
        setPhoneAccounts(mNewCall, mActiveCall, false);
        setActiveCallFocus(mActiveCall);
        when(mCallsManager.canHold(mActiveCall)).thenReturn(true);
        when(mActiveCall.hold(anyString())).thenReturn(CompletableFuture.completedFuture(false));
    }

    private void verifyTransactionHoldActiveCallForNewCall(
            OutcomeReceiver<Boolean, CallException> callback, CountDownLatch latch) {
        mController.transactionHoldPotentialActiveCallForNewCallSequencing(mNewCall, callback);
        while (latch.getCount() > 0) {
            try {
                latch.await(SEQUENCING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // do nothing
            }
        }
        assertEquals(latch.getCount(), 0);
    }

    private CallAttributes getOutgoingCallAttributes() {
        return new CallAttributes.Builder(mHandle1,
                CallAttributes.DIRECTION_OUTGOING, TEST_NAME, TEST_URI)
                .setCallType(CallAttributes.AUDIO_CALL)
                .setCallCapabilities(CallAttributes.SUPPORTS_SET_INACTIVE)
                .build();
    }

    private boolean waitForFutureResult(CompletableFuture<Boolean> future, boolean defaultValue) {
        boolean result = defaultValue;
        try {
            result = future.get(SEQUENCING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Pass through
        }
        return result;
    }
}

