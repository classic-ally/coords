#!/usr/bin/env bash
# Update cities.json and boundaries.json with Natural Earth polygon data
#
# This script:
# 1. Downloads Natural Earth 10m admin boundaries (if not cached)
# 2. Converts to simplified GeoJSON
# 3. Updates city regions based on polygon containment
# 4. Generates pruned boundaries.json for embedding in core
#
# Prerequisites: Run from nix dev shell (nix develop)
# Usage: ./update_cities.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$(dirname "$SCRIPT_DIR")"
CACHE_DIR="${SCRIPT_DIR}/.cache"
NE_URL="https://naciscdn.org/naturalearth/10m/cultural/ne_10m_admin_1_states_provinces.zip"
NE_ZIP="${CACHE_DIR}/ne_10m_admin_1_states_provinces.zip"
NE_SHP="${CACHE_DIR}/ne_10m_admin_1_states_provinces.shp"
BOUNDARIES_FULL="${CACHE_DIR}/boundaries_full.json"
BOUNDARIES_OUTPUT="${CORE_DIR}/src/boundaries.json"
CITIES_INPUT="${SCRIPT_DIR}/cities.json"
CITIES_OUTPUT="${CORE_DIR}/src/cities.json"

# Simplification tolerance in degrees (~5.5km at equator)
SIMPLIFY_TOLERANCE="0.05"

mkdir -p "$CACHE_DIR"

echo "=== Updating cities.json with polygon region data ==="
echo ""

# Download Natural Earth data if not cached
if [[ ! -f "$NE_ZIP" ]]; then
    echo "Downloading Natural Earth 10m admin boundaries..."
    curl -L -o "$NE_ZIP" "$NE_URL"
fi

# Extract if not already done
if [[ ! -f "$NE_SHP" ]]; then
    echo "Extracting..."
    unzip -q -o "$NE_ZIP" -d "$CACHE_DIR"
fi

# Convert to GeoJSON with simplification
if [[ ! -f "$BOUNDARIES_FULL" ]] || [[ "$NE_SHP" -nt "$BOUNDARIES_FULL" ]]; then
    echo "Converting to GeoJSON (with ${SIMPLIFY_TOLERANCE} degree simplification)..."
    ogr2ogr -f GeoJSON "$BOUNDARIES_FULL" "$NE_SHP" -simplify "$SIMPLIFY_TOLERANCE" -select name,admin
    echo "  Created: $BOUNDARIES_FULL ($(du -h "$BOUNDARIES_FULL" | cut -f1))"
fi

# Run preprocessing (updates cities and generates pruned boundaries)
echo ""
echo "Running preprocessing..."
python3 "$SCRIPT_DIR/preprocess_cities.py" \
    --cities "$CITIES_INPUT" \
    --boundaries "$BOUNDARIES_FULL" \
    --output-cities "$CITIES_OUTPUT" \
    --output-boundaries "$BOUNDARIES_OUTPUT"

echo ""
echo "=== Done ==="
echo "Updated: $CITIES_OUTPUT"
echo "Updated: $BOUNDARIES_OUTPUT"
