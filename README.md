# Despia V3 Extensions skill

An Agent Skill that teaches an AI coding agent to write a Despia V3 Extension: native Swift and Kotlin or Java code called from a V3 web-view app with `extension://<name>/<route>`. It is for the Despia V3 runtime only, and V3 Extensions are experimental. First-party V4 support comes through the new package registry.

The skill is self-contained: `skills/despia-v3-extensions/SKILL.md` is the workflow, `references/` holds the manifest, bridge and platform facts plus Background Recorder, Live Activities and Home Widgets references. `examples/recorder/` holds the Background Recorder sample files. The Live Activities and Home Widgets references include their routes, answers, platform limits and recorded test results. Docs: https://docs.despia.com/experiments/v3-extensions

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

Apache License 2.0, see LICENSE.
