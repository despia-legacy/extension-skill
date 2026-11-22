# Despia V3 Extensions skill

An Agent Skill that teaches an AI coding agent to write a Despia V3 Extension: native Swift and Kotlin or Java code called from a V3 web-view app with `extension://<name>/<route>`. It is for the Despia V3 runtime only, and V3 Extensions are experimental. First-party V4 support comes through the new package registry.

The skill is self-contained: `skills/despia-v3-extensions/SKILL.md` is the workflow, `references/` holds the manifest, bridge and platform facts plus Background Recorder, Live Activities and Home Widgets references. `examples/recorder/` holds the Background Recorder extension files. The Live Activities and Home Widgets references include their routes, answers and platform limits. Docs: https://setup.despia.com/experiments/v3-extensions

## Install

With the `skills` CLI:

```bash
npx skills add despia-legacy/extension-skill
```

With Claude Code, copy the skill folder into your project (or `~/.claude/skills/` for all projects):

```bash
git clone https://github.com/despia-legacy/extension-skill
mkdir -p .claude/skills
cp -R extension-skill/skills/despia-v3-extensions .claude/skills/
```

## License

The skill instructions use Apache License 2.0, see [LICENSE](LICENSE).

The Despia-authored extension code in `skills/despia-v3-extensions/examples/recorder/` uses the [Despia V3 Extension Source-Available Commercial License](skills/despia-v3-extensions/examples/recorder/LICENSE), version 1.4. It is proprietary and source available. Anyone with lawful access can inspect, copy, modify, build and run it for noncommercial development, evaluation and testing with a Despia V3 app without buying a paid Despia license. This includes developing an app you intend to release commercially later.

Production deployment and commercial use require a valid Despia commercial license and project for the particular application. A Solo Pack covers one app on its licensed operating system, iOS or Android; a Hybrid Pack covers one app on both. An existing applicable license includes all Despia V3 extension templates you have lawful access to and any number of your own extensions for that app and operating system. No separate extension purchase or per-extension fee applies. See [Despia's current plans](https://despia.com/).

You can fork the source, keep private development copies, customize it and use it as a reference under the license. [Public GitHub forks remain public](https://docs.github.com/en/pull-requests/reference/forks). Keep the license, copyright and third-party notices. Standalone redistribution is restricted except for GitHub forks expressly permitted by the license. Third-party rights and permissions already granted for earlier releases remain intact.
