# From hosted source to an installed feature

## Four different checks

| Stage | What it establishes | What it does not establish |
| --- | --- | --- |
| Hosting | Required paths return the intended JSON, native source and resources | That a native app contains them |
| Input verification | The selected source and dependency graph match the verified build inputs | That the code compiles or behaves correctly |
| Native build and artifact checks | The selected extension compiles and the resulting artifact matches the checked inputs | User consent, device behavior, store approval or a successful provider operation |
| Installed-app exercise | A particular artifact performs the exercised behavior | Untested lifecycle cases, devices, providers or combinations |

Uploading or deploying web files does not update an installed native binary. Changed native files, manifests, dependencies, permissions or targets require fresh build verification and a native rebuild. Web UI changes can be hosted separately only when they remain compatible with the installed native API.

Extension builds are not yet open to every app. An app that serves `/despia/extensions.json` today gets a clear build error rather than a build without its extensions. The standard build of an app with no index is unchanged. There is no per-app enrollment switch. Check the actual build inputs and installed artifact. Do not claim that source hosting alone triggers native inclusion. The source/dependency and signing checks are technical verification, not a human review of every developer-owned extension.

## Prepare the source

1. Write the index with the `extensions` key only: 0 to 8 unique folder names. Despia reads the app's client id, bundle id and Android package from the build, so you add none of them.
2. Put the index at `public/despia/extensions.json` and each manifest at `public/despia/<name>/manifest.json`. Serve the native files and resources at their exact paths on the app's HTTPS origin.
3. Check response bodies, not just HTTP status. A SPA fallback returning HTML with status 200 is not a valid JSON index or Swift file. Keep static extension paths ahead of the fallback, and do not block the build's automated fetcher with bot protection or sign-in.
4. Declare every required source, resource, target and pinned dependency. Keep trusted policy/signing inputs outside the public source tree. Do not place service credentials or private signing material in an extension folder.
5. Keep the build inputs stable while verifying and building. Changing a served file without refreshing the verified snapshot is a different source input, not a harmless hosting change.

## Prepare the native platforms

Use the host project's actual compiler, language mode, SDK, minimum OS and dependency graph. A Swift compiler version is not the Swift language mode. A vendor's latest documentation may describe a different SDK version from the one pinned in your manifest. Inspect its real headers or public API and compile the selected source against it.

A dependency's minimum OS and an extension's availability check are different constraints. The current `android.min_sdk` gates routes below that floor; it does not grant permission to link an incompatible library into a lower-minimum host app. Do not use `overrideLibrary` to conceal that incompatibility. A host minimum-SDK opt-in remains a separate runtime change until it is shipped.

Permissions have distinct layers: a declaration in the compiled app, purpose text or entitlement/provisioning where required, a runtime user decision and any store policy. Check each needed layer. For App Groups, register the group and both app/target identifiers and regenerate the matching profiles. See `manifest.md` for the currently deployed `.NXWidgets` target naming.

## Exercise the installed API

Use the published `despia-native` SDK from the trusted top-level app page. First read a real status route with the exact manifest output variable. A browser preview, iframe or wrong native build cannot be treated as the installed extension runtime.

Then test the actual operation, denied permissions and unsupported OS cases. For stateful work, also exercise page reload, background/foreground and the specific process termination/recovery behavior you promise. Re-read durable state or a journal after reconnecting; an event is not a durable delivery mechanism.

For uploads, verify the server's expected method, body format, content type, authentication, redirects, success codes and retry/idempotency behavior. A raw file PUT is not a multipart POST. A recorded file, a scheduled upload and a server-confirmed upload are separate outcomes. Do not submit real private data before the endpoint, logging and retention behavior are reviewed.

## Report a bounded result

Record the source revision, selected extension names, native build command and exit code, artifact identity and the operations actually exercised. State whether evidence came from a validator, mock, real SDK compile, simulator/emulator, physical device or a live provider. Keep negative results and limitations. A pass from one extension or a standalone writer does not transfer to another extension or a combined recording/upload workflow.

If a required build, capability approval, device, account or provider is unavailable, prepare the inputs and name the missing verification. Do not invent a successful result or an administrative control to explain the gap.
