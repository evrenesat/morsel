# Checkpoint 2 material review — 80f5d4d

Reviewer: supervising Codex. Implementation/fixes remain assigned to ZCode GLM-5.3-Flash.
Do these focused corrections before APK release, then run the complete existing checks and emulator tests. No architecture expansion.

## [P1, high] Use the required Preferences DataStore filename extension
SettingsStore.kt:43–44 and FeedJournal.kt:72–73 pass morsel.settings_pb / morsel.journal_pb to PreferenceDataStoreFactory. The installed AndroidX 1.1.7 factory checks File.extension == "preferences_pb" and throws IllegalStateException otherwise. Verified directly with javap on the installed datastore-preferences-core-android AAR, class PreferenceDataStoreFactory$create$delegate$1. The first real settings/journal read therefore fails when the UI initializes, although fake-store JVM tests pass.

Change both filenames to distinct files ending .preferences_pb in noBackupFilesDir. This is pre-release, so no released data migration exists. Add an instrumented smoke test that creates the actual SettingsStore and DataStoreFeedJournal, writes/reads each and restarts their scopes correctly. Verify cold launch and setup on emulator.

## [P2, high] Count acknowledged journal entries as resolved when trimming
FeedJournal.kt:104–108 checks only FeedState.unresolvedState and ignores acknowledgedAtEpochMs. Production feeding has no proven correlation field, so normal accepted requests remain ACCEPTED_UNCONFIRMED and the user explicitly acknowledges them. After 25 acknowledged requests, the 26th operation gives unresolved.size=26 and calls takeLast(-1), throwing and permanently preventing further journal writes/feeding.

Use FeedOperation.unresolved semantics (state plus acknowledgement) for the partition; preserve genuinely unresolved entries and keep the resolved tail bounded. Ensure no negative takeLast argument. Add a regression using the actual persisted journal with >=26 sequential accepted+acknowledged operations and a new dispatch, plus preservation of a genuinely unresolved entry. Do not increase the cap to conceal the bug.

## Implementation and verification order
1. Correct the two DataStore filenames.
2. Correct journal partition semantics.
3. Add the focused tests above, exercising production storage instead of an in-memory fake.
4. Run ./gradlew --no-daemon spotlessCheck lintDebug testDebugUnitTest assembleDebug.
5. Run ./gradlew --no-daemon connectedDebugAndroidTest in emulator CI. Record exact run links/results in docs/implementation-status.md.
6. Commit and push with a concise fix/morsel message. Mark this plan resolved only after evidence.

## UI acceptance reminder (in-progress code, not a committed-code finding)
The original implementation plan requires zero selected portions on each fresh session, empty cup, and Feed disabled until plus is tapped. The current unfinished ViewModel initially uses 1 and clamps decrement to 1. Ensure the completed UI starts and decrements to zero, while the coordinator still only accepts writes for 1–16.

Verdict: Material fixes required.
