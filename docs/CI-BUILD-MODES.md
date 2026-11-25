# Android CI build modes

## Fast Debug

Manual: Actions → Android CI → Run workflow → select feature branch → mode **fast**. Draft pull requests also run this mode automatically.

Compiles Debug, runs Debug unit tests, verifies APK signature and permissions, produces checksum and a `AndroidAuto-Bridge-fast-debug-<run>` artifact. It is for local testing only, not a full security audit.

## Full Build

Runs automatically on main/security pushes and on ready-for-review pull requests (including when a draft becomes ready). Manual runs on a feature branch can select **full**; manual main/security runs are always full.

Adds minified Release assembly, dependency inventory, OSV audit and ClamAV with fresh signatures. Produces the existing `AndroidAuto-Bridge-debug-<run>` artifact. Do not merge or publish based only on Fast Debug. Repository merge protection, if desired, must be configured separately; this workflow does not change repository protection rules.

## Performance

The pinned official Gradle setup action replaces the duplicate dependency cache and saves reusable Gradle state. Build Cache, daemon and parallel execution are enabled for CI; heap/worker count adapt to runner RAM. The dependency audit uses the same daemon and explicitly disables Configuration Cache because its init script accesses Gradle project state at execution time. Configuration Cache remains off for normal builds until compatibility and actual benefit are measured; no encryption key is needed. SDK install is skipped only if required components are already present; full antivirus still refreshes its signatures. APK upload uses compression level 0 to avoid recompressing the ZIP-format APK.

Signing uses the existing repository secret, never stored in source or in the Gradle cache. See [DEBUG-SIGNING.md](DEBUG-SIGNING.md). Missing secret retains the existing warning; subsequent APK updates are not guaranteed in that case.

Manual runs are in a separate concurrency group and cannot cancel automatic PR audits. For everyday iterative development, keep the PR as a draft and mark it ready before merge. The first cache-enabled run may be slower while populating caches. Compare warm runs in the Actions timing view.
