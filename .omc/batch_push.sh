#!/bin/bash
# Batched incremental push to work around lossy upstream link.
# Each batch only transfers new objects since the previous batch.
cd "Z:/hacker_project/self-dns" || exit 1
SSH443="ssh -p 443 -o StrictHostKeyChecking=accept-new"

mapfile -t commits < <(git rev-list --reverse main)
n=${#commits[@]}
echo "total commits on main: $n"
BATCH=40
for ((i=BATCH-1; i<n; i+=BATCH)); do
  tip=${commits[$i]}
  echo "=== batch up to $tip ==="
  if ! git -c core.sshCommand="$SSH443" push origin "$tip:refs/heads/main" 2>&1 | tail -1; then
    echo "BATCH_FAILED_AT_$tip"
    exit 1
  fi
done
echo "=== final main ==="
if ! git -c core.sshCommand="$SSH443" push origin main 2>&1 | tail -1; then
  echo "MAIN_FINAL_FAILED"
  exit 1
fi
echo "=== feature branch ==="
if ! git -c core.sshCommand="$SSH443" push origin feature/cn-rules-startup-fix 2>&1 | tail -1; then
  echo "FEATURE_FAILED"
  exit 1
fi
git -c core.sshCommand="$SSH443" push origin --delete __probe 2>&1 | tail -1
echo "ALL_PUSHED"
