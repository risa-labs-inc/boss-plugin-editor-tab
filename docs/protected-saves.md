# Protected document saves

Companion to [editor-tab #31](https://github.com/risa-labs-inc/boss-plugin-editor-tab/issues/31)
and [BossConsole #427](https://github.com/risa-labs-inc/BossConsole/pull/427).

Cmd/Ctrl+S, autosave, and Cmd/Ctrl+S in the editable diff pane use
`saveEditorDocument`. It calls the host's `EditorContentProvider.writeFileContent`,
the same protected writer used by `editor_write_file`. A missing provider, a
false result, or an ordinary exception produces a visible save error and leaves
the document modified. There is no direct-write fallback.

The buffer's successful-write signature, external state, and git refresh flag
advance only after the provider reports a commit. Saves from shared viewports
are serialized per buffer. A save only marks the captured document version
saved, so typing during a write remains dirty. Once writing begins, cancellation
of the autosave timer cannot skip commit bookkeeping.

External edits are checked before saving. An unresolved conflict raised by the
watcher also blocks saving until the user chooses Reload or Keep mine. This is
a pre-save check, not a filesystem compare-and-swap: another program can still
race between the check and commit.

## Compatibility and release ordering

The API signature is unchanged (boss-plugin-api 1.0.87), but the writer's safety
contract requires BossConsole #427. Released BOSS 9.5.11 does not contain it.
The manifest therefore reserves **9.5.12** as the minimum host version.

1. Merge and release BossConsole #427 in 9.5.12 or later.
2. Before merging this plugin PR, verify the actual host release includes #427.
   If the first fixed host is later than 9.5.12, raise `minBossVersion` to that
   release. The number alone is not evidence that the writer was shipped.
3. Only then merge/release this plugin: pushes to `main` publish automatically.
   Older hosts must refuse this plugin via the manifest gate.

Keep the companion PR in draft until that release dependency is satisfied.
Testing against a source build of #427 is useful, but does not make an older
released host compatible. No new API artifact is required.

## Filesystem behavior inherited from the host

The host writes and syncs a unique sibling staging file, then requires atomic
replacement. A partial staging write or failed promotion preserves the existing
destination; a failed first save leaves no partial destination. Ordinary failure
cleans staging output. Unsupported atomic replacement fails without retrying
in place. Saving requires temporary disk space and parent-directory write access.

Existing symlinks resolve to their targets; dangling symlinks fail safely.
Read-only and non-regular targets are rejected. Supported metadata preservation
covers nine POSIX rwx bits, Windows ACLs and DOS flags; POSIX owner/group are
best effort. New POSIX files respect umask. Special mode bits and arbitrary
extended attributes are not preserved. Atomic replacement changes file identity:
hard-linked aliases retain the old content. Locks can prevent replacement.
Process termination may leave a staging file, and parent-directory durability
after power loss is not guaranteed.

## Regression coverage

`EditorDocumentSaveTest` exercises the shared save pipeline, provider failures
and absence, dirty state and baseline preservation, conflicts, shorter UTF-8
replacement, overlapping manual/autosave requests, and debounce cancellation
with newer typing. Its staging provider is a test double for the provider
contract, not a second production implementation of atomic writes.

The real filesystem implementation and fault injection are covered by
`EditorFileWriterTest` and `EditorFileIoTest` in BossConsole #427. Run those along
with this plugin's full Kotlin suite, which retains the shared-buffer and
external-change tests from #28. Platform-specific symlink, POSIX permission and
Windows ACL/lock tests belong to the host suite.

```sh
# In the plugin checkout, with the API jar configured as in .github/workflows/tests.yml:
./gradlew test buildPluginJar
node src/test/js/preview-dom.test.mjs

# In the BossConsole #427 checkout:
./gradlew :composeApp:desktopTest --tests '*EditorFileWriterTest*' --tests '*EditorFileIoTest*'
```

Manual app verification: edit a file and save with Cmd/Ctrl+S, then enable
autosave and edit again. Repeat with replacement blocked (for example by a
Windows file lock). Check the visible error, modified marker, original bytes,
and a successful retry after unblocking. Repeat Cmd/Ctrl+S from the diff pane.
