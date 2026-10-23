#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [[ ! -e config.properties && ! -L config.properties ]]; then
    # The file will hold an API key; only its owner should be able to read it.
    (umask 077; cp config.properties.example config.properties)
    echo "Created config.properties. Add your API key before running the chatbot."
fi

mvn -B -ntp dependency:go-offline test
