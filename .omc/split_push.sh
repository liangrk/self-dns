#!/bin/bash
# Build a fresh, directory-split commit chain on an orphan branch and push
# batch by batch. Small packs survive the lossy upstream link; the single
# 34MB pack does not. Final chain becomes the remote main.
cd "Z:/hacker_project/self-dns" || exit 1
S="git -c core.sshCommand=ssh -p 443 -o StrictHostKeyChecking=accept-new"

echo "### start from current working tree (feature content)"
git checkout --orphan tmpbuild 2>&1 | head -1
git rm -rf --cached . -q
git config user.name >/dev/null && echo "git identity ok"

push_tip() {
  if ! git -c "core.sshCommand=ssh -p 443 -o StrictHostKeyChecking=accept-new" push origin tmpbuild:refs/heads/main 2>&1 | tail -1; then
    echo "PUSH_FAILED_AT_STAGE: $1"
    exit 1
  fi
}

commit_and_push() {
  local msg="$1"; shift
  echo "=== $msg ==="
  git add -- "$@"
  git commit -qm "$msg" || { echo "NOTHING_TO_COMMIT"; return 1; }
  push_tip "$msg"
}

# Stage 1: small top-level files, docs, CI workflows, skills, TV module
commit_and_push "chore: import upstream project docs, CI workflows and TV module" \
  .agent .github docs scripts .gitignore LICENSE README.md CLAUDE.md \
  Gemfile Gemfile.lock .run metadata fastlane blockadstv ISSUES_ANALYSIS.md ISSUES_REPORT_2026-06-11.md 2>/dev/null

# Stage 2: Go tunnel sources
commit_and_push "chore: import upstream Go tunnel module" tunnel

# Stage 3: app sources (everything in app/ except libs/)
commit_and_push "chore: import upstream Android app sources" \
  app/src app/schemas app/.gitignore app/build.gradle.kts app/proguard-rules.pro app/google-services.yml app/ic_launcher-playstore.png 2>/dev/null

# Stage 4: the big binary (tunnel.aar) alone — the riskiest batch
commit_and_push "chore: import upstream prebuilt tunnel AAR" app/libs

# Stage 5: CN enhancement work (final tree = current feature content)
commit_and_push "feat: CN ad rules integration, ads-only mode, region-aware DNS, startup optimization" \
  .

echo "### final tree check"
git diff --stat feature tmpbuild | tail -1
echo "ALL_STAGES_PUSHED"
