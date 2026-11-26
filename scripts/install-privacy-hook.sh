#!/bin/sh
# OPTIONAL MAINTAINER TOOL: contributors should not run this. The guard it installs needs a
# private term list that only the maintainer has, and it fails closed without one, so it would
# block every push from a clone that lacks the list.
# Install the local pre-push privacy guard (scripts/privacy-check.mjs) into this clone.
# Works under Git for Windows (Git Bash) and on Linux/macOS. Needs Node on PATH.
# Usage: sh scripts/install-privacy-hook.sh
# The hook is local to this clone and is not committed. Bypassing it
# (git push --no-verify) skips the check, so don't.
set -eu

root="$(git rev-parse --show-toplevel)"
hooks="$(git rev-parse --git-path hooks)"
case "$hooks" in /*|[A-Za-z]:*) ;; *) hooks="$root/$hooks" ;; esac
hook="$hooks/pre-push"
mkdir -p "$hooks"

if [ -f "$hook" ] && ! grep -q 'privacy-check.mjs' "$hook"; then
	mv "$hook" "$hook.before-privacy-check"
	echo "Kept the previous pre-push hook as $hook.before-privacy-check"
fi

cat > "$hook" <<'HOOK'
#!/bin/sh
# Local privacy guard - installed by scripts/install-privacy-hook.sh.
# Blocks a push whose commits carry a term from the private term list
# or an email that is not a GitHub no-reply address.
root="$(git rev-parse --show-toplevel)" || exit 2
script="$root/scripts/privacy-check.mjs"
if [ ! -f "$script" ]; then
	echo "privacy guard: $script is missing; push blocked." >&2
	exit 2
fi
exec node "$script" --repo "$root" --pre-push "$@"
HOOK
chmod +x "$hook"

terms="$root/private-docs/privacy-terms.txt"
common="$(git rev-parse --git-common-dir)"
case "$common" in /*|[A-Za-z]:*) ;; *) common="$(pwd)/$common" ;; esac
main_terms="$(dirname "$common")/private-docs/privacy-terms.txt"
echo "Installed $hook"
if [ ! -f "$terms" ] && [ ! -f "$main_terms" ]; then
	echo "Note: no term list at $terms or $main_terms yet; every push is blocked until one exists."
fi
