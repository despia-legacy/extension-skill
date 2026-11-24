# Despia V3 Extensions skills

Agent Skills that teach a coding agent to install, configure and build Despia V3 extensions: native Swift and Kotlin or Java code called from a V3 app with `extension://<name>/<route>`. They are for the Despia V3 runtime only.

| Skill | Your agent uses it to |
| --- | --- |
| `skills/despia-extensions-cli` | Find, install, configure, update, remove and check ready-made extensions with `npx despia-extensions` |
| `skills/despia-v3-extensions` | Build your own extension: create, validate, mock, compile and test, with the native contract and the fix for every error code |

Each skill matches its docs pages, which it names: https://setup.despia.com/extensions/install and https://setup.despia.com/extensions/overview. The references under `skills/despia-v3-extensions/references/` are generated from those pages. `examples/recorder/` holds the Background Recorder extension files. Guide: https://setup.despia.com/extensions/ai-skills

## Install

With the `skills` CLI:

```bash
npx skills add despia-legacy/extension-skill
npx skills add despia-legacy/extension-skill --skill despia-extensions-cli -a claude-code
```

Skills go into the current project by default; add `-g` for every project. To copy a folder yourself, put it in your agent's skill folder: `.claude/skills/` for Claude Code, `.agents/skills/` for Codex and Cursor.

```bash
git clone https://github.com/despia-legacy/extension-skill
mkdir -p .claude/skills
cp -R extension-skill/skills/despia-extensions-cli extension-skill/skills/despia-v3-extensions .claude/skills/
```

## License

The skill instructions use Apache License 2.0, see [LICENSE](LICENSE).

The Despia-authored extension code in `skills/despia-v3-extensions/examples/recorder/` uses the [Despia V3 Extension Source-Available Commercial License](skills/despia-v3-extensions/examples/recorder/LICENSE), version 1.4. It is proprietary and source available. Anyone with lawful access can inspect, copy, modify, build and run it for noncommercial development, evaluation and testing with a Despia V3 app without buying a paid Despia license. This includes developing an app you intend to release commercially later.

Production deployment and commercial use require a valid Despia commercial license and project for the particular application. A Solo Pack covers one app on its licensed operating system, iOS or Android. A Hybrid Pack covers one app on both. An existing applicable license includes all Despia V3 extension templates you have lawful access to and any number of your own extensions for that app and operating system. No separate extension purchase or per-extension fee applies. See [Despia's current plans](https://despia.com/).

You can fork the source, keep private development copies, customize it and use it as a reference under the license. [Public GitHub forks remain public](https://docs.github.com/en/pull-requests/reference/forks). Keep the license, copyright and third-party notices. Standalone redistribution is restricted except for GitHub forks expressly permitted by the license. Third-party rights and permissions already granted for earlier releases remain intact.
