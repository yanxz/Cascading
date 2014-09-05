#!/bin/bash

echo "Updating libs: ""$@"
bundle update "$@"
git add Gemfile.lock
git commit -m "Gemfile.lock automatically updated to latest version (see diff for details)"
git fetch origin
git rebase origin/master
git push origin master
