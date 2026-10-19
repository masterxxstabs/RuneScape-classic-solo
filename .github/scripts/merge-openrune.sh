#!/usr/bin/env bash
# Merges OpenRune's main branch into the checked-out branch and commits the result. This
# repository's README and the upstream workflows it removed are kept as they are; any other
# conflict aborts the merge. Writes merged/commits/conflicts to $GITHUB_OUTPUT when set.
set -euo pipefail

UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/OpenRune/OpenRune-Server.git}"
UPSTREAM_BRANCH="${UPSTREAM_BRANCH:-main}"
OUTPUT="${GITHUB_OUTPUT:-/dev/null}"
REMOVED_WORKFLOWS=(
    content-progress.yml
    openwiki-update.yml
    shop-mapping-progress.yml
    update-readme-on-rev-change.yml
    update-test-server.yml
)

git config user.name >/dev/null || git config user.name "github-actions[bot]"
git config user.email >/dev/null ||
    git config user.email "41898282+github-actions[bot]@users.noreply.github.com"

git fetch --quiet --no-tags "$UPSTREAM_URL" "$UPSTREAM_BRANCH"
upstream="$(git rev-parse FETCH_HEAD)"

if git merge-base --is-ancestor "$upstream" HEAD; then
    echo "Already up to date with OpenRune ($upstream)."
    echo "merged=false" >>"$OUTPUT"
    exit 0
fi

commits="$(git rev-list --count "HEAD..$upstream")"
echo "Merging $commits new OpenRune commit(s) up to $upstream..."
git merge --no-ff --no-commit "$upstream" || true

git checkout HEAD -- README.md
for workflow in "${REMOVED_WORKFLOWS[@]}"; do
    git rm --quiet --force --ignore-unmatch -- ".github/workflows/$workflow"
done

conflicts="$(git diff --name-only --diff-filter=U)"
if [ -n "$conflicts" ]; then
    git merge --abort
    {
        echo "conflicts<<CONFLICTS"
        echo "$conflicts"
        echo "CONFLICTS"
    } >>"$OUTPUT"
    echo "OpenRune changes conflict with this repository's changes in:"
    echo "$conflicts"
    exit 1
fi

git commit --quiet --no-verify -m "Merge $commits OpenRune update(s)" \
    -m "Merged OpenRune-Server $UPSTREAM_BRANCH at $upstream."
echo "merged=true" >>"$OUTPUT"
echo "commits=$commits" >>"$OUTPUT"
echo "Merged as $(git rev-parse --short HEAD)."
