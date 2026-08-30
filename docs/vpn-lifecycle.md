# VPN lifecycle and ownership

Runtime mutations enter the serialized lifecycle executor through `VpnRuntimeCoordinator`
commands (`Start`, `Stop`, outbound update, runtime-settings apply, Bridge recovery, and focused
auxiliary commands). This is a first ownership extraction: Android framework callbacks remain in
`TcptunVpnService`, while the coordinator owns mutation admission and in-flight accounting.
Delayed recovery still uses the same existing scheduler and all safety checks below remain
authoritative.

## Resource state machine

```text
Idle -> Preparing -> TunTransferPending -> StartPending -> SessionOwned
                                                     -> Stopping
Stopping -> CallbacksOwned -> Idle
                     \-> Closed after successful Engine.Close
```

`BridgeResourceStateMachine` claims cleanup obligations before calls that can partially
succeed across JNI. `ExclusiveResourceOwner<ParcelFileDescriptor>` prevents replacement or
double-close from silently taking ownership of an existing TUN.

## Start transaction

1. Acquire the process runtime lease.
2. Establish the Android TUN and retain the original `ParcelFileDescriptor`.
3. Install callbacks and optional event registrations.
4. Configure the core and log level.
5. Mark TUN transfer pending, call `SetTun`, and mark start pending.
6. Start the configured session and record the exact native session ID.
7. Wait for core-ready and a bounded local SOCKS authentication/request probe before publishing `Running`.
   The probe does not dial an outbound. The same check runs after bridge replacement.

If Configure, SetTun, or Start fails, the state machine retains exactly the cleanup
obligations that may have crossed into native code. The stop controller then performs the
same ordered cleanup used by a normal stop.

## Stop contract

Stop invalidates the active epoch first, then:

1. Call `Stop()`.
2. If needed, call `WaitStopped(exactSessionId, timeout)`.
3. If the session remains unsettled, call `Abort()`.
4. Keep native and callback ownership if Abort also fails.
5. Only after native ownership is gone, unregister events and clear callbacks.
6. Only after that, close the Android-owned TUN descriptor and release the runtime lease.

The original Android FD is deliberately retained while `nativeStopRequired` is true. A
shutdown error after native settlement is reportable but does not block safe reuse.

## Destroy and replacement

`onDestroy()` uses a bounded lifecycle deadline. If the lifecycle worker or native runtime
does not settle before the deadline, cleanup is retained for safe process teardown instead of
running a second teardown thread against JNI state. A new service instance must acquire the
runtime lease, so it cannot create a second engine while the old one is still releasing.

## Callback safety

Bridge, flow, and network callbacks carry an epoch or generation. Old session events and
non-increasing sequence numbers are ignored before they can update `TcptunState`.

## Network handover

Android network callbacks feed ranked selection. A real handover from one available network
to another can schedule one bounded bridge recovery. A temporary no-network interval does
not stop the VPN or trigger an unnecessary rebuild when the same network returns. Explicit
user stop invalidates pending recovery work.
