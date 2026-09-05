#!/bin/bash
# Retry-push helper: GitHub upstream from this network is lossy/slow.
cd "Z:\hacker_project\self-dns" || exit 1
SSH443="ssh -p 443 -o StrictHostKeyChecking=accept-new"

for i in 1 2 3 4 5 6 7 8 9 10; do
  echo "=== attempt $i ==="
  if git -c core.sshCommand="$SSH443" push origin main 2>&1 | tail -2; then
    if git -c core.sshCommand="$SSH443" push origin feature/cn-rules-startup-fix 2>&1 | tail -2; then
      echo "BOTH_PUSHED"
      exit 0
    fi
  fi
  echo "--- retrying in 30s ---"
  sleep 30
done
echo "ALL_ATTEMPTS_EXHAUSTED"
exit 1
