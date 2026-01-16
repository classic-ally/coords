# Coords iOS

## Development Notes

### Testing Background Tasks

The iOS app uses `BGTaskScheduler` for background refresh. Xcode's **Debug → Simulate Background Fetch** does NOT work with BGTaskScheduler (it's for the legacy API).

To trigger a background refresh task:

1. Run the app in the debugger
2. Pause execution (Debug → Pause)
3. In the lldb console, run:
   ```
   e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"sh.bentley.transponder.refresh"]
   ```
4. Resume execution (Debug → Continue)

You should see logs like:
```
BackgroundSync: Background refresh task started
BackgroundSync: Scheduled background refresh
BackgroundSync: Location uploaded successfully
BackgroundSync: Fetched 3 friend locations
BackgroundSync: Background refresh task completed
```

### Background Sync Architecture

Two mechanisms handle background location sharing:

| Mechanism | Trigger | Use Case |
|-----------|---------|----------|
| Significant Location Change (SLC) | ~500m movement | Updates when user moves |
| BGAppRefreshTask | iOS-determined (~15min+) | Periodic sync when stationary |

Both require **Always** location permission, enabled via the "Share automatically" toggle.

### Console Logging

Filter Xcode console by `BackgroundSync` to see background activity:
- `BackgroundSync: Background refresh task started/completed`
- `BackgroundSync: Significant location change detected`
- `BackgroundSync: Location uploaded successfully`
- `BackgroundSync: Fetched N friend locations`

### Permissions

The app requests these permissions:
- **Location When In Use**: Basic location for manual sharing
- **Location Always**: Required for background sync (SLC + BGAppRefreshTask)

The "Share automatically" toggle in the profile sheet gates on Always permission and will prompt if not granted.
